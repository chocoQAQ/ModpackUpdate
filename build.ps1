param(
    [string]$Platform
)

$ErrorActionPreference = 'Stop'
$gradleArguments = $args

if ([string]::IsNullOrWhiteSpace($Platform)) {
    $Platform = Read-Host 'Build platform (fabric / neoforge / all)'
}

$Platform = $Platform.Trim().ToLowerInvariant()
if ($Platform -notin @('fabric', 'neoforge', 'all')) {
    [Console]::Error.WriteLine('Usage: ./build.ps1 <fabric|neoforge|all> [Gradle arguments]')
    exit 2
}

$task = "${Platform}Dist"
$outputDirectory = Join-Path $PSScriptRoot "build/dist/$Platform"
$buildExitCode = 1

Push-Location -LiteralPath $PSScriptRoot
try {
    Write-Host "Building ModpackUpdate for $Platform"
    if ($env:OS -eq 'Windows_NT') {
        & (Join-Path $PSScriptRoot 'gradlew.bat') $task @gradleArguments
    } else {
        & sh (Join-Path $PSScriptRoot 'gradlew') $task @gradleArguments
    }
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($buildExitCode -eq 0) {
    Write-Host "Output directory: $outputDirectory"
}
exit $buildExitCode
