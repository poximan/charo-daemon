# Rutas base
$baseDir = "$HOME\Documents\hugo\git\infra-monitor"
$srcWin8 = Join-Path $baseDir "charo-daemon\daemon-app\build\install\daemon-app"
$srcWin7 = Join-Path $baseDir "charo-daemon-legacy\daemon-app\build\install\daemon-app"
$srcConfig = Join-Path $baseDir "charo-daemon\config"

# Destinos
$destWin8 = "Z:\info general\21 - Software\daemon-app\win8"
$destWin7 = "Z:\info general\21 - Software\daemon-app\win7"
$destConfig = "Z:\info general\21 - Software\daemon-app"

# Borrar contenido previo de win7 y win8
Remove-Item -Path "$destWin7\*" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -Path "$destWin8\*" -Recurse -Force -ErrorAction SilentlyContinue

# Copiar carpetas principales
Copy-Item -Path "$srcWin8\*" -Destination $destWin8 -Recurse
Copy-Item -Path "$srcWin7\*" -Destination $destWin7 -Recurse

# Borrar config destino si existe y luego copiar nuevo
Remove-Item -Path "$destConfig\config" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -Path $srcConfig -Destination $destConfig -Recurse

Write-Host "Despliegue completado para Win7, Win8 y config."