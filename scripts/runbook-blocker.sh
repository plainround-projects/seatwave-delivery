#!/usr/bin/env bash
# Section 10's runbook, step 2, on the lab database: a long transaction holds a weak lock on
# bookings, a migration queues behind it for a strong one, and the runbook's query finds the blocker.
# The migration gives up after its lock_timeout of 2 s, as the module's migrations do. Needs the
# stack up (scripts/stack.sh up --run --yes) with Module 13's schema migrated; changes no data.
set -euo pipefail
cd "$(dirname "$0")/.."
psql_in() {  # runs psql in the lab container as the owner role, password from the container's secret
  docker compose -p seatwave-delivery exec -T postgres sh -c \
    'PGPASSWORD="$(cat /run/secrets/owner_password)" psql -X -q -h 127.0.0.1 -U seatwave_owner -d seatwave -v ON_ERROR_STOP=1 "$@"' psql "$@"
}
# 1. An analytics query that forgot to end its transaction: holds ACCESS SHARE for 8 s.
psql_in -c "BEGIN; SELECT count(*) AS analytics_rows FROM seatwave.bookings; SELECT pg_sleep(8); COMMIT;" > /dev/null &
analytics=$!
sleep 1
# 2. A migration wanting ACCESS EXCLUSIVE queues behind it, for at most its lock_timeout.
psql_in -c "SET lock_timeout = '2s'; ALTER TABLE seatwave.bookings ADD COLUMN runbook_probe INTEGER;" > /dev/null 2> build/runbook-migration.err &
migration=$!
sleep 1
# 3. The runbook's query, while the migration waits.
echo "\$ psql \"\$DATABASE_URL\" -c \"SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event_type, state, now() - xact_start AS age, left(query, 60) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'\""
psql_in -c "SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event_type, state, date_trunc('second', now() - xact_start) AS age, left(query, 60) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"
status=0
wait "$migration" || status=$?
echo "the migration, after its lock_timeout: exit status $status, $(sed 's/^psql:[^E]*//' build/runbook-migration.err | head -1)"
wait "$analytics"
rm -f build/runbook-migration.err
