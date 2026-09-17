@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0ELEGIR_LENTE.ps1"
if errorlevel 1 pause
