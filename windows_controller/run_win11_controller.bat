@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Windows 11 安卓远程控制终端
echo ========================================================
echo   Windows 11 安卓远程控制终端 启动中...
echo   要求: Windows 11 已安装 Python 3.x
echo ========================================================
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Python 环境！
    echo 请在 Windows 11 安装 Python 3 (https://www.python.org/downloads/)
    pause
    exit /b
)
python "%~dp0win11_controller.py"
pause
