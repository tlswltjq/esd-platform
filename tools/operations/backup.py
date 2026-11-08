#!/usr/bin/env python3
"""Back up and restore the platform MySQL schemas and build objects.

Database: a consistent full dump plus continuously streamed binary logs, so a
restore can replay every committed change up to the last streamed event.
Objects: a continuous mirror of the build bucket that never removes copies,
so the backup always contains every object a restored row can reference.

Clients run inside Docker images that match the server versions. Credentials
are read from the environment and passed to containers by variable name only;
they never appear in command arguments or files.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone


SCHEMAS = ("stove_auth", "stove_studio", "stove_review", "stove_catalog", "stove_store",
           "stove_order", "stove_payment", "stove_license", "stove_settlement")
# The official mysql image lacks mysqlbinlog; build this one with ./gradlew :infra:mysql-client:jibDockerBuild
# Self-built images default to localhost/stove, which Docker never pulls from Docker Hub (decision 118).
DEFAULT_MYSQL_IMAGE = os.environ.get("STOVE_MYSQL_CLIENT_IMAGE", "localhost/stove/mysql-client:8.0-local")
DEFAULT_MC_IMAGE = os.environ.get("STOVE_MINIO_IMAGE", "localhost/stove/minio:RELEASE.2025-04-22T22-12-26Z-local")
# Same rule as tools/verification/support/image_names.py; this tool runs on hosts without that package.
DOCKER_HUB_HOSTS = {"docker.io", "index.docker.io", "registry-1.docker.io"}
SOURCE_POSITION = re.compile(
    r"^-- CHANGE (?:REPLICATION SOURCE|MASTER) TO (?:SOURCE|MASTER)_LOG_FILE='([^']+)', "
    r"(?:SOURCE|MASTER)_LOG_POS=(\d+);", re.MULTILINE)
BINLOG_NAME = re.compile(r"^[A-Za-z0-9_.-]+\.(\d{6,})$")
DUMP_COMPLETE = re.compile(r"^-- Dump completed on ", re.MULTILINE)


class BackupError(RuntimeError):
    pass


def required(environment, name):
    value = environment.get(name, "")
    if not value.strip():
        raise BackupError(name + " must be provided in the environment")
    return value


def source_position(dump_text):
    """Return the binary log file and position recorded by --source-data=2."""
    match = SOURCE_POSITION.search(dump_text)
    if not match:
        raise BackupError("The dump does not record a binary log position")
    if not BINLOG_NAME.match(match.group(1)):
        raise BackupError("Unexpected binary log name: " + match.group(1))
    return match.group(1), int(match.group(2))


def binlog_sequence(names, first):
    """Select the consecutive binary logs that start at the dump position."""
    numbered = {}
    for name in names:
        match = BINLOG_NAME.match(name)
        if match and name.rsplit(".", 1)[0] == first.rsplit(".", 1)[0]:
            numbered[int(match.group(1))] = name
    start = int(BINLOG_NAME.match(first).group(1))
    if start not in numbered:
        raise BackupError("The streamed binary logs do not contain " + first)
    selected = [numbered[number] for number in sorted(numbered) if number >= start]
    for previous, current in zip(selected, selected[1:]):
        if int(BINLOG_NAME.match(current).group(1)) != int(BINLOG_NAME.match(previous).group(1)) + 1:
            raise BackupError("Binary log gap between " + previous + " and " + current)
    return selected


def docker_hub_stove(image):
    """Whether Docker would pull this name from Docker Hub's `stove` namespace (someone else's account)."""
    name = image.strip().split("@", 1)[0]
    head, _, last = name.rpartition("/")
    parts = ((head + "/" if head else "") + last.split(":", 1)[0]).split("/")
    first = parts[0]
    if len(parts) > 1 and ("." in first or ":" in first or first == "localhost"):
        if first.lower() not in DOCKER_HUB_HOSTS:
            return False
        parts = parts[1:]
    return len(parts) > 1 and parts[0] == "stove"


def docker_client(image, network, mount, variables, interactive=False, entrypoint=None):
    # Every client container receives database or storage credentials, so never run an image that could
    # come from Docker Hub's `stove` namespace (decision 118).
    if docker_hub_stove(image):
        raise BackupError("Image " + image + " points at Docker Hub's stove account, which is not ours "
                          "(decision 118). Use localhost/stove/... or our registry.")
    command = ["docker", "run", "--rm", "--network", network, "-v", str(mount) + ":/backup",
               "--user", str(os.getuid()) + ":" + str(os.getgid())]
    if interactive:
        command.append("-i")
    if entrypoint:
        command += ["--entrypoint", entrypoint]
    for name in variables:
        command += ["-e", name]
    return command + [image]


def connection(arguments):
    return ["--host", arguments.host, "--port", str(arguments.port), "--user", arguments.user]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def full_backup(arguments, environment, run=subprocess.run):
    environment = dict(environment, MYSQL_PWD=required(environment, "MYSQL_BACKUP_PASSWORD"))
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = arguments.dest / "full"
    target.mkdir(parents=True, exist_ok=True)
    name = "stove-" + stamp + ".sql"
    command = docker_client(arguments.image, arguments.network, arguments.dest, ["MYSQL_PWD"]) + [
        "mysqldump", *connection(arguments), "--single-transaction", "--source-data=2",
        "--routines", "--triggers", "--events", "--set-gtid-purged=OFF", "--hex-blob", "--no-tablespaces",
        "--result-file=/backup/full/" + name, "--databases", *arguments.schemas]
    run(command, env=environment, check=True)
    dump = target / name
    text = dump.read_text(encoding="utf-8", errors="replace")
    if not DUMP_COMPLETE.search(text[-4096:]):
        raise BackupError("The dump did not finish: " + str(dump))
    log_file, position = source_position(text)
    metadata = {"dump": name, "created_at": stamp, "schemas": list(arguments.schemas),
                "binlog_file": log_file, "binlog_position": position,
                "sha256": sha256(dump), "bytes": dump.stat().st_size}
    (target / (name + ".json")).write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata))
    return metadata


def stream_binlog(arguments, environment, run=subprocess.run, query=None):
    environment = dict(environment, MYSQL_PWD=required(environment, "MYSQL_BACKUP_PASSWORD"))
    target = arguments.dest / "binlog"
    target.mkdir(parents=True, exist_ok=True)
    existing = sorted(path.name for path in target.iterdir() if BINLOG_NAME.match(path.name))
    if existing:
        # Raw streaming rewrites a whole file, so resume from the newest, possibly partial, one.
        start = existing[-1]
    else:
        listing = (query or server_query)(arguments, environment, "SHOW BINARY LOGS")
        if not listing:
            raise BackupError("Binary logging is disabled on the server")
        start = listing[0].split("\t")[0]
    command = docker_client(arguments.image, arguments.network, arguments.dest, ["MYSQL_PWD"]) + [
        "mysqlbinlog", "--read-from-remote-server", *connection(arguments), "--raw", "--stop-never",
        "--result-file=/backup/binlog/", start]
    print("Streaming binary logs from " + start, flush=True)
    run(command, env=environment, check=True)


def server_query(arguments, environment, sql):
    command = docker_client(arguments.image, arguments.network, arguments.dest, ["MYSQL_PWD"]) + [
        "mysql", *connection(arguments), "--batch", "--skip-column-names", "--execute", sql]
    output = subprocess.run(command, env=environment, check=True, capture_output=True, text=True).stdout
    return [line for line in output.splitlines() if line.strip()]


def latest_metadata(directory):
    candidates = sorted((directory / "full").glob("stove-*.sql.json"))
    if not candidates:
        raise BackupError("No full backup metadata in " + str(directory / "full"))
    return json.loads(candidates[-1].read_text())


def restore(arguments, environment, run=subprocess.run):
    environment = dict(environment, MYSQL_PWD=required(environment, "MYSQL_RESTORE_PASSWORD"))
    metadata = latest_metadata(arguments.backup) if arguments.dump is None else json.loads(
        (arguments.backup / "full" / (arguments.dump + ".json")).read_text())
    dump = arguments.backup / "full" / metadata["dump"]
    if sha256(dump) != metadata["sha256"]:
        raise BackupError("The dump checksum does not match its metadata")
    binlogs = binlog_sequence([path.name for path in (arguments.backup / "binlog").iterdir()],
                              metadata["binlog_file"])
    client = docker_client(arguments.image, arguments.network, arguments.backup, ["MYSQL_PWD"], interactive=True)
    with dump.open("rb") as handle:
        run(client + ["mysql", *connection(arguments), "--binary-mode"], stdin=handle, env=environment, check=True)
    replay = ["mysqlbinlog", "--start-position=" + str(metadata["binlog_position"]),
              *["/backup/binlog/" + name for name in binlogs]]
    apply = ["mysql", *connection(arguments), "--binary-mode"]
    script = " ".join(replay) + " | " + " ".join(apply)
    # pipefail: a truncated or unreadable binary log must fail the restore, not just the reader.
    run(docker_client(arguments.image, arguments.network, arguments.backup, ["MYSQL_PWD"])
        + ["bash", "-o", "pipefail", "-ec", script], env=environment, check=True)
    result = {"dump": metadata["dump"], "binlog_start": [metadata["binlog_file"], metadata["binlog_position"]],
              "binlogs_applied": binlogs}
    print(json.dumps(result))
    return result


def mirror(source, target, watch):
    """Arguments for the mc entrypoint (the MinIO image's default entrypoint is the server)."""
    # Never pass --remove: the copy must keep every object a restored row may still reference.
    # mc needs a writable configuration directory for a non-root user.
    command = ["--config-dir", "/tmp/mc", "mirror", "--preserve", "--overwrite"]
    if watch:
        command.append("--watch")
    return command + [source, target]


def objects_backup(arguments, environment, run=subprocess.run):
    endpoint = arguments.endpoint.split("://", 1)
    access = required(environment, "STORAGE_BACKUP_ACCESS_KEY")
    secret = required(environment, "STORAGE_BACKUP_SECRET_KEY")
    environment = dict(environment, MC_HOST_source=endpoint[0] + "://" + access + ":" + secret + "@" + endpoint[1])
    (arguments.dest / "objects" / arguments.bucket).mkdir(parents=True, exist_ok=True)
    command = docker_client(arguments.image, arguments.network, arguments.dest, ["MC_HOST_source"],
                            entrypoint="mc") + mirror(
        "source/" + arguments.bucket, "/backup/objects/" + arguments.bucket, arguments.watch)
    run(command, env=environment, check=True)


def objects_restore(arguments, environment, run=subprocess.run):
    endpoint = arguments.endpoint.split("://", 1)
    access = required(environment, "STORAGE_RESTORE_ACCESS_KEY")
    secret = required(environment, "STORAGE_RESTORE_SECRET_KEY")
    environment = dict(environment, MC_HOST_target=endpoint[0] + "://" + access + ":" + secret + "@" + endpoint[1])
    command = docker_client(arguments.image, arguments.network, arguments.backup, ["MC_HOST_target"],
                            entrypoint="mc") + mirror(
        "/backup/objects/" + arguments.bucket, "target/" + arguments.bucket, False)
    run(command, env=environment, check=True)


def parser():
    root = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = root.add_subparsers(dest="command", required=True)

    def database(sub, directory_option):
        sub.add_argument(directory_option, type=Path, required=True)
        sub.add_argument("--host", default="127.0.0.1")
        sub.add_argument("--port", type=int, default=3306)
        sub.add_argument("--user", required=True)
        sub.add_argument("--network", default="host")
        sub.add_argument("--image", default=DEFAULT_MYSQL_IMAGE)

    full = commands.add_parser("full", help="Consistent dump with its binary log position")
    database(full, "--dest")
    full.add_argument("--schemas", nargs="+", default=list(SCHEMAS))
    stream = commands.add_parser("stream-binlog", help="Continuously copy binary logs off the server")
    database(stream, "--dest")
    back = commands.add_parser("restore", help="Load the dump and replay every streamed binary log")
    database(back, "--backup")
    back.add_argument("--dump", help="Dump file name; defaults to the newest full backup")

    def storage(sub, directory_option):
        sub.add_argument(directory_option, type=Path, required=True)
        sub.add_argument("--endpoint", required=True, help="For example http://minio:9000")
        sub.add_argument("--bucket", default="stove-builds")
        sub.add_argument("--network", default="host")
        sub.add_argument("--image", default=DEFAULT_MC_IMAGE)

    objects = commands.add_parser("objects-backup", help="Mirror build objects without removing copies")
    storage(objects, "--dest")
    objects.add_argument("--watch", action="store_true", help="Keep mirroring new objects")
    objects_back = commands.add_parser("objects-restore", help="Copy mirrored objects back to the bucket")
    storage(objects_back, "--backup")
    return root


def main(argv=None, environment=None):
    arguments = parser().parse_args(argv)
    environment = dict(os.environ if environment is None else environment)
    for option in ("dest", "backup"):
        if getattr(arguments, option, None) is not None:
            setattr(arguments, option, getattr(arguments, option).resolve())
    handlers = {"full": full_backup, "stream-binlog": stream_binlog, "restore": restore,
                "objects-backup": objects_backup, "objects-restore": objects_restore}
    try:
        handlers[arguments.command](arguments, environment)
    except (BackupError, subprocess.CalledProcessError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
