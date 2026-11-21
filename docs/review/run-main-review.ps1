[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Snapshot,
    [string]$Jdk
)

$ErrorActionPreference = 'Stop'
$snapshotPath = (Resolve-Path -LiteralPath $Snapshot).Path
$properties = Get-Content -LiteralPath (Join-Path $snapshotPath 'gradle.properties') -Raw
if ($properties -notmatch '(?m)^modVersion=0\.3\.0-preview\.2\r?$') {
    throw 'This probe requires a separately exported main snapshot at fb58e0a (0.3.0-preview.2).'
}

$updaterJar = Join-Path $snapshotPath 'updater/build/libs/ModpackUpdate-updater-0.3.0-preview.2.jar'
$modJar = Join-Path $snapshotPath 'build/targets/fabric-1.20.1/libs/ModpackUpdate-0.3.0-preview.2-fabric-mc1.20.1.jar'
foreach ($artifact in @($updaterJar, $modJar)) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "Missing artifact: $artifact. Build the main snapshot first."
    }
}

$java = 'java'
$javac = 'javac'
if ($Jdk) {
    $jdkPath = (Resolve-Path -LiteralPath $Jdk).Path
    $java = Join-Path $jdkPath 'bin/java'
    $javac = Join-Path $jdkPath 'bin/javac'
    if ($env:OS -eq 'Windows_NT') {
        $java += '.exe'
        $javac += '.exe'
    }
}

$runDirectory = Join-Path $snapshotPath ('build/main-review-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $runDirectory 'classes'
$instances = Join-Path $runDirectory 'instances'
New-Item -ItemType Directory -Path $classes,$instances -Force | Out-Null
$separator = [IO.Path]::PathSeparator
$classpath = "$updaterJar$separator$modJar"
& $javac -encoding UTF-8 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'MainReviewProbe.java')
if ($LASTEXITCODE -ne 0) { throw "Probe compilation failed with exit code $LASTEXITCODE." }

& $java -cp "$classes$separator$classpath" dev.modpackupdate.updater.MainReviewProbe $instances |
    Tee-Object -FilePath (Join-Path $runDirectory 'results.log')
if ($LASTEXITCODE -ne 0) { throw "Probe failed with exit code $LASTEXITCODE." }
Write-Output "Review evidence: $runDirectory"
