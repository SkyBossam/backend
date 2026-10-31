param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $bundledJdk = Join-Path $projectRoot '.tools\jdk'
    if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $bundledJdk -Directory | Select-Object -First 1).FullName }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 21 installation.' }
    $localData = Join-Path $projectRoot 'data'
    New-Item -ItemType Directory -Path $localData -Force | Out-Null
    $secretFile = Join-Path $localData 'local-secrets.json'
    if (-not (Test-Path -LiteralPath $secretFile)) {
        function New-LocalSecret {
            $bytes = New-Object byte[] 48
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            return [Convert]::ToBase64String($bytes)
        }
        @{ jwt = New-LocalSecret; worker = New-LocalSecret } | ConvertTo-Json | Set-Content -LiteralPath $secretFile -Encoding UTF8
    }
    $keys = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
    $env:JWT_SECRET = $keys.jwt
    $env:WORKER_KEY = $keys.worker
    $env:PORT = "$Port"
    $env:APP_ENV = 'development'
    $env:MAIL_MODE = 'local'
    $env:DEBUG = 'false'
    $maven = Join-Path $projectRoot '.tools\maven\apache-maven-3.9.11\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) { $maven = Join-Path $projectRoot 'mvnw.cmd' }
    Write-Host "Starting aicoo at http://127.0.0.1:$Port (API docs: /docs). Press Ctrl+C to stop."
    $ErrorActionPreference = 'Continue'
    & $maven '-Dmaven.repo.local=.m2' -B -ntp test-compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java '-Dexec.mainClass=com.aicoo.LocalServer' '-Dexec.classpathScope=test' '-Dexec.cleanupDaemonThreads=false'
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
