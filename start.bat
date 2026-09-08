@echo off
chcp 65001 >nul 2>&1
title MusicHub
setlocal enabledelayedexpansion

set BATDIR=%~dp0
set BATDIR=%BATDIR:~0,-1%

echo Stopping previous MusicHub services if they are running...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8082" ^| findstr "LISTENING"') do taskkill /F /PID %%P >nul 2>&1
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8081" ^| findstr "LISTENING"') do taskkill /F /PID %%P >nul 2>&1
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8090" ^| findstr "LISTENING"') do taskkill /F /PID %%P >nul 2>&1
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8100" ^| findstr "LISTENING"') do taskkill /F /PID %%P >nul 2>&1

echo ============================
echo   MusicHub - Music Player
echo ============================
echo.

where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js not found
    pause
    exit /b 1
)

where python >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found
    pause
    exit /b 1
)

where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven not found
    pause
    exit /b 1
)

echo [1/4] Starting local vector RAG...
set RAGDIR=%BATDIR%\rag-service
set RAGPYTHON=%RAGDIR%\.venv\Scripts\python.exe
if exist "%RAGPYTHON%" goto check_rag_dependencies

echo RAG environment not found. Creating it now...
python -m venv "%RAGDIR%\.venv"
if errorlevel 1 (
    echo [ERROR] Failed to create the RAG Python environment
    pause
    exit /b 1
)

:check_rag_dependencies
"%RAGPYTHON%" -c "import fastapi, uvicorn, sentence_transformers, faiss, pymysql, dotenv" >nul 2>&1
if not errorlevel 1 goto start_rag

echo Installing RAG dependencies. The first installation may take several minutes...
"%RAGPYTHON%" -m pip install -r "%RAGDIR%\requirements.txt"
if errorlevel 1 (
    echo [ERROR] Failed to install RAG dependencies
    pause
    exit /b 1
)

:start_rag
start "Vector RAG" "%ComSpec%" /k "cd /d ""%RAGDIR%"" && .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090"

echo [2/4] Starting LangGraph agent service...
set AGENTDIR=%BATDIR%\agent-service
set AGENTPYTHON=%AGENTDIR%\.venv\Scripts\python.exe
if exist "%AGENTPYTHON%" goto check_agent_dependencies

echo Agent environment not found. Creating it now...
python -m venv "%AGENTDIR%\.venv"
if errorlevel 1 (
    echo [ERROR] Failed to create the Agent Python environment
    pause
    exit /b 1
)

:check_agent_dependencies
"%AGENTPYTHON%" -c "import fastapi, uvicorn, langchain_core, langchain_openai, langgraph" >nul 2>&1
if not errorlevel 1 goto start_agent

echo Installing Agent dependencies...
"%AGENTPYTHON%" -m pip install -r "%AGENTDIR%\requirements.txt"
if errorlevel 1 (
    echo [ERROR] Failed to install Agent dependencies
    pause
    exit /b 1
)

:start_agent
start "LangGraph Agent" "%ComSpec%" /k "cd /d ""%AGENTDIR%"" && .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8100"

echo [3/4] Starting backend...
set BKDIR=%BATDIR%\springboot-web-demo
start "Backend" "%ComSpec%" /k "cd /d ""%BKDIR%"" && mvn spring-boot:run"

echo Waiting for backend...
ping -n 10 127.0.0.1 >nul

echo [4/4] Starting frontend...
set FEDIR=%BATDIR%\vue-login
if exist "%FEDIR%\node_modules\.bin\vue-cli-service.cmd" goto start_frontend

echo Frontend dependencies not found. Installing them now...
pushd "%FEDIR%"
call npm ci
set NPM_RESULT=%ERRORLEVEL%
popd
if not "%NPM_RESULT%"=="0" (
    echo [ERROR] Failed to install frontend dependencies
    pause
    exit /b 1
)

:start_frontend
start "Frontend" "%ComSpec%" /k "cd /d ""%FEDIR%"" && npm run serve"

echo Waiting for the frontend to start...
timeout /t 4 /nobreak >nul
echo Opening MusicHub in your browser...
start "" "http://localhost:8081"

echo.
echo ============================
echo   All services started!
echo.
echo   Frontend: http://localhost:8081
echo   Backend:  http://localhost:8082

echo   Vector RAG: http://localhost:8090/health
echo   Agent:      http://localhost:8100/health
echo ============================
pause
