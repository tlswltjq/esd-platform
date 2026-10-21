#!/usr/bin/env python3
"""Offline copy of V11 supplier decisions. Stop embedded PG writes before running.

Passwords are read from SOURCE_MYSQL_PASSWORD and TARGET_MYSQL_PASSWORD.
The source is read only; existing target rows must match and fees remain unknown.
"""
import argparse
import json
import os
import re
import subprocess


def identifier(value):
    if not re.fullmatch(r"[A-Za-z0-9_]+", value):
        raise argparse.ArgumentTypeError("Invalid SQL identifier")
    return value


def sql(container, database, user, password, query):
    result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=" + password,
        container, "mysql", "--default-character-set=utf8mb4", "-u" + user, "-N", "-r", database],
        input=query, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Migration SQL failed; no credentials are included in this error")
    return result.stdout.strip()


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "CONVERT(X'" + value.encode().hex() + "' USING utf8mb4)"


def migrate(args):
    source_password = os.environ["SOURCE_MYSQL_PASSWORD"]
    target_password = os.environ["TARGET_MYSQL_PASSWORD"]
    def read(query):
        return sql(args.source_container, args.source_db, args.source_user, source_password, query)
    def write(query):
        return sql(args.target_container, args.target_db, args.target_user, target_password, query)
    projection = """JSON_OBJECT('payment_id',payment_id,'order_no',order_no,'amount',amount,'currency',currency,
        'provider_transaction_id',TRANSACTION_COLUMN,'provider_occurred_at',DATE_FORMAT(TIME_COLUMN,'%Y-%m-%d %H:%i:%s.%f'),
        'decision',decision,'reason_code',reason_code,'reason',reason,
        'pg_fee_policy_id',FEE_POLICY,'pg_fee_rate_bps',FEE_RATE,'pg_fee_amount',FEE_AMOUNT)"""
    source_projection = projection.replace("TRANSACTION_COLUMN", "transaction_id").replace("TIME_COLUMN", "occurred_at")
    source_projection = source_projection.replace("FEE_POLICY", "NULL").replace("FEE_RATE", "NULL").replace("FEE_AMOUNT", "NULL")
    target_projection = projection.replace("TRANSACTION_COLUMN", "provider_transaction_id").replace("TIME_COLUMN", "provider_occurred_at")
    target_projection = target_projection.replace("FEE_POLICY", "pg_fee_policy_id").replace("FEE_RATE", "pg_fee_rate_bps").replace("FEE_AMOUNT", "pg_fee_amount")
    source_text = read("SELECT " + source_projection + " FROM simulated_payment_charge ORDER BY payment_id;")
    rows = [json.loads(line) for line in source_text.splitlines() if line]
    existing_text = write("SELECT " + target_projection + " FROM pg_charge;")
    existing = {row["payment_id"]: row for row in (json.loads(line) for line in existing_text.splitlines() if line)}
    commands = ["START TRANSACTION;"]
    inserted = 0
    columns = ["payment_id", "order_no", "amount", "currency", "provider_transaction_id", "provider_occurred_at",
        "decision", "reason_code", "reason", "pg_fee_policy_id", "pg_fee_rate_bps", "pg_fee_amount"]
    for row in rows:
        prior = existing.get(row["payment_id"])
        if prior is not None:
            if prior != row:
                raise RuntimeError("Existing target decision conflicts with source")
            continue
        commands.append("INSERT INTO pg_charge(" + ",".join(columns) + ") VALUES(" +
            ",".join(literal(row[column]) for column in columns) + ");")
        inserted += 1
    commands.append("COMMIT;")
    write("\n".join(commands))
    verified_text = write("SELECT " + target_projection + " FROM pg_charge;")
    verified = {row["payment_id"]: row for row in (json.loads(line) for line in verified_text.splitlines() if line)}
    if any(verified.get(row["payment_id"]) != row for row in rows):
        raise RuntimeError("Target verification failed")
    if read("SELECT " + source_projection + " FROM simulated_payment_charge ORDER BY payment_id;") != source_text:
        raise RuntimeError("Source changed during migration; keep workers stopped and investigate")
    print(json.dumps({"sourceCount": len(rows), "inserted": inserted, "unchanged": len(rows)-inserted, "verified": len(rows)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-container", required=True)
    parser.add_argument("--target-container", required=True)
    parser.add_argument("--source-db", type=identifier, default="stove_payment")
    parser.add_argument("--target-db", type=identifier, default="mock_pg")
    parser.add_argument("--source-user", type=identifier, default="stove")
    parser.add_argument("--target-user", type=identifier, default="mock_pg")
    migrate(parser.parse_args())
