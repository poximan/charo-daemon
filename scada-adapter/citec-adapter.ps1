[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [Alias("Tag")]
    [string]$TagName
)

$ErrorActionPreference = "Stop"
$scriptPath = $PSCommandPath
$scriptDir = Split-Path -Parent $scriptPath
$configPath = Join-Path $scriptDir "adapter-config.json"
$tagsPath = Join-Path $scriptDir "tags.json"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Result {
    param([object]$Payload, [int]$Exit = 0)
    $Payload | ConvertTo-Json -Compress
    exit $Exit
}

$hasTag = -not [string]::IsNullOrWhiteSpace($TagName)

if (!(Test-Path $configPath)) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "adapter-config.json no encontrado" } -Exit 2
}
if (!(Test-Path $tagsPath)) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "tags.json no encontrado" } -Exit 3
}

$config = Get-Content -Raw -Path $configPath | ConvertFrom-Json
$tags = Get-Content -Raw -Path $tagsPath | ConvertFrom-Json

$powershellPath = $config.powershell32Path
if (-not $powershellPath -or [string]::IsNullOrWhiteSpace($powershellPath)) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "powershell32Path no definido en adapter-config.json" } -Exit 7
}
if (-not [System.IO.Path]::IsPathRooted($powershellPath)) {
    $powershellPath = Join-Path $scriptDir $powershellPath
}
$powershellPath = [System.IO.Path]::GetFullPath($powershellPath)
if (!(Test-Path $powershellPath)) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "powershell32Path no existe: $powershellPath" } -Exit 7
}

if (-not $hasTag) {
    if (-not $tags) {
        Write-Error "tags.json no contiene tags para procesar"
        exit 8
    }
    Write-Verbose "[BATCH] Ejecutando tags desde $tagsPath usando $powershellPath"
    foreach ($entry in $tags) {
        $tagValue = if ($entry -is [string]) { $entry } elseif ($entry.PSObject.Properties["tag"]) { $entry.tag } else { $null }
        if ([string]::IsNullOrWhiteSpace($tagValue)) {
            continue
        }
        Write-Verbose "[BATCH] Invocando citec-adapter.ps1 para tag $tagValue"
        & $powershellPath '-NoProfile' '-ExecutionPolicy' 'Bypass' '-File' $scriptPath '-TagName' $tagValue
    }
    exit 0
}

$TagName = $TagName.Trim()
Write-Verbose "[Adapter] Procesando tag solicitado '$TagName'"
Write-Verbose "[Adapter] Config: $configPath | Tags: $tagsPath"

$resolvedDllPath = if ([System.IO.Path]::IsPathRooted($config.dllPath)) {
    $config.dllPath
} else {
    Join-Path $scriptDir $config.dllPath
}
$dllDir = Split-Path -Parent $resolvedDllPath
Write-Verbose "[Adapter] DLL esperado en $resolvedDllPath"
$match = $tags | Where-Object { $_.tag -ieq $TagName } | Select-Object -First 1
if (-not $match) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "tag desconocido" } -Exit 4
}

$dllPath = $resolvedDllPath
if (!(Test-Path $dllPath)) {
    Write-Result -Payload @{ status = "error"; tag = $TagName; message = "CtApi.dll no encontrada" } -Exit 5
}

Write-Verbose "[Adapter] Cargando tipos CtApi..."
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

namespace CitecAdapter
{
    public static class Native
    {
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        public static extern bool SetDllDirectory(string lpPathName);

        [DllImport("CtApi.dll", CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern IntPtr ctOpen(string computer, string user, string password, uint mode);

        [DllImport("CtApi.dll", CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern int ctClose(IntPtr handle);

        [DllImport("CtApi.dll", CallingConvention = CallingConvention.StdCall, CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern int ctTagRead(IntPtr handle, string tag, byte[] buffer, int size);
    }
}
"@ -Language CSharp

$setResult = [CitecAdapter.Native]::SetDllDirectory($dllDir)
Write-Verbose "[Adapter] SetDllDirectory('$dllDir') -> $setResult"

function Invoke-CitectTag {
    param([string]$Tag)
    Write-Verbose "[Adapter] Invocando ctOpen (modo local)"
    $handle = [CitecAdapter.Native]::ctOpen($null, $null, $null, 0)
    if ($handle -eq [IntPtr]::Zero) {
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "ctOpen fallo (codigo $err)"
    }
    try {
        $buffer = New-Object byte[] $config.tagBufferSize
        Write-Verbose "[Adapter] ctTagRead($Tag) buffer=$($buffer.Length)"
        $result = [CitecAdapter.Native]::ctTagRead($handle, $Tag, $buffer, $buffer.Length)
        if ($result -eq 0) {
            $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
            throw "ctTagRead fallo para $Tag (codigo $err)"
        }
        $value = [System.Text.Encoding]::ASCII.GetString($buffer)
        return $value.Trim([char]0).Trim()
    }
    finally {
        [CitecAdapter.Native]::ctClose($handle) | Out-Null
    }
}

try {
    Write-Verbose "[Adapter] Ejecutando lectura para $($match.tag)"
    $value = Invoke-CitectTag -Tag $match.tag
    $payload = [ordered]@{
        status = "ok"
        tag = $match.tag
        state = $value
        timestamp = (Get-Date).ToUniversalTime().ToString("o")
    }
    Write-Result -Payload $payload -Exit 0
}
catch {
    Write-Verbose "[Adapter] Error detectado: $($_.Exception.Message)"
    Write-Result -Payload @{ status = "error"; tag = $match.tag; message = $_.Exception.Message } -Exit 6
}
