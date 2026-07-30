# Ruta base (compatible con PowerShell 2.0)
$localBase = Split-Path -Parent $MyInvocation.MyCommand.Definition
$remoteWin = "\\SERVER02\Comunicaciones\info general\21 - Software\daemon-app\win8"
$remoteConfig = "\\SERVER02\Comunicaciones\info general\21 - Software\daemon-app\config"
$configPath = Join-Path $localBase "bin\config"
$binPath = Join-Path $localBase "bin"
$libPath = Join-Path $localBase "lib"

# Verificar si bin\config existe
$preserveConfig = Test-Path $configPath

# Paso 1: Eliminar carpeta lib completamente
if (Test-Path $libPath) {
    Remove-Item -Path $libPath -Recurse -Force -ErrorAction SilentlyContinue
}

# Paso 2: Borrar contenido de bin excepto config
if (Test-Path $binPath) {
    Get-ChildItem -Path $binPath -Force | Where-Object {
        $_.FullName -ne $configPath -and $_.FullName -notlike "$configPath\*"
    } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

# Paso 3: Copiar archivos desde carpeta remota
Copy-Item -Path "$remoteWin\*" -Destination $localBase -Recurse -Force

# Paso 4: Traer config si no existia
if (-not $preserveConfig) {
    Copy-Item -Path $remoteConfig -Destination $binPath -Recurse -Force
    Write-Host "bin\\config copiado desde red"
} else {
    Write-Host "bin\\config ya existia y fue preservado"
}

Write-Host "Actualizacion finalizada"