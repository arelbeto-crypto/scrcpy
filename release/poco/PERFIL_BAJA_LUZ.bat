@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo Perfil Baja Luz: ID0, ISO 800, obturacion 1/30s, 30 FPS.
echo Si se ve con demasiado ruido, baja ISO con Alt+Shift+Izquierda.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=0 --max-size=1920 --camera-fps=30 --camera-iso=800 --camera-exposure=33333333 --no-audio --shortcut-mod=lalt --window-title="POCO V4 - Baja Luz" %*
pause
