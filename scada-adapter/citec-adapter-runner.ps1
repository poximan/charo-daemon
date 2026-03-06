param(
    [Parameter(Mandatory = $false)]
    [Alias("Tag")]
    [string]$TagName,
    [int]$TimeoutSeconds = 25
)

$ErrorActionPreference = "Stop"
$scriptPath = $PSCommandPath
$scriptDir = Split-Path -Parent $scriptPath
$adapterScript = Join-Path $scriptDir "citec-adapter.ps1"
if (!(Test-Path $adapterScript)) {
    throw "No se encontro citec-adapter.ps1 en $adapterScript"
}

$configPath = Join-Path $scriptDir "adapter-config.json"
if (!(Test-Path $configPath)) {
    throw "adapter-config.json no encontrado en $configPath"
}
$config = Get-Content -Raw -Path $configPath | ConvertFrom-Json
if (-not ($config.PSObject.Properties["powershell32Path"])) {
    throw "Falta 'powershell32Path' en adapter-config.json"
}
$powershell32Path = $config.powershell32Path
if ([string]::IsNullOrWhiteSpace($powershell32Path)) {
    throw "'powershell32Path' no puede estar vacio en adapter-config.json"
}
if (-not [System.IO.Path]::IsPathRooted($powershell32Path)) {
    $powershell32Path = Join-Path $scriptDir $powershell32Path
}
$powershell32Path = [System.IO.Path]::GetFullPath($powershell32Path)
if (!(Test-Path $powershell32Path)) {
    throw "No se encontro PowerShell x86 configurado (powershell32Path='$powershell32Path')"
}

if ($TimeoutSeconds -lt 5) {
    $TimeoutSeconds = 5
}

$argumentList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $adapterScript)
if (-not [string]::IsNullOrWhiteSpace($TagName)) {
    $argumentList += '-TagName'
    $argumentList += $TagName.Trim()
}

$stdoutFile = [System.IO.Path]::GetTempFileName()
$stderrFile = [System.IO.Path]::GetTempFileName()
try {
    $process = Start-Process -FilePath $powershell32Path `
        -ArgumentList $argumentList `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $stdoutFile `
        -RedirectStandardError $stderrFile

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch { }
        throw "Tiempo de espera excedido ($TimeoutSeconds s) para citec-adapter"
    }

    if (Test-Path $stdoutFile) {
        $stdout = Get-Content -Path $stdoutFile -Raw
        if ($stdout) {
            $stdout.TrimEnd() | Write-Output
        }
    }
    if (Test-Path $stderrFile) {
        $stderr = Get-Content -Path $stderrFile -Raw
        if ($stderr) {
            Write-Error ($stderr.Trim())
        }
    }
    exit $process.ExitCode
}
finally {
    if (Test-Path $stdoutFile) { Remove-Item $stdoutFile -ErrorAction SilentlyContinue }
    if (Test-Path $stderrFile) { Remove-Item $stderrFile -ErrorAction SilentlyContinue }
}
