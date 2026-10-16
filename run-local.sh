#!/usr/bin/env bash
# Run KnowledgeHub locally WITHOUT Docker: Postgres(+pgvector) must be installed; Node 20+, Python 3.11+.
#   ./run-local.sh            -> Python backend (FastAPI, :8000)
#   BACKEND=java ./run-local.sh  -> Java backend (Spring Boot, :8081)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND="${BACKEND:-python}"
DB_NAME="${DB_NAME:-knowledgehub_py}"; DB_USER="${DB_USER:-kh}"; DB_PASSWORD="${DB_PASSWORD:-kh}"

# 1. Postgres + pgvector
if command -v pg_isready >/dev/null && ! pg_isready -q -h localhost; then
  sudo service postgresql start || true
fi
sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD'"
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER"
sudo -u postgres psql -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS vector" >/dev/null

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

# 2. mock LLM
(cd "$ROOT/mock-llm" && node server.js) & pids+=($!)

# 3. backend
if [ "$BACKEND" = "java" ]; then
  PORT=8081
  (cd "$ROOT/backend" && SERVER_PORT=$PORT DB_URL="jdbc:postgresql://localhost:5432/$DB_NAME" DB_USER=$DB_USER \
     DB_PASSWORD=$DB_PASSWORD mvn -q spring-boot:run) & pids+=($!)
else
  PORT=8000
  cd "$ROOT/backend-python"
  [ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
  DATABASE_URL="postgresql://$DB_USER:$DB_PASSWORD@localhost:5432/$DB_NAME" \
    .venv/bin/uvicorn app.main:app --port $PORT & pids+=($!)
  cd "$ROOT"
fi

# 4. frontend (proxy target configurable via BACKEND_URL)
cd "$ROOT/frontend"
[ -d node_modules ] || npm ci --no-audit --no-fund
BACKEND_URL="http://localhost:$PORT" npm run dev -- --port 5173 & pids+=($!)
echo "KnowledgeHub: http://localhost:5173  (backend :$PORT, mock-llm :4000). Ctrl+C to stop."
wait
