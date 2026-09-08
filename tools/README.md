# tools

## verify_migration.py

Checks that `MIGRATION_7_8` actually turns a version-7 database into the schema
Room expects at version 8.

```bash
python tools/verify_migration.py
```

Run it from the repository root, after a build has regenerated
`app/schemas/`. It builds a real SQLite database to the v7 schema, seeds it with
the awkward cases (readings spanning two cycles, an upload abandoned mid-sync,
duplicate readings in one cycle, a consumer with no readings), executes the
migration's own SQL — read straight out of `MeterReaderDatabase.kt`, so it can't
drift from what ships — and then compares the result column by column and index
by index against `app/schemas/…/8.json`.

This matters because Room validates the live schema on every open and aborts
the app with *"Migration didn't properly handle &lt;table&gt;"* on any mismatch.
Since the app no longer falls back to a destructive migration, a broken
migration is a crash on launch rather than silent data loss — better, but still
worth catching here first.

Requires only Python 3 (`sqlite3` is in the standard library); no emulator.
