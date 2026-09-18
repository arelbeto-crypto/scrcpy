@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
set "POCO_SERIAL=adb-a7664a5-oGHxQd._adb-tls-connect._tcp"
echo POCO V3 - camara logica/multicam ID 3 con controles en vivo.
echo Pulsa F1 en la ventana para ver los atajos.
echo Si no abre, usa INICIAR_POCO_SEGURO_ID0.bat.
scrcpy.exe -s "%POCO_SERIAL%" --video-source=camera --camera-id=3 --max-size=1920 --no-audio --shortcut-mod=lalt --window-title="POCO V3 - MultiCam ID3 - F1 ayuda" %*
echo.
echo Si cambio la conexion ADB, abre ELEGIR_LENTE.bat para detectar el telefono.
pause
