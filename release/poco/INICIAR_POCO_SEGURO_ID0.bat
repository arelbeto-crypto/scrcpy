@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo POCO V3 - modo seguro con camara trasera principal ID 0.
echo Pulsa F1 en la ventana para ver los atajos.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=0 --max-size=1920 --no-audio --shortcut-mod=lalt --window-title="POCO V3 - Back ID0 - F1 ayuda" %*
pause
