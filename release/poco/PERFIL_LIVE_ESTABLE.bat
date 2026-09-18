@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo Perfil Live Estable: multicam ID3, 30 FPS, EIS si la camara lo acepta.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=3 --max-size=1920 --camera-fps=30 --camera-eis --no-audio --shortcut-mod=lalt --window-title="POCO V4 - Live Estable" %*
pause
