"""Rehearse disaster recovery of MySQL and build objects on the isolated Jib stack.

Order: continuous binlog and object copies start first, a full dump follows before any
business phase, so every purchase exists only in the copies. Then the MySQL and
MinIO containers are removed with their data, restored with tools/operations/backup.py
and compared with the state captured just before the loss.
"""

import hashlib
import json
import os
import secrets
import signal
import subprocess
import sys
import time
from pathlib import Path

from support.image_names import DEFAULT_REGISTRY, refuse_docker_hub_stove


SCHEMAS = ("stove_auth", "stove_studio", "stove_review", "stove_catalog", "stove_store",
           "stove_order", "stove_payment", "stove_license", "stove_settlement")
BACKUP_GRANTS = "SELECT, SHOW VIEW, TRIGGER, LOCK TABLES, EVENT, RELOAD, REPLICATION CLIENT, REPLICATION SLAVE"
ROOT_PASSWORD = "root1234"
BUCKET = "stove-builds"
CLIENT_IMAGE = refuse_docker_hub_stove("STOVE_MYSQL_CLIENT_IMAGE", os.environ.get(
    "STOVE_MYSQL_CLIENT_IMAGE", DEFAULT_REGISTRY + "/mysql-client:8.0-local").strip())


def ensure_client_image(root):
    """The official MySQL image lacks mysqlbinlog; build the matching client image when absent."""
    present = subprocess.run(["docker", "image", "inspect", CLIENT_IMAGE],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode:
        subprocess.run(["./gradlew", ":infra:mysql-client:jibDockerBuild", "-Pstove.mysqlClient.image=" + CLIENT_IMAGE,
                        "--no-daemon", "--console=plain"], cwd=root, check=True)
    return CLIENT_IMAGE


class BackupRestoreVerification:
    def __init__(self, root, output, minio_image, capture):
        self.root, self.output, self.capture = root, output, capture
        self.minio_image = minio_image
        self.original_volumes = set(capture(["docker", "volume", "ls", "-q"]).splitlines())
        self.backup_password = secrets.token_urlsafe(24)
        self.backup = output / "backup"
        self.backup.mkdir()
        self.copies = []
        self.evidence = {}

    def init_sql(self):
        """Least-privilege account used for the dump and the binlog stream."""
        return ("CREATE USER 'backup'@'%' IDENTIFIED BY '" + self.backup_password + "';"
                " GRANT " + BACKUP_GRANTS + " ON *.* TO 'backup'@'%';")

    def bind(self, docker, run, project):
        self.docker, self.run = docker, run
        self.network = project + "_app"

    def tool(self, *arguments):
        return [sys.executable, str(self.root / "tools/operations/backup.py"), *arguments]

    def environment(self):
        return dict(os.environ, MYSQL_BACKUP_PASSWORD=self.backup_password, MYSQL_RESTORE_PASSWORD=ROOT_PASSWORD,
                    STORAGE_BACKUP_ACCESS_KEY="build-backup", STORAGE_BACKUP_SECRET_KEY=self.backup_password,
                    STORAGE_RESTORE_ACCESS_KEY="stove", STORAGE_RESTORE_SECRET_KEY="stove1234")

    def provision_backup_account(self):
        """Read and change notifications only: the mirror can neither write nor delete builds."""
        policy = json.dumps({"Version": "2012-10-17", "Statement": [
            {"Effect": "Allow", "Action": ["s3:GetObject", "s3:GetObjectTagging"],
             "Resource": ["arn:aws:s3:::" + BUCKET + "/*"]},
            {"Effect": "Allow", "Action": ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListenBucketNotification"],
             "Resource": ["arn:aws:s3:::" + BUCKET]}]})
        mc = self.docker + ["exec", "-T", "minio", "mc"]
        self.run(self.docker + ["exec", "-T", "minio", "sh", "-c", "cat > /tmp/backup-policy.json"],
                 "minio-backup-policy-file", input=policy, text=True)
        self.run(mc + ["admin", "policy", "create", "admin", "build-backup", "/tmp/backup-policy.json"],
                 "minio-backup-policy")
        self.run(self.docker + ["exec", "-T", "-e", "BACKUP_SECRET", "minio", "sh", "-c",
                                'exec mc admin user add admin build-backup "$BACKUP_SECRET"'],
                 "minio-backup-user", env=dict(os.environ, BACKUP_SECRET=self.backup_password))
        self.run(mc + ["admin", "policy", "attach", "admin", "build-backup", "--user", "build-backup"],
                 "minio-backup-attach")
        denied = subprocess.run(self.docker + ["exec", "-T", "-e", "BACKUP_SECRET", "minio", "sh", "-c",
                                               'mc alias set backup http://localhost:9000 build-backup "$BACKUP_SECRET" >/dev/null'
                                               ' && echo denied | mc pipe backup/' + BUCKET + '/backup-write-check.bin'],
                                cwd=self.root, env=dict(os.environ, BACKUP_SECRET=self.backup_password),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if denied.returncode == 0:
            raise RuntimeError("The object backup account accepted an upload")

    def database(self, option, directory):
        return [option, str(directory), "--host", "mysql", "--network", self.network]

    def storage(self, option, directory):
        return [option, str(directory), "--endpoint", "http://minio:9000", "--bucket", BUCKET,
                "--network", self.network, "--image", self.minio_image]

    def start_copies(self):
        """Continuous copies run before any business data is written."""
        self.provision_backup_account()
        for name, arguments in [
                ("binlog-stream", self.tool("stream-binlog", *self.database("--dest", self.backup), "--user", "backup")),
                ("objects-mirror", self.tool("objects-backup", *self.storage("--dest", self.backup), "--watch"))]:
            log = (self.output / (name + ".log")).open("w")
            process = subprocess.Popen(arguments, cwd=self.root, env=self.environment(), stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            self.copies.append((name, process, log))
        deadline = time.monotonic() + 60
        while not any(self.backup.joinpath("binlog").glob("binlog.*")):
            self.check_copies_alive()
            if time.monotonic() > deadline:
                raise RuntimeError("The binlog stream did not start")
            time.sleep(1)
        print("PASS: continuous binlog and object copies started", flush=True)

    def check_copies_alive(self):
        for name, process, _ in self.copies:
            if process.poll() is not None:
                raise RuntimeError(name + " stopped with " + str(process.returncode))

    def full_backup(self):
        self.check_copies_alive()
        self.run(self.tool("full", *self.database("--dest", self.backup), "--user", "backup"), "full-backup",
                 env=self.environment())
        metadata = json.loads(sorted(self.backup.joinpath("full").glob("*.json"))[-1].read_text())
        self.evidence["full_backup"] = {key: metadata[key] for key in ("dump", "binlog_file", "binlog_position", "bytes")}
        # Taken right after the dump: later differences can only come back through the binlogs.
        self.at_full_backup = self.table_checksums()
        print("PASS: full backup at " + metadata["binlog_file"] + ":" + str(metadata["binlog_position"]), flush=True)

    def sql(self, statement):
        # The container already holds its root password; keep it out of command arguments.
        return self.capture(self.docker + ["exec", "-T", "mysql", "sh", "-c",
                                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch '
                                           '--skip-column-names -e "$0"', statement])

    def table_checksums(self):
        tables = self.sql("SELECT CONCAT(table_schema, '.', table_name) FROM information_schema.tables "
                          "WHERE table_type='BASE TABLE' AND table_schema IN ('" + "','".join(SCHEMAS) + "') "
                          "ORDER BY 1").splitlines()
        if not tables:
            raise RuntimeError("No business tables were found")
        rows = self.sql("CHECKSUM TABLE " + ", ".join("`" + name.replace(".", "`.`") + "`" for name in tables)
                        + " EXTENDED").splitlines()
        checksums = dict(line.split("\t") for line in rows)
        counts = {}
        for name in tables:
            schema, table = name.split(".")
            counts[name] = int(self.sql("SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`"))
        return checksums, counts

    def objects(self):
        listing = self.capture(self.docker + ["exec", "-T", "minio", "mc", "ls", "--recursive", "--json",
                                              "admin/" + BUCKET])
        entries = [json.loads(line) for line in listing.splitlines() if line.strip()]
        return {entry["key"]: entry["size"] for entry in entries if entry.get("type") == "file"}

    def object_hash(self, key):
        content = subprocess.run(self.docker + ["exec", "-T", "minio", "mc", "cat", "admin/" + BUCKET + "/" + key],
                                 cwd=self.root, check=True, stdout=subprocess.PIPE).stdout
        return hashlib.sha256(content).hexdigest()

    def wait_for_copies(self, objects):
        status = self.sql("SHOW MASTER STATUS").split("\t")
        name, position = status[0], int(status[1])
        mirrored = self.backup / "objects" / BUCKET
        deadline = time.monotonic() + 90
        while True:
            self.check_copies_alive()
            streamed = self.backup / "binlog" / name
            binlog_ready = streamed.exists() and streamed.stat().st_size >= position
            copied = {str(path.relative_to(mirrored)): path.stat().st_size for path in mirrored.rglob("*") if path.is_file()}
            if binlog_ready and all(copied.get(key) == size for key, size in objects.items()):
                return name, position
            if time.monotonic() > deadline:
                raise RuntimeError("Copies did not catch up: binlog " + str(binlog_ready) + ", objects "
                                   + str(len([key for key in objects if copied.get(key) == objects[key]])) + "/"
                                   + str(len(objects)))
            time.sleep(1)

    def stop_copies(self):
        for name, process, log in self.copies:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            log.close()
        self.copies = []

    def lose_and_restore(self, applications, provision_storage, start_applications):
        # Quiesce writers so the captured state is the last committed state.
        self.run(self.docker + ["stop", *applications], "stop-before-loss")
        expected_checksums, expected_counts = self.table_checksums()
        expected_objects = self.objects()
        if not expected_objects:
            raise RuntimeError("No build objects to protect")
        expected_hashes = {key: self.object_hash(key) for key in expected_objects}
        last_binlog = self.wait_for_copies(expected_objects)
        self.stop_copies()
        after_dump = sum(1 for path in self.backup.joinpath("binlog").glob("binlog.*"))
        print("PASS: copies caught up to " + last_binlog[0] + ":" + str(last_binlog[1]), flush=True)

        # Disk loss: the database and object containers disappear with their data.
        self.run(self.docker + ["rm", "--stop", "--force", "--volumes", "mysql", "minio"], "loss")
        self.run(self.docker + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "mysql", "minio"],
                 "replacement")
        if self.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('"
                    + "','".join(SCHEMAS) + "')") != "0":
            raise RuntimeError("The replacement database is not empty")
        provision_storage()
        if self.objects():
            raise RuntimeError("The replacement bucket is not empty")

        self.run(self.tool("restore", *self.database("--backup", self.backup), "--user", "root"), "restore-database",
                 env=self.environment())
        self.run(self.tool("objects-restore", *self.storage("--backup", self.backup)), "restore-objects",
                 env=self.environment())
        actual_checksums, actual_counts = self.table_checksums()
        if actual_checksums != expected_checksums or actual_counts != expected_counts:
            different = sorted(name for name in expected_checksums if actual_checksums.get(name) != expected_checksums[name])
            raise RuntimeError("Restored tables differ: " + ", ".join(different[:10]))
        actual_objects = self.objects()
        if actual_objects != expected_objects:
            raise RuntimeError("Restored objects differ")
        if any(self.object_hash(key) != digest for key, digest in expected_hashes.items()):
            raise RuntimeError("Restored object content differs")
        dump_checksums, dump_counts = self.at_full_backup
        changed_after_dump = sorted(name for name in expected_checksums if dump_checksums.get(name) != expected_checksums[name])
        rows_added = sum(expected_counts.values()) - sum(dump_counts.values())
        if not changed_after_dump or rows_added <= 0:
            raise RuntimeError("No data changed after the full backup; binlog replay was not exercised")
        self.evidence.update({
            "tables": len(expected_checksums), "rows": sum(expected_counts.values()), "objects": len(expected_objects),
            "rows_at_full_backup": sum(dump_counts.values()), "rows_added_after_full_backup": rows_added,
            "tables_changed_after_full_backup": len(changed_after_dump),
            "binlog_files": after_dump, "last_binlog": list(last_binlog),
            "non_empty_tables": sum(1 for count in expected_counts.values() if count)})
        print("PASS: " + str(len(expected_checksums)) + " tables, " + str(sum(expected_counts.values())) + " rows ("
              + str(rows_added) + " added after the full backup in " + str(len(changed_after_dump)) + " tables) and "
              + str(len(expected_objects)) + " objects restored identically", flush=True)
        start_applications()
        print("PASS: applications restarted on the restored data", flush=True)

    def close(self):
        self.stop_copies()
        (self.output / "backup-restore.json").write_text(json.dumps(self.evidence, indent=2))

    def check_preserved_volumes(self):
        remaining = set(self.capture(["docker", "volume", "ls", "-q"]).splitlines())
        if not self.original_volumes.issubset(remaining):
            raise RuntimeError("An original volume disappeared")
