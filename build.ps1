[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)]
    [string]$Platform,
    [string]$Minecraft,
    [switch]$AllVersions,
    [switch]$ListTargets,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @()
)

$ErrorActionPreference = 'Stop'

try {
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build-targets.json') -Raw | ConvertFrom-Json
    $targets = @($manifest.targets)
    if ($manifest.schemaVersion -ne 1 -or $targets.Count -eq 0) {
        throw 'build-targets.json must contain schemaVersion 1 and a non-empty targets array.'
    }
    $ids = @{}
    foreach ($target in $targets) {
        if ($target.platform -notin @('fabric', 'neoforge') -or
            $target.adapter -ne $target.platform -or
            $target.minecraft -notmatch '^\d+\.\d+(\.\d+)?$' -or
            $target.id -cne "$($target.platform)-$($target.minecraft)" -or
            $ids.ContainsKey($target.id) -or $target.java -lt 17) {
            throw "Invalid or duplicate build target: $($target.id)"
        }
        $ids[$target.id] = $true
    }
    if ($ListTargets) {
        $targets | Select-Object id, platform, minecraft, loaderVersion, java | Format-Table -AutoSize
        exit 0
    }
    if ([string]::IsNullOrWhiteSpace($Platform)) {
        $Platform = Read-Host 'Build platform (fabric / neoforge / all)'
    }
    $Platform = $Platform.Trim().ToLowerInvariant()
    $Minecraft = $Minecraft.Trim()
    if ($Platform -notin @('fabric', 'neoforge', 'all')) {
        throw 'Usage: ./build.ps1 -Platform <fabric|neoforge|all> [-Minecraft <version> | -AllVersions] [Gradle arguments]'
    }
    if ($Minecraft -and ($AllVersions -or $Platform -eq 'all')) {
        throw '-Minecraft cannot be combined with -AllVersions or -Platform all.'
    }
    if ($Minecraft) {
        $selected = @($targets | Where-Object { $_.platform -eq $Platform -and $_.minecraft -eq $Minecraft })
        if ($selected.Count -ne 1) {
            throw "Unsupported target: $Platform / Minecraft $Minecraft. Use -ListTargets to see supported combinations."
        }
        $distribution = $selected[0].id
    } else {
        $selected = @($targets | Where-Object { $Platform -eq 'all' -or $_.platform -eq $Platform })
        if ($selected.Count -eq 0) { throw "No registered targets for $Platform." }
        $distribution = $Platform
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}

$task = "${distribution}Dist"
$outputDirectory = Join-Path $PSScriptRoot "build/dist/$distribution"
$buildExitCode = 1
Push-Location -LiteralPath $PSScriptRoot
try {
    Write-Host "Building targets: $($selected.id -join ', ')"
    if ($env:OS -eq 'Windows_NT') {
        & (Join-Path $PSScriptRoot 'gradlew.bat') $task @gradleArguments
    } else {
        & sh (Join-Path $PSScriptRoot 'gradlew') $task @gradleArguments
    }
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExitCode -eq 0) { Write-Host "Output directory: $outputDirectory" }
exit $buildExitCode
