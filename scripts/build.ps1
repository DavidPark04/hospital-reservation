$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleCache = $env:GRADLE_USER_HOME
if (-not $gradleCache) { $gradleCache = Join-Path $projectRoot '.runtime\gradle' }
$gradleExecutable = Join-Path $projectRoot 'backend\gradlew.bat'
Push-Location (Join-Path $projectRoot 'backend')
try {
    & $gradleExecutable --gradle-user-home $gradleCache --no-daemon test bootJar
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
