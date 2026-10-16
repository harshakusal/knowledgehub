@echo off
REM Starts mock LLM, Python backend and frontend in 3 windows (Windows, no Docker, no database needed).
REM Storage: if DATABASE_URL is NOT set, the backend runs with STORAGE=memory (data kept in RAM, lost on restart).
REM          To use Postgres instead: set DATABASE_URL=postgresql://user:pass@host:5432/db  before running.
REM Requires: Node 20+, Python 3.12+ on PATH.
if "%DATABASE_URL%"=="" (
  echo DATABASE_URL not set - using in-memory storage.
  set STORAGE=memory
)
start "mock-llm" cmd /k "cd /d "%~dp0mock-llm" && node server.js"
start "backend" cmd /k "cd /d "%~dp0backend-python" && (if not exist .venv python -m venv .venv) && .venv\Scripts\python -m pip install -r requirements.txt && .venv\Scripts\python run.py"
start "frontend" cmd /k "cd /d "%~dp0frontend" && npm install && npm run dev -- --port 5173"
echo Open http://localhost:5173 once all three windows are ready.
