@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo Perfil Enfoque Fijo: ID0, 30 FPS, distancia inicial 2.0 dioptrias.
echo Ajusta con Alt+Izquierda/Derecha y usa Alt+A para volver a autofocus.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=0 --max-size=1920 --camera-fps=30 --camera-focus-distance=2.0 --no-audio --shortcut-mod=lalt --window-title="POCO V4 - Enfoque Fijo" %*
pause
