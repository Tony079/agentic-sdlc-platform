#!/usr/bin/env bash
# Database bootstrap for Docker Compose and CI (run by the PostgreSQL superuser).
# Creates roles, sets their passwords from the environment, and prepares the application database.
# Required environment: POSTGRES_USER, SDLC_DB_NAME, SDLC_DB_MIGRATOR_PASSWORD, SDLC_DB_APP_PASSWORD,
# SDLC_DB_WORKER_PASSWORD, SDLC_DB_PROVISIONER_PASSWORD.
set -euo pipefail
# SQL files live next to this script unless SDLC_BOOTSTRAP_DIR says otherwise (Docker entrypoint case).
here="${SDLC_BOOTSTRAP_DIR:-$(cd "$(dirname "$0")" && pwd)}"
: "${SDLC_DB_NAME:?}" "${SDLC_DB_MIGRATOR_PASSWORD:?}" "${SDLC_DB_APP_PASSWORD:?}" "${SDLC_DB_WORKER_PASSWORD:?}" "${SDLC_DB_PROVISIONER_PASSWORD:?}"

psql_su() { psql -v ON_ERROR_STOP=1 -X -q --username "${POSTGRES_USER:-postgres}" "$@"; }

psql_su --dbname postgres -f "$here/00-roles.sql"
psql_su --dbname postgres \
  -v migrator_pw="$SDLC_DB_MIGRATOR_PASSWORD" -v app_pw="$SDLC_DB_APP_PASSWORD" \
  -v worker_pw="$SDLC_DB_WORKER_PASSWORD" -v provisioner_pw="$SDLC_DB_PROVISIONER_PASSWORD" <<'SQL'
ALTER ROLE sdlc_migrator PASSWORD :'migrator_pw';
ALTER ROLE sdlc_app PASSWORD :'app_pw';
ALTER ROLE sdlc_worker PASSWORD :'worker_pw';
ALTER ROLE sdlc_provisioner PASSWORD :'provisioner_pw';
SQL

if ! psql_su --dbname postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$SDLC_DB_NAME'" | grep -q 1; then
  psql_su --dbname postgres -c "CREATE DATABASE \"$SDLC_DB_NAME\""
fi
psql_su --dbname "$SDLC_DB_NAME" -f "$here/01-database.sql"
echo "sdlc bootstrap: OK"
