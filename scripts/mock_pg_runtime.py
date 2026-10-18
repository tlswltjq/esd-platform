"""Run the external simulator with its own MySQL in a disposable verification stack."""
from pathlib import Path
import json
import os
import secrets
import socket
import subprocess
import time
import urllib.request


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class MockPgRuntime:
    def __init__(self, repo, directory):
        self.repo = Path(repo)
        self.directory = Path(directory)
        self.port = free_port()
        self.db_port = free_port()
        self.password = secrets.token_urlsafe(32)
        self.payment_key = secrets.token_urlsafe(32)
        self.settlement_key = secrets.token_urlsafe(32)
        self.control_key = secrets.token_urlsafe(32)
        self.process = None
        self.decision = None
        self.log = None
        self.docker = None

    @property
    def uri(self):
        return f"http://127.0.0.1:{self.port}"

    def compose_service(self):
        return f"""  pg-mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: {self.password}
      MYSQL_DATABASE: mock_pg
      MYSQL_USER: mock_pg
      MYSQL_PASSWORD: {self.password}
    ports: ["127.0.0.1:{self.db_port}:3306"]
"""

    def client_environment(self, app, base):
        result = {key: value for key, value in base.items() if not key.startswith("MOCK_PG_")}
        if app == "payment":
            result.update(MOCK_PG_URI=self.uri, MOCK_PG_PAYMENT_KEY=self.payment_key)
        elif app == "settlement":
            result.update(MOCK_PG_URI=self.uri, MOCK_PG_SETTLEMENT_KEY=self.settlement_key)
        return result

    def sql(self, query):
        result = subprocess.run(self.docker + ["exec", "-T", "-e", "MYSQL_PWD=" + self.password,
            "pg-mysql", "mysql", "-umock_pg", "-N", "mock_pg", "-e", query],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise RuntimeError("PG MySQL query failed")
        return result.stdout.strip()

    def start(self, decision="APPROVED", rate=300):
        self.stop()
        self.decision = decision
        deadline = time.monotonic() + 120
        while True:
            try:
                if self.sql("SELECT 1") == "1":
                    break
            except RuntimeError:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("PG MySQL did not become ready")
            time.sleep(1)
        environment = os.environ.copy()
        environment.update(MOCK_PG_DB_HOST="127.0.0.1", MOCK_PG_DB_PORT=str(self.db_port), MOCK_PG_DB_NAME="mock_pg",
            MOCK_PG_DB_USER="mock_pg", MOCK_PG_DB_PASSWORD=self.password,
            MOCK_PG_PAYMENT_KEY=self.payment_key, MOCK_PG_SETTLEMENT_KEY=self.settlement_key,
            MOCK_PG_CONTROL_KEY=self.control_key, MOCK_PG_DECISION=decision, MOCK_PG_FEE_RATE_BPS=str(rate),
            MOCK_PG_FEE_POLICY_ID="SIMULATED_FEE_V1" if rate == 300 else "SIMULATED_FEE_TEST_" + str(rate))
        jar = self.repo / "simulators/mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar"
        self.log = open(self.directory / "mock-pg.log", "a")
        self.process = subprocess.Popen(["java", "-Xmx128m", "-jar", str(jar), "--server.port=" + str(self.port)],
            cwd=self.repo, env=environment, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while True:
            if self.process.poll() is not None:
                raise RuntimeError("External PG stopped; see " + str(self.directory / "mock-pg.log"))
            try:
                with urllib.request.urlopen(self.uri + "/actuator/health", timeout=2) as response:
                    if response.status == 200:
                        break
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("External PG did not become ready")
            time.sleep(1)
        print("Ready: independent mock PG and MySQL", flush=True)

    def run(self, request_id):
        request = urllib.request.Request(self.uri + "/test-support/v1/settlement-runs",
            data=json.dumps({"requestId": request_id}).encode(),
            headers={"X-Pg-Api-Key": self.control_key, "Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    def stop(self):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None
