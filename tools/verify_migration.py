"""
Executes MIGRATION_7_8's SQL against a real SQLite database built to the v7
schema, then checks the result against the schema Room expects at v8.

Room validates the live table shape on every open and aborts the app with
"Migration didn't properly handle <table>" on any mismatch, so this catches a
broken migration before it reaches a reader's phone.

The migration statements are read out of the Kotlin source rather than
duplicated here, so this can't silently drift from what actually ships.
"""
import io, json, re, sqlite3, sys, tempfile, os

KOTLIN = 'app/src/main/java/com/waterdistrict/meterreader/data/local/MeterReaderDatabase.kt'
SCHEMA_V8 = 'app/schemas/com.waterdistrict.meterreader.data.local.MeterReaderDatabase/8.json'

# ── The v7 schema, as it shipped ─────────────────────────────────────────────
V7_DDL = [
    """CREATE TABLE `consumers` (
        `account_no` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL,
        `meter_no` TEXT NOT NULL, `prev_reading` REAL NOT NULL, `route_id` TEXT NOT NULL,
        `firebase_id` TEXT NOT NULL, `prev_reading_month` TEXT NOT NULL,
        `classification` TEXT NOT NULL, `overdue_balance` REAL NOT NULL,
        `overdue_billing_date_millis` INTEGER,
        `extension_fee_already_charged` INTEGER NOT NULL,
        `already_billed_this_month` INTEGER NOT NULL,
        `billed_reading_this_month` REAL NOT NULL, `billed_amount_this_month` REAL NOT NULL,
        `sync_status` TEXT NOT NULL, `downloaded_at` INTEGER NOT NULL,
        PRIMARY KEY(`account_no`))""",
    """CREATE TABLE `readings` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_no` TEXT NOT NULL,
        `prev_reading` REAL NOT NULL, `current_reading` REAL NOT NULL,
        `consumption` REAL NOT NULL, `minimum_charge` REAL NOT NULL,
        `commodity_charge` REAL NOT NULL, `overdue_balance` REAL NOT NULL,
        `overdue_surcharge` REAL NOT NULL, `extension_fee` REAL NOT NULL,
        `total_amount_due` REAL NOT NULL, `due_date_millis` INTEGER NOT NULL,
        `projected_overdue_total` REAL NOT NULL, `reading_date` INTEGER NOT NULL,
        `sync_status` TEXT NOT NULL, `server_id` TEXT, `or_number` TEXT NOT NULL,
        `read_by_user_id` TEXT NOT NULL, `remarks` TEXT NOT NULL,
        FOREIGN KEY(`account_no`) REFERENCES `consumers`(`account_no`)
          ON UPDATE NO ACTION ON DELETE CASCADE )""",
    "CREATE INDEX `index_readings_account_no` ON `readings` (`account_no`)",
    "CREATE INDEX `index_readings_sync_status` ON `readings` (`sync_status`)",
]

AUG_2026 = 1_786_723_200_000   # 15 Aug 2026, epoch millis
SEP_2026 = 1_789_401_600_000   # 15 Sep 2026


def extract_migration_sql(source: str):
    """Pulls every execSQL(...) argument out of the MIGRATION_7_8 block, in order."""
    start = source.index('val MIGRATION_7_8')
    end = source.index('val MIGRATIONS', start)
    block = source[start:end]

    statements = []
    for m in re.finditer(r'db\.execSQL\(\s*(.*?)\s*\)\s*(?=\n\s*(?:db\.execSQL|//|\}))',
                         block, re.S):
        arg = m.group(1).strip()
        if arg.startswith('"""'):
            body = arg[3:]
            body = body[:body.index('"""')]
            statements.append(body.strip())
        else:
            # One or more adjacent "..." literals, possibly joined with +
            parts = re.findall(r'"((?:[^"\\]|\\.)*)"', arg)
            statements.append("".join(parts).replace('\\"', '"').strip())
    return [s for s in statements if s]


def main():
    source = io.open(KOTLIN, encoding='utf-8').read()
    statements = extract_migration_sql(source)
    print("extracted %d migration statements" % len(statements))
    if len(statements) < 8:
        print("FAIL: suspiciously few statements extracted — parser is wrong", file=sys.stderr)
        return 1

    path = os.path.join(tempfile.mkdtemp(), 'v7.db')
    con = sqlite3.connect(path)
    con.execute("PRAGMA foreign_keys = OFF")   # Room does the same during migration
    for ddl in V7_DDL:
        con.execute(ddl)

    # Seed data spanning two cycles, plus rows in each awkward state.
    con.execute(
        "INSERT INTO consumers VALUES "
        "('M-001','Juan Dela Cruz','Purok 1','M-001',100.0,'BO-OT','fb1','JUL 2026',"
        "'RESIDENTIAL',520.0,1750000000000,1,1,120.0,213.0,'PENDING',?)", (AUG_2026,))
    con.execute(
        "INSERT INTO consumers VALUES "
        "('M-002','Maria Santos','Purok 2','M-002',50.0,'BO-OT','fb2','JUL 2026',"
        "'COMMERCIAL A',0.0,NULL,0,0,0.0,0.0,'PENDING',?)", (AUG_2026,))
    # A consumer with no readings at all — its billing_month must still be filled.
    con.execute(
        "INSERT INTO consumers VALUES "
        "('M-003','Pedro Reyes','Purok 3','M-003',10.0,'CG','fb3','','GOVERNMENT',"
        "0.0,NULL,0,0,0.0,0.0,'PENDING',?)", (AUG_2026,))

    def reading(acct, date, status, orno):
        con.execute(
            "INSERT INTO readings (account_no,prev_reading,current_reading,consumption,"
            "minimum_charge,commodity_charge,overdue_balance,overdue_surcharge,extension_fee,"
            "total_amount_due,due_date_millis,projected_overdue_total,reading_date,sync_status,"
            "server_id,or_number,read_by_user_id,remarks) VALUES "
            "(?,100.0,120.0,20.0,100.0,108.0,0.0,0.0,0.0,208.0,0,214.24,?,?,NULL,?,'reader','')",
            (acct, date, status, orno))

    reading('M-001', AUG_2026, 'SYNCED', 'OR-2026-000001')
    reading('M-001', SEP_2026, 'PENDING', '')
    reading('M-002', AUG_2026, 'SYNCING', '')          # abandoned mid-upload
    # Two readings in the same cycle for one account — the unique index must not
    # fail to build because of legacy duplicates.
    reading('M-002', SEP_2026, 'PENDING', '')
    reading('M-002', SEP_2026 + 1000, 'PENDING', '')
    con.commit()

    # ── Run the migration ────────────────────────────────────────────────────
    for i, stmt in enumerate(statements, 1):
        try:
            con.execute(stmt)
        except Exception as exc:
            print("FAIL: statement %d raised %s" % (i, exc), file=sys.stderr)
            print(stmt[:400], file=sys.stderr)
            return 1
    con.commit()
    print("all statements executed cleanly")

    # ── Compare the result against Room's expected v8 schema ─────────────────
    expected = json.load(io.open(SCHEMA_V8, encoding='utf-8'))['database']['entities']
    failures = []

    for entity in expected:
        table = entity['tableName']
        actual = {
            r[1]: {'type': r[2], 'notnull': bool(r[3])}
            for r in con.execute("PRAGMA table_info(`%s`)" % table)
        }
        wanted = {
            f['columnName']: {'type': f['affinity'], 'notnull': bool(f['notNull'])}
            for f in entity['fields']
        }
        for col, spec in wanted.items():
            if col not in actual:
                failures.append("%s: missing column %s" % (table, col))
            elif actual[col]['type'] != spec['type']:
                failures.append("%s.%s: type %s, expected %s"
                                % (table, col, actual[col]['type'], spec['type']))
            elif actual[col]['notnull'] != spec['notnull']:
                failures.append("%s.%s: notNull %s, expected %s"
                                % (table, col, actual[col]['notnull'], spec['notnull']))
        for col in actual:
            if col not in wanted:
                failures.append("%s: leftover column %s not in the entity "
                                "(Room aborts on this)" % (table, col))

        wanted_idx = {i['name']: (i['unique'], i['columnNames']) for i in entity.get('indices', [])}
        actual_idx = {}
        for row in con.execute("PRAGMA index_list(`%s`)" % table):
            name, unique = row[1], bool(row[2])
            if name.startswith('sqlite_autoindex'):
                continue
            cols = [r[2] for r in con.execute("PRAGMA index_info(`%s`)" % name)]
            actual_idx[name] = (unique, cols)
        for name, spec in wanted_idx.items():
            if name not in actual_idx:
                failures.append("%s: missing index %s" % (table, name))
            elif actual_idx[name] != spec:
                failures.append("%s: index %s is %r, expected %r"
                                % (table, name, actual_idx[name], spec))

    # ── Data-level expectations ──────────────────────────────────────────────
    checks = [
        ("every reading has a billing month",
         "SELECT COUNT(*) FROM readings WHERE billing_month = ''", 0),
        ("every consumer has a billing month",
         "SELECT COUNT(*) FROM consumers WHERE billing_month = ''", 0),
        ("Aug reading tagged AUG 2026",
         "SELECT COUNT(*) FROM readings WHERE account_no='M-001' AND billing_month='AUG 2026'", 1),
        ("Sep reading tagged SEP 2026",
         "SELECT COUNT(*) FROM readings WHERE account_no='M-001' AND billing_month='SEP 2026'", 1),
        ("duplicate same-cycle readings collapsed to one",
         "SELECT COUNT(*) FROM readings WHERE account_no='M-002' AND billing_month='SEP 2026'", 1),
        ("no reading left stuck in SYNCING",
         "SELECT COUNT(*) FROM readings WHERE sync_status='SYNCING'", 0),
        ("interrupted upload requeued as PENDING",
         "SELECT COUNT(*) FROM readings WHERE account_no='M-002' AND billing_month='AUG 2026' "
         "AND sync_status='PENDING'", 1),
        ("delinquency date carried over from the old column",
         "SELECT COUNT(*) FROM consumers WHERE account_no='M-001' "
         "AND delinquent_since_millis=1750000000000", 1),
        ("consumer rows preserved",
         "SELECT COUNT(*) FROM consumers", 3),
        ("readings not cascaded away when consumers was rebuilt",
         "SELECT COUNT(*) FROM readings", 4),
        ("credit balance defaults to zero",
         "SELECT COUNT(*) FROM consumers WHERE credit_balance = 0.0", 3),
    ]
    for label, sql, want in checks:
        got = con.execute(sql).fetchone()[0]
        if got != want:
            failures.append("data: %s — got %s, expected %s" % (label, got, want))

    # The unique index must actually be enforced from here on.
    try:
        con.execute("INSERT INTO readings (account_no,prev_reading,current_reading,consumption,"
                    "minimum_charge,commodity_charge,overdue_balance,overdue_surcharge,"
                    "extension_fee,credit_applied,total_amount_due,due_date_millis,"
                    "projected_overdue_total,reading_date,billing_month,sync_status,or_number,"
                    "read_by_user_id,remarks) VALUES ('M-001',0,0,0,0,0,0,0,0,0,0,0,0,?, "
                    "'AUG 2026','PENDING','','r','')", (AUG_2026,))
        failures.append("data: unique index does not prevent a second reading in the same cycle")
    except sqlite3.IntegrityError:
        pass

    con.close()

    if failures:
        print("\nFAILURES:", file=sys.stderr)
        for f in failures:
            print("  -", f, file=sys.stderr)
        return 1

    print("migration produces the exact schema Room expects at v8")
    print("all %d data expectations hold" % len(checks))
    return 0


if __name__ == '__main__':
    sys.exit(main())
