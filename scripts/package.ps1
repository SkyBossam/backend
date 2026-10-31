$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$output = Join-Path $projectRoot 'outputs'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$archivePath = Join-Path $output 'aicoo-backend-source.zip'
$archive = [IO.Compression.ZipArchive]::new([IO.File]::Open($archivePath, [IO.FileMode]::Create), [IO.Compression.ZipArchiveMode]::Create)
try {
    $selectedFiles = @()
    foreach ($folder in @('src', 'docs', 'clients', 'scripts', 'raspberry-pi', '.mvn', '.github')) {
        $selectedFiles += Get-ChildItem -LiteralPath (Join-Path $projectRoot $folder) -File -Recurse | Where-Object { $_.Extension -ne '.pyc' -and $_.FullName -notmatch '[\\/](__pycache__|aicoo-backups)[\\/]' -and $_.Name -notin @('identity.pem','credential.json','pending.json','server.json','agent.lock','bridge-cursor.json','bridge-commands.json','bridge-errors.json','bridge-binding.json','launcher.json','profile-reset.json','unlink-request.json','unlinked.json') }
    }
    foreach ($file in @('pom.xml','mvnw','mvnw.cmd','.gitignore','.gitattributes','.dockerignore','.env.example','Dockerfile','compose.yaml','README.md')) {
        $selectedFiles += Get-Item -LiteralPath (Join-Path $projectRoot $file)
    }
    foreach ($file in $selectedFiles) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "aicoo-backend/$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
Get-FileHash -LiteralPath $archivePath -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  aicoo-backend-source.zip" } | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ASCII
Write-Host $archivePath
