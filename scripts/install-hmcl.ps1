param(
    [string] $InstanceDir = $env:MCAI_HMCL_INSTANCE_DIR,
    [switch] $NoBuild,
    [switch] $KeepOld
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot

if (-not $InstanceDir) {
    $candidate = Join-Path (Split-Path -Parent $ProjectDir) ".minecraft\versions\1.21.1-NeoForge"
    if (Test-Path -LiteralPath $candidate) {
        $InstanceDir = $candidate
    }
}

if (-not $InstanceDir) {
    throw "Set MCAI_HMCL_INSTANCE_DIR or pass -InstanceDir with your HMCL NeoForge instance path."
}

if (-not (Test-Path -LiteralPath $InstanceDir)) {
    throw "HMCL instance directory does not exist: $InstanceDir"
}

$resolvedInstanceDir = (Resolve-Path -LiteralPath $InstanceDir).Path
$modsDir = Join-Path $resolvedInstanceDir "mods"
New-Item -ItemType Directory -Path $modsDir -Force | Out-Null

if (-not $NoBuild) {
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat build --no-daemon --console plain
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$libsDir = Join-Path $ProjectDir "build\libs"
$jar = Get-ChildItem -LiteralPath $libsDir -Filter "mc_ai_companion-*.jar" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "No built mod jar found in $libsDir. Run without -NoBuild first."
}

if (-not $KeepOld) {
    Get-ChildItem -LiteralPath $modsDir -Filter "mc_ai_companion-*.jar" |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
}

$destination = Join-Path $modsDir $jar.Name
Copy-Item -LiteralPath $jar.FullName -Destination $destination -Force

Write-Host "Installed $($jar.Name) to $destination"
Write-Host "Restart the HMCL NeoForge instance to load the new build."
