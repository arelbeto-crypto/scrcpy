@echo off
setlocal
cd /d "%~dp0"
echo Reiniciando ADB...
adb.exe kill-server
adb.exe start-server
adb.exe devices -l
echo.
echo Si el serial Wi-Fi cambio, abre POCO_MENU.bat para redetectar.
pause
