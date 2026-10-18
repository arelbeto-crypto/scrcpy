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

Write-Host "Instalando coprocesador en $serial..."
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "Fallo adb install" }

Write-Host "Abriendo Redmi Vision Coprocessor..."
& $adb -s $serial shell am start -n com.poco.visioncoprocessor/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "Se instalo, pero no se pudo abrir automaticamente." }

Write-Host ""
Write-Host "Listo. En el Redmi pulsa: Iniciar / autodetectar POCO"
