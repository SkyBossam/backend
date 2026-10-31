$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $env:DEBUG = 'false'
    $bundledJdk = Join-Path $projectRoot '.tools\jdk'
    if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $bundledJdk -Directory | Select-Object -First 1).FullName }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 21 installation.' }
    $maven = Join-Path $projectRoot '.tools\maven\apache-maven-3.9.11\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) { $maven = Join-Path $projectRoot 'mvnw.cmd' }
    $ErrorActionPreference = 'Continue'
    & $maven '-Dmaven.repo.local=.m2' -B -ntp verify
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Tests failed with code $LASTEXITCODE" }
} finally { Pop-Location }
