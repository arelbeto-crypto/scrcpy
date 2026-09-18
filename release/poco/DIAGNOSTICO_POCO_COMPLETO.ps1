# Genera un reporte completo del POCO para depurar camaras, ADB y scrcpy.
$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath $PSScriptRoot
$env:SCRCPY_SERVER_PATH = Join-Path $PSScriptRoot 'scrcpy-server'
$adbPath = Join-Path $PSScriptRoot 'adb.exe'
$scrcpyPath = Join-Path $PSScriptRoot 'scrcpy.exe'
$preferredSerial = 'adb-a7664a5-oGHxQd._adb-tls-connect._tcp'
$stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
$logDir = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir "diagnostico_poco_$stamp.txt"

function Add-Section([string]$Title, [scriptblock]$Command) {
    "" | Tee-Object -FilePath $log -Append | Out-Null
    "===== $Title =====" | Tee-Object -FilePath $log -Append | Out-Null
    try {
        & $Command 2>&1 | Tee-Object -FilePath $log -Append
    } catch {
        "ERROR: $($_.Exception.Message)" | Tee-Object -FilePath $log -Append | Out-Null
    }
}

$devices = @(& $adbPath devices | ForEach-Object {
    if ($_ -match '^([A-Za-z0-9_.:-]+)\s+device\s*$') { $Matches[1] }
})
$serial = $null
if ($devices -contains $preferredSerial) { $serial = $preferredSerial }
elseif ($devices.Count -ge 1) { $serial = $devices[0] }

"POCO V4 diagnostico" | Tee-Object -FilePath $log
"Fecha: $(Get-Date -Format o)" | Tee-Object -FilePath $log -Append
"Script: $PSCommandPath" | Tee-Object -FilePath $log -Append
"Serial elegido: $serial" | Tee-Object -FilePath $log -Append

Add-Section 'ADB devices' { & $adbPath devices -l }
if (-not $serial) {
    "No hay serial ADB usable." | Tee-Object -FilePath $log -Append
    Write-Host "Reporte: $log"
    exit 1
}

Add-Section 'Propiedades Android' {
    & $adbPath -s $serial shell getprop ro.product.model
    & $adbPath -s $serial shell getprop ro.product.device
    & $adbPath -s $serial shell getprop ro.build.version.sdk
    & $adbPath -s $serial shell getprop ro.build.version.release
    & $adbPath -s $serial shell getprop ro.build.fingerprint
}
Add-Section 'scrcpy version' { & $scrcpyPath --version }
Add-Section 'scrcpy list cameras' { & $scrcpyPath -s $serial --list-cameras }
Add-Section 'scrcpy list camera sizes' { & $scrcpyPath -s $serial --list-camera-sizes }
Add-Section 'Android camera service dump' { & $adbPath -s $serial shell dumpsys media.camera }
Add-Section 'Procesos de camara' { & $adbPath -s $serial shell ps -A | findstr /i "camera camerahal provider" }
Add-Section 'Resumen practico' {
    Write-Output 'Si camera-id=3 falla, prueba INICIAR_POCO_SEGURO_ID0.bat.'
    Write-Output 'Si otra app ocupa la camara, cierra Camara/WhatsApp/Telegram antes de iniciar.'
    Write-Output 'Si ADB cambio de serial, usa POCO_MENU.bat para redetectar.'
}
Write-Host "Reporte generado: $log"
