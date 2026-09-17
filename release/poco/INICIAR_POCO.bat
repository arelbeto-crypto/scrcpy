@echo off
setlocal
cd /d "%~dp0"
set "SCRCPY_SERVER_PATH=%~dp0scrcpy-server"
echo Camara POCO con controles en vivo. Pulsa F1 en la ventana para ver los atajos.
scrcpy.exe -s "adb-a7664a5-oGHxQd._adb-tls-connect._tcp" --video-source=camera --camera-facing=back --max-size=1920 --no-audio --shortcut-mod=lalt --window-title="POCO - Controles en vivo - F1 ayuda" %*
echo.
echo Si cambio la conexion ADB, abre ELEGIR_LENTE.bat para detectar el telefono.
pause
