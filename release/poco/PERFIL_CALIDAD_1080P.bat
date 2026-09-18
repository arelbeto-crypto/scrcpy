@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo Perfil Calidad 1080p: ventana capturable, H264 16M, sin bordes y siempre encima.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=3 --camera-size=1920x1080 --video-codec=h264 --video-bit-rate=16M --no-audio --shortcut-mod=lalt --window-title="POCO_CAM_LIVE" --window-borderless --always-on-top %*
pause
