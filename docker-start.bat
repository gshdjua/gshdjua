@echo off
chcp 65001 >nul 2>&1
title MusicHub Docker
setlocal

cd /d "%~dp0"

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Desktop is not installed or docker is not in PATH.
    pause
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Desktop is not running. Start it and try again.
    pause
    exit /b 1
)

if not exist ".env" (
    copy ".env.docker.example" ".env" >nul
    echo [NOTICE] Created .env. Add OPENAI_API_KEY if DeepSeek answers are required.
)

echo Building and starting MusicHub containers...
docker compose up -d --build
if errorlevel 1 (
    echo [ERROR] Docker startup failed. Run: docker compose logs -f
    pause
    exit /b 1
)

echo Waiting for the web service...
timeout /t 5 /nobreak >nul
start "" "http://localhost:8081"

echo.
echo MusicHub is running at http://localhost:8081
echo View logs: docker compose logs -f
echo Stop:      docker compose down
echo.
pause
