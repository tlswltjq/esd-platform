import argparse
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import backup


DUMP = """-- MySQL dump 10.13
-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000003', SOURCE_LOG_POS=4521;
CREATE DATABASE stove_order;
-- Dump completed on 2026-11-06  9:00:00
"""
SECRET = "never-in-argv-7c1e"


def arguments(directory, **values):
    base = dict(host="mysql", port=3306, user="backup", network="isolated", image="mysql:8.0",
                schemas=list(backup.SCHEMAS), dump=None, endpoint="http://minio:9000",
                bucket="stove-builds", watch=False)
    base.update(values)
    return argparse.Namespace(**base)


class Recorder:
    def __init__(self, on_run=None):
        self.calls = []
        self.on_run = on_run

    def __call__(self, command, **options):
        self.calls.append((command, options))
        if self.on_run:
            self.on_run(command)


class SourcePositionTest(unittest.TestCase):
    def test_reads_the_position_written_by_source_data(self):
        self.assertEqual(backup.source_position(DUMP), ("binlog.000003", 4521))

    def test_reads_the_legacy_master_form(self):
        legacy = "-- CHANGE MASTER TO MASTER_LOG_FILE='binlog.000010', MASTER_LOG_POS=157;\n"
        self.assertEqual(backup.source_position(legacy), ("binlog.000010", 157))

    def test_rejects_a_dump_without_a_position(self):
        with self.assertRaises(backup.BackupError):
            backup.source_position("CREATE DATABASE stove_order;\n")


class BinlogSequenceTest(unittest.TestCase):
    def test_selects_consecutive_logs_from_the_dump_file(self):
        names = ["binlog.000004", "binlog.index", "binlog.000002", "binlog.000003", "other.000005"]
        self.assertEqual(backup.binlog_sequence(names, "binlog.000003"), ["binlog.000003", "binlog.000004"])

    def test_rejects_a_missing_start_log(self):
        with self.assertRaises(backup.BackupError):
            backup.binlog_sequence(["binlog.000004"], "binlog.000003")

    def test_rejects_a_gap_after_the_start_log(self):
        with self.assertRaisesRegex(backup.BackupError, "gap"):
            backup.binlog_sequence(["binlog.000003", "binlog.000005"], "binlog.000003")


class FullBackupTest(unittest.TestCase):
    def test_dump_records_position_and_checksum_without_exposing_the_password(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)

            def write_dump(command):
                result = next(part for part in command if part.startswith("--result-file="))
                (target / "full" / Path(result.split("=", 1)[1]).name).write_text(DUMP)

            run = Recorder(write_dump)
            metadata = backup.full_backup(arguments(target, dest=target),
                                          {"MYSQL_BACKUP_PASSWORD": SECRET}, run=run)

            command, options = run.calls[0]
            self.assertNotIn(SECRET, " ".join(command))
            self.assertEqual(options["env"]["MYSQL_PWD"], SECRET)
            self.assertIn("MYSQL_PWD", command)
            self.assertIn("--single-transaction", command)
            self.assertIn("--source-data=2", command)
            self.assertEqual(command[-len(backup.SCHEMAS):], list(backup.SCHEMAS))
            self.assertEqual((metadata["binlog_file"], metadata["binlog_position"]), ("binlog.000003", 4521))
            dump = target / "full" / metadata["dump"]
            self.assertEqual(metadata["sha256"], hashlib.sha256(dump.read_bytes()).hexdigest())
            stored = json.loads((target / "full" / (metadata["dump"] + ".json")).read_text())
            self.assertEqual(stored, metadata)

    def test_an_unfinished_dump_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)

            def write_partial(command):
                result = next(part for part in command if part.startswith("--result-file="))
                (target / "full" / Path(result.split("=", 1)[1]).name).write_text(DUMP.rsplit("--", 1)[0])

            with self.assertRaisesRegex(backup.BackupError, "did not finish"):
                backup.full_backup(arguments(target, dest=target), {"MYSQL_BACKUP_PASSWORD": SECRET},
                                   run=Recorder(write_partial))

    def test_missing_password_fails_before_running(self):
        run = Recorder()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(backup.BackupError):
                backup.full_backup(arguments(Path(directory), dest=Path(directory)), {}, run=run)
        self.assertEqual(run.calls, [])


class RestoreTest(unittest.TestCase):
    def prepare(self, target, binlogs=("binlog.000003", "binlog.000004")):
        (target / "full").mkdir()
        (target / "binlog").mkdir()
        dump = target / "full" / "stove-20261106T000000Z.sql"
        dump.write_text(DUMP)
        metadata = {"dump": dump.name, "binlog_file": "binlog.000003", "binlog_position": 4521,
                    "sha256": hashlib.sha256(dump.read_bytes()).hexdigest()}
        (target / "full" / (dump.name + ".json")).write_text(json.dumps(metadata))
        for name in binlogs:
            (target / "binlog" / name).write_bytes(b"\xfebin")
        return metadata

    def test_loads_the_dump_then_replays_every_log_from_the_recorded_position(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            self.prepare(target)
            run = Recorder()
            result = backup.restore(arguments(target, backup=target), {"MYSQL_RESTORE_PASSWORD": SECRET}, run=run)

            load, replay = run.calls
            self.assertIn("mysql", load[0])
            self.assertIn("stdin", load[1])
            script = replay[0][-1]
            self.assertEqual(replay[0][-5:-1], ["bash", "-o", "pipefail", "-ec"])
            self.assertIn("--start-position=4521 /backup/binlog/binlog.000003 /backup/binlog/binlog.000004", script)
            for command, options in run.calls:
                self.assertNotIn(SECRET, " ".join(command))
                self.assertEqual(options["env"]["MYSQL_PWD"], SECRET)
            self.assertEqual(result["binlogs_applied"], ["binlog.000003", "binlog.000004"])

    def test_a_modified_dump_is_rejected_before_any_change(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            self.prepare(target)
            (target / "full" / "stove-20261106T000000Z.sql").write_text(DUMP + "DROP DATABASE stove_order;\n")
            run = Recorder()
            with self.assertRaisesRegex(backup.BackupError, "checksum"):
                backup.restore(arguments(target, backup=target), {"MYSQL_RESTORE_PASSWORD": SECRET}, run=run)
            self.assertEqual(run.calls, [])

    def test_a_binlog_gap_is_rejected_before_any_change(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            self.prepare(target, binlogs=("binlog.000003", "binlog.000005"))
            run = Recorder()
            with self.assertRaisesRegex(backup.BackupError, "gap"):
                backup.restore(arguments(target, backup=target), {"MYSQL_RESTORE_PASSWORD": SECRET}, run=run)
            self.assertEqual(run.calls, [])


class ObjectMirrorTest(unittest.TestCase):
    def test_backup_mirror_never_removes_copies_and_keeps_keys_out_of_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            run = Recorder()
            backup.objects_backup(arguments(target, dest=target, watch=True),
                                  {"STORAGE_BACKUP_ACCESS_KEY": "reader", "STORAGE_BACKUP_SECRET_KEY": SECRET}, run=run)
            command, options = run.calls[0]
            self.assertEqual(command[command.index("--entrypoint") + 1], "mc")
            self.assertNotIn("--remove", command)
            self.assertIn("--watch", command)
            self.assertNotIn(SECRET, " ".join(command))
            self.assertEqual(options["env"]["MC_HOST_source"], "http://reader:" + SECRET + "@minio:9000")
            self.assertEqual(command[-2:], ["source/stove-builds", "/backup/objects/stove-builds"])

    def test_restore_copies_the_mirror_back_into_the_bucket(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            run = Recorder()
            backup.objects_restore(arguments(target, backup=target),
                                   {"STORAGE_RESTORE_ACCESS_KEY": "writer", "STORAGE_RESTORE_SECRET_KEY": SECRET}, run=run)
            command, _ = run.calls[0]
            self.assertNotIn("--remove", command)
            self.assertNotIn("--watch", command)
            self.assertEqual(command[-2:], ["/backup/objects/stove-builds", "target/stove-builds"])


class DockerHubStoveImageTest(unittest.TestCase):
    """Decision 135: Docker Hub's `stove` namespace belongs to someone else, so its images never run here."""

    REFUSED = ["stove/mysql-client:8.0-local", "docker.io/stove/minio:tag", "Docker.IO/stove/minio:tag",
               "index.docker.io/stove/minio@sha256:" + "0" * 64]

    def test_database_commands_refuse_before_running(self):
        for image in self.REFUSED:
            with self.subTest(image=image):
                run = Recorder()
                with tempfile.TemporaryDirectory() as directory:
                    with self.assertRaisesRegex(backup.BackupError, "decision 135"):
                        backup.full_backup(arguments(Path(directory), dest=Path(directory), image=image),
                                           {"MYSQL_BACKUP_PASSWORD": SECRET}, run=run)
                self.assertEqual(run.calls, [])

    def test_object_commands_refuse_before_running(self):
        run = Recorder()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(backup.BackupError, "decision 135"):
                backup.objects_backup(arguments(Path(directory), dest=Path(directory), image="stove/minio:tag"),
                                      {"STORAGE_BACKUP_ACCESS_KEY": "reader", "STORAGE_BACKUP_SECRET_KEY": SECRET},
                                      run=run)
        self.assertEqual(run.calls, [])

    def test_local_and_other_registries_run(self):
        for image in ["localhost/stove/mysql-client:8.0-local", "ghcr.io/stove/minio:tag",
                      "localhost:5000/stove/minio:tag", "mysql:8.0", "stove-team/minio:tag"]:
            with self.subTest(image=image):
                self.assertEqual(backup.docker_client(image, "host", Path("/b"), [])[-1], image)


if __name__ == "__main__":
    unittest.main()
