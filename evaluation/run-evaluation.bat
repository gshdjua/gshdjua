@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo MusicHub 50题检索效果评测
echo 请先确认 Spring Boot 后端和本地向量服务已经启动。
echo.
python run_retrieval_eval.py
echo.
pause
