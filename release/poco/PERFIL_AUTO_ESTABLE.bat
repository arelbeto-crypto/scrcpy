@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo Perfil Auto Estable: multicam ID3, 30 FPS, automatico.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=3 --max-size=1920 --camera-fps=30 --no-audio --shortcut-mod=lalt --window-title="POCO V4 - Auto Estable" %*
pause
