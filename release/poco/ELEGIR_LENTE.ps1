# Run from ELEGIR_LENTE.bat. No settings or code edits are required.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$adbPath = Join-Path $PSScriptRoot 'adb.exe'
$scrcpyPath = Join-Path $PSScriptRoot 'scrcpy.exe'
$env:SCRCPY_SERVER_PATH = Join-Path $PSScriptRoot 'scrcpy-server'
$preferredSerial = 'adb-a7664a5-oGHxQd._adb-tls-connect._tcp'
$devices = @(& $adbPath devices | ForEach-Object {
    if ($_ -match '^([A-Za-z0-9_.:-]+)\s+device\s*$') { $Matches[1] }
})
if ($devices.Count -eq 0) {
    Write-Host 'No hay un telefono autorizado por ADB. Conecta el POCO y acepta su aviso de depuracion.'
    Read-Host 'Enter para salir'
    exit 1
}
if ($devices -contains $preferredSerial) {
    $selectedSerial = $preferredSerial
} elseif ($devices.Count -eq 1) {
    $selectedSerial = $devices[0]
} else {
    for ($i = 0; $i -lt $devices.Count; $i++) { Write-Host "[$($i + 1)] $($devices[$i])" }
    $selection = 0
    $answer = Read-Host 'Numero del POCO'
    if (-not [int]::TryParse($answer, [ref]$selection) -or $selection -lt 1 -or $selection -gt $devices.Count) { exit 1 }
    $selectedSerial = $devices[$selection - 1]
}
Write-Host "Consultando las camaras de $selectedSerial ..."
# Capture stderr as well: scrcpy and ADB print diagnostics there.
$ErrorActionPreference = 'Continue'
$listing = @(& $scrcpyPath -s $selectedSerial --list-cameras 2>&1 | ForEach-Object { "$_" })
$listExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$cameras = @($listing | ForEach-Object {
    if ($_ -match '--camera-id=([A-Za-z0-9_.:-]+)\s+(.+)$') {
        [pscustomobject]@{ Id = $Matches[1]; Description = $Matches[2] }
    }
})
if ($listExitCode -ne 0 -or $cameras.Count -eq 0) {
    $listing | ForEach-Object { Write-Host $_ }
    Read-Host 'No se pudo obtener la lista. Enter para salir'
    exit 1
}
$windows = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
while ($true) {
    Write-Host ''
    for ($i = 0; $i -lt $cameras.Count; $i++) {
        $description = $cameras[$i].Description.Replace('back', 'trasera').Replace('front', 'frontal')
        Write-Host "[$($i + 1)] Camara $($cameras[$i].Id): $description"
    }
    Write-Host 'Numero: cambiar de lente (cierra las ventanas que abrio este selector).'
    Write-Host '+numero: abrir otra ventana, por ejemplo +2. Q: salir del selector.'
    Write-Host 'F1 muestra los controles en cada ventana. Si otra app ocupa la camara, cierrala.'
    $answer = (Read-Host 'Seleccion').Trim()
    if ($answer -match '^[qQ]$') { break }
    if ($answer -notmatch '^(\+?)([0-9]+)$') { continue }
    $extraWindow = $Matches[1] -eq '+'
    $index = 0
    if (-not [int]::TryParse($Matches[2], [ref]$index) -or $index -lt 1 -or $index -gt $cameras.Count) { continue }
    if (-not $extraWindow) {
        foreach ($window in $windows) {
            if (-not $window.HasExited) {
                $null = $window.CloseMainWindow()
                if (-not $window.WaitForExit(2000)) { $window.Kill(); $window.WaitForExit() }
            }
        }
        $windows.Clear()
        Start-Sleep -Milliseconds 500
    }
    $camera = $cameras[$index - 1]
    $arguments = @('-s', $selectedSerial, '--video-source=camera', "--camera-id=$($camera.Id)",
        '--max-size=1920', '--no-audio', '--shortcut-mod=lalt',
        "--window-title=`"POCO - Camara $($camera.Id) - F1 ayuda`"")
    $window = Start-Process -FilePath $scrcpyPath -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -PassThru
    $windows.Add($window)
    if ($extraWindow) {
        Write-Host 'Android decide que camaras pueden funcionar juntas. Si la nueva falla, usa el numero sin + para cambiar.'
    }
}
