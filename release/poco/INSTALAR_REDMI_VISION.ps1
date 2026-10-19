$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$adb = Join-Path $PSScriptRoot 'adb.exe'
$apk = Join-Path $PSScriptRoot 'RedmiVisionCoprocessor.apk'

if (!(Test-Path $adb)) { throw "No se encontro adb.exe" }
if (!(Test-Path $apk)) { throw "No se encontro RedmiVisionCoprocessor.apk" }

$devices = @(& $adb devices | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
})

if ($devices.Count -eq 0) {
    throw "No hay dispositivos ADB autorizados."
}

Write-Host ""
Write-Host "Selecciona el REDMI NOTE 10 PRO:"
for ($i = 0; $i -lt $devices.Count; $i++) {
    $serial = $devices[$i]
    $model = (& $adb -s $serial shell getprop ro.product.model 2>$null | Out-String).Trim()
    $device = (& $adb -s $serial shell getprop ro.product.device 2>$null | Out-String).Trim()
    Write-Host "[$($i+1)] $serial | $model | $device"
}

$n = [int](Read-Host "Numero")
if ($n -lt 1 -or $n -gt $devices.Count) { throw "Seleccion invalida" }
$serial = $devices[$n-1]

Write-Host "Instalando Redmi Vision 8.7 junto a la version anterior en $serial..."
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "Fallo adb install. Si aparece INSTALL_FAILED_UPDATE_INCOMPATIBLE, la firma de otra preview V8.7 es distinta. No se ha desinstalado ni borrado ninguna app."
}

Write-Host "Deteniendo el servicio anterior para evitar dos conexiones; su app y datos se conservan."
& $adb -s $serial shell am force-stop com.poco.visioncoprocessor
if ($LASTEXITCODE -ne 0) { throw "Se instalo V8.7, pero no se pudo detener la version anterior. Detenla antes de abrir V8.7." }

Write-Host "Abriendo Redmi Vision 8.7..."
& $adb -s $serial shell am start -n com.poco.visioncoprocessor.v87/com.poco.visioncoprocessor.MainActivity
if ($LASTEXITCODE -ne 0) { throw "Se instalo, pero no se pudo abrir automaticamente." }

Write-Host ""
Write-Host "Listo. En el Redmi pulsa: Iniciar / autodetectar POCO"
