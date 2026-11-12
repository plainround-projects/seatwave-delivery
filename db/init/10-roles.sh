#!/usr/bin/env bash
# Copied from Module 13's project (seatwave-transactions). Runs once inside the postgres container, as the superuser, when the data volume is new.
# Creates the two roles SeatWave uses, so nothing in SeatWave ever logs in as the superuser:
#   seatwave_owner  owns the schemas and runs migrations (DDL)
#   seatwave_app    reads and writes rows; R__app_grants.sql decides which
# Passwords come from the Compose secrets, as psql variables, never pasted into SQL text.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(< /run/secrets/owner_password)" \
  -v app_password="$(< /run/secrets/app_password)" <<'SQL'
CREATE ROLE seatwave_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE seatwave_app LOGIN PASSWORD :'app_password';
-- The owner may create schemas in this database (Flyway creates seatwave).
GRANT CONNECT, CREATE ON DATABASE seatwave TO seatwave_owner;
GRANT CONNECT ON DATABASE seatwave TO seatwave_app;
-- Nobody else may connect: PUBLIC loses the default right.
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
SQL
