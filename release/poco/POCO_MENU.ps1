# POCO V4 Pro Tools menu. Run from POCO_MENU.bat.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:SCRCPY_SERVER_PATH = Join-Path $PSScriptRoot 'scrcpy-server'
$adbPath = Join-Path $PSScriptRoot 'adb.exe'
$scrcpyPath = Join-Path $PSScriptRoot 'scrcpy.exe'
$preferredSerial = 'adb-a7664a5-oGHxQd._adb-tls-connect._tcp'

function Get-PocoSerial {
    $devices = @(& $adbPath devices | ForEach-Object {
        if ($_ -match '^([A-Za-z0-9_.:-]+)\s+device\s*$') { $Matches[1] }
    })
    if ($devices.Count -eq 0) {
        Write-Host 'No hay telefono autorizado por ADB. Conecta el POCO y acepta depuracion USB/Wi-Fi.'
        return $null
    }
    if ($devices -contains $preferredSerial) { return $preferredSerial }
    if ($devices.Count -eq 1) { return $devices[0] }
    Write-Host 'Dispositivos ADB:'
    for ($i = 0; $i -lt $devices.Count; $i++) { Write-Host "[$($i + 1)] $($devices[$i])" }
    $selection = 0
    $answer = Read-Host 'Numero del POCO'
    if ([int]::TryParse($answer, [ref]$selection) -and $selection -ge 1 -and $selection -le $devices.Count) {
        return $devices[$selection - 1]
    }
    return $null
}

function Start-PocoCam([string]$Serial, [string]$CameraId, [string]$Title, [string[]]$ExtraArgs = @()) {
    $args = @('-s', $Serial, '--video-source=camera', "--camera-id=$CameraId", '--max-size=1920', '--no-audio', '--shortcut-mod=lalt', "--window-title=$Title") + $ExtraArgs
    Write-Host "Ejecutando: scrcpy $($args -join ' ')"
    & $scrcpyPath @args
}

function Run-Diagnostics {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'DIAGNOSTICO_POCO_COMPLETO.ps1')
}

$serial = Get-PocoSerial
if (-not $serial) { Read-Host 'Enter para salir'; exit 1 }

while ($true) {
    Clear-Host
    Write-Host 'POCO V4 Pro Tools'
    Write-Host "Serial activo: $serial"
    Write-Host ''
    Write-Host '[1] Abrir multicam ID3'
    Write-Host '[2] Abrir modo seguro ID0'
    Write-Host '[3] Abrir selector de lentes'
    Write-Host '[4] Perfil Auto Estable'
    Write-Host '[5] Perfil Live Estable'
    Write-Host '[6] Perfil Baja Luz'
    Write-Host '[7] Perfil Enfoque Fijo'
    Write-Host '[8] Perfil Calidad 1080p capturable'
    Write-Host '[9] Diagnostico completo'
    Write-Host '[10] Reiniciar ADB'
    Write-Host '[11] Ver camaras/tamanos'
    Write-Host '[Q] Salir'
    Write-Host ''
    $op = (Read-Host 'Opcion').Trim()
    switch -Regex ($op) {
        '^1$' { Start-PocoCam $serial '3' 'POCO V4 - MultiCam ID3 - F1 ayuda' }
        '^2$' { Start-PocoCam $serial '0' 'POCO V4 - Back ID0 - F1 ayuda' }
        '^3$' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ELEGIR_LENTE.ps1') }
        '^4$' { Start-PocoCam $serial '3' 'POCO V4 - Auto Estable' @('--camera-fps=30') }
        '^5$' { Start-PocoCam $serial '3' 'POCO V4 - Live Estable' @('--camera-fps=30', '--camera-eis') }
        '^6$' { Start-PocoCam $serial '0' 'POCO V4 - Baja Luz' @('--camera-fps=30', '--camera-iso=800', '--camera-exposure=33333333') }
        '^7$' { Start-PocoCam $serial '0' 'POCO V4 - Enfoque Fijo' @('--camera-fps=30', '--camera-focus-distance=2.0') }
        '^8$' { Start-PocoCam $serial '3' 'POCO_CAM_LIVE' @('--camera-size=1920x1080', '--video-codec=h264', '--video-bit-rate=16M', '--window-borderless', '--always-on-top') }
        '^9$' { Run-Diagnostics; Read-Host 'Enter para volver al menu' }
        '^10$' { & $adbPath kill-server; & $adbPath start-server; & $adbPath devices; $serial = Get-PocoSerial; Read-Host 'Enter para volver al menu' }
        '^11$' { & $scrcpyPath -s $serial --list-cameras; & $scrcpyPath -s $serial --list-camera-sizes; Read-Host 'Enter para volver al menu' }
        '^[qQ]$' { exit 0 }
    }
}
