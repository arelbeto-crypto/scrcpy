$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:SCRCPY_SERVER_PATH = Join-Path $PSScriptRoot 'scrcpy-server'
$adb = Join-Path $PSScriptRoot 'adb.exe'
$exe = Join-Path $PSScriptRoot 'scrcpy.exe'
$devices = @(& $adb devices | ForEach-Object { if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] } })
if ($devices.Count -eq 0) { Write-Host 'Conecta el POCO y autoriza ADB.'; Read-Host 'Enter'; exit 1 }
$serial = $devices[0]
if ($devices.Count -gt 1) {
    for ($i=0; $i -lt $devices.Count; $i++) { Write-Host "[$($i+1)] $($devices[$i])" }
    $n = [int](Read-Host 'Numero del POCO')
    if ($n -lt 1 -or $n -gt $devices.Count) { exit 1 }
    $serial = $devices[$n-1]
}
function Camera([string]$id, [string[]]$extra) {
    $opts = @('-s', $serial, '--video-source=camera', "--camera-id=$id", '--no-audio', '--shortcut-mod=lalt', '--hwdec=disabled', '--video-codec=h264') + $extra
    & $exe @opts
}
while ($true) {
    Write-Host ''
    Write-Host 'POCO V8.5 / scrcpy 5.0.1 | Bokeh+ + Fast Tracking + Mask FX + Redmi Vision'
    Write-Host '[1] Camara ID0 auto | [2] MultiCam ID3 auto'
    Write-Host '[3] ID0 1080p 30fps | [4] ID3 1080p 30fps'
    Write-Host '[5] Listar camaras | [6] Version | [7] Controles V8.5 | [8] Instalar Redmi Vision | [Q] Salir'
    switch ((Read-Host 'Opcion').ToUpperInvariant()) {
       '1' { Camera '0' @() }
       '2' { Camera '3' @() }
       '3' { Camera '0' @('--camera-size=1920x1080', '--camera-fps=30', '--video-bit-rate=12M') }
       '4' { Camera '3' @('--camera-size=1920x1080', '--camera-fps=30', '--video-bit-rate=12M') }
       '5' { & $exe -s $serial --list-cameras; & $exe -s $serial --list-camera-sizes }
       '6' { & $exe --version }
       '7' { Get-Content (Join-Path $PSScriptRoot 'CONTROLES_AR_V85.txt') }
       '8' { & (Join-Path $PSScriptRoot 'INSTALAR_REDMI_VISION.ps1') }
       'Q' { return }
    }
}
