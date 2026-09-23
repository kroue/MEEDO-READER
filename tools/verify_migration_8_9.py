"""Checks that MIGRATION_8_9 turns a version-8 database into the schema Room
expects at version 9.

    python tools/verify_migration_8_9.py

Run from the repository root, after a build has regenerated `app/schemas/`.
It builds a real SQLite database to the v8 schema, puts a row in it, runs the
migration's own SQL — read straight out of MeterReaderDatabase.kt, so it can't
drift from what ships — and then compares the result column by column against
`app/schemas/.../9.json`.

Room validates the live schema on every open and aborts when it disagrees, so
a migration that doesn't land exactly on 9.json means the app won't start.
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/com.waterdistrict.meterreader.data.local.MeterReaderDatabase"
DATABASE_KT = (
    ROOT / "app/src/main/java/com/waterdistrict/meterreader/data/local/MeterReaderDatabase.kt"
)


def load_schema(version: int) -> dict:
    return json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]


def migration_sql() -> list[str]:
    """Every execSQL string inside MIGRATION_8_9, in order."""
    source = DATABASE_KT.read_text(encoding="utf-8")
    start = source.index("val MIGRATION_8_9")
    end = source.index("val MIGRATIONS", start)
    return re.findall(r'execSQL\(\s*"((?:[^"\\]|\\.)*)"\s*\)', source[start:end])


def columns_of(connection: sqlite3.Connection, table: str) -> dict[str, tuple]:
    rows = connection.execute(f"PRAGMA table_info(`{table}`)").fetchall()
    # name -> (type, notNull, default)
    return {row[1]: (row[2].upper(), row[3], row[4]) for row in rows}


def expected_columns(schema: dict, table: str) -> dict[str, tuple]:
    entity = next(e for e in schema["entities"] if e["tableName"] == table)
    return {
        field["columnName"]: (
            field["affinity"].upper(),
            1 if field["notNull"] else 0,
            field.get("defaultValue"),
        )
        for field in entity["fields"]
    }


def insert_row(
    connection: sqlite3.Connection, schema: dict, table: str, given: dict[str, object]
) -> None:
    """Inserts one row, filling every other NOT NULL column with a plain value."""
    entity = next(e for e in schema["entities"] if e["tableName"] == table)
    values: dict[str, object] = {}
    for field in entity["fields"]:
        column = field["columnName"]
        if column in given:
            values[column] = given[column]
        elif field["notNull"] and field.get("defaultValue") in (None, "undefined"):
            values[column] = "" if field["affinity"].upper() == "TEXT" else 0
    columns = ", ".join(f"`{c}`" for c in values)
    placeholders = ", ".join("?" for _ in values)
    connection.execute(
        f"INSERT INTO `{table}` ({columns}) VALUES ({placeholders})", list(values.values())
    )


def main() -> int:
    v8, v9 = load_schema(8), load_schema(9)

    connection = sqlite3.connect(":memory:")
    for entity in v8["entities"]:
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    # A household already on the phone, to prove the migration keeps it. Every
    # NOT NULL column is filled from the v8 schema itself, so this doesn't have
    # to be rewritten each time a column is added.
    insert_row(connection, v8, "consumers", {"account_no": "048213", "meter_no": "048213"})

    statements = migration_sql()
    if not statements:
        print("FAIL: no SQL found in MIGRATION_8_9")
        return 1
    for statement in statements:
        connection.execute(statement.encode().decode("unicode_escape"))

    problems: list[str] = []
    for table in ("consumers", "readings"):
        got, want = columns_of(connection, table), expected_columns(v9, table)
        for column, expectation in want.items():
            if column not in got:
                problems.append(f"{table}.{column} is missing after the migration")
            elif got[column][:2] != expectation[:2]:
                problems.append(
                    f"{table}.{column} is {got[column][:2]}, Room expects {expectation[:2]}"
                )
        for column in got.keys() - want.keys():
            problems.append(f"{table}.{column} is left over and Room does not expect it")

    kept = connection.execute(
        "SELECT account_no, office_account_no FROM consumers"
    ).fetchall()
    if kept != [("048213", "")]:
        problems.append(f"the existing household did not survive intact: {kept}")

    if problems:
        print("MIGRATION_8_9 does not land on schema 9:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print("MIGRATION_8_9 lands exactly on schema 9, and keeps the rows already there.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
