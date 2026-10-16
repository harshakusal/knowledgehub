@echo off
REM Starts mock LLM, Python backend and frontend in 3 windows (Windows, no Docker).
REM Set DATABASE_URL before running, e.g.: set DATABASE_URL=postgresql://user:pass@host:5432/postgres
if "%DATABASE_URL%"=="" (echo Please set DATABASE_URL first & exit /b 1)
start "mock-llm" cmd /k "cd /d %~dp0mock-llm && npm install && node server.js"
start "backend" cmd /k "cd /d %~dp0backend-python && (if not exist .venv python -m venv .venv) && .venv\Scripts\pip install -r requirements-dev.txt && .venv\Scripts\python run.py"
start "frontend" cmd /k "cd /d %~dp0frontend && npm install && npm run dev -- --port 5173"
echo Open http://localhost:5173 once all three windows are ready.
