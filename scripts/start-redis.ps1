$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $backendRoot '.local\redis'
$version = '8.10.2'
$package = "Redis-$version-Windows-x64-cygwin"
$expectedHash = '6de5cc7f5adbf97b5928b13766383d4ad424626ef3d8b313ffff12d820ec6fc1'
$archive = Join-Path $localRoot "$package.zip"
New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
$server = Get-ChildItem -LiteralPath $localRoot -Filter 'redis-server.exe' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $server) {
    # Community Windows build; checksum from the release's GitHub asset metadata.
    Invoke-WebRequest -Uri "https://github.com/redis-windows/redis-windows/releases/download/$version/$package.zip" -OutFile $archive -UseBasicParsing
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        throw 'Checksum do Redis invalido. O arquivo nao sera executado.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $localRoot -Force
    $server = Get-ChildItem -LiteralPath $localRoot -Filter 'redis-server.exe' -Recurse | Select-Object -First 1
}
if (-not $server) { throw 'Executavel redis-server.exe nao encontrado.' }
$cli = Join-Path $server.DirectoryName 'redis-cli.exe'
if (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
    $pong = & $cli -h 127.0.0.1 -p 6379 PING
    if ($pong -eq 'PONG') { Write-Output 'Redis ja esta ativo em 127.0.0.1:6379.'; exit 0 }
    throw 'A porta 6379 esta ocupada por outro processo ou exige credenciais.'
}
Start-Process -FilePath $server.FullName -WorkingDirectory $server.DirectoryName -WindowStyle Hidden -ArgumentList @(
    '--bind', '127.0.0.1', '--port', '6379', '--protected-mode', 'yes',
    '--appendonly', 'yes', '--maxmemory', '128mb', '--maxmemory-policy', 'noeviction'
) -RedirectStandardOutput (Join-Path $localRoot 'redis.log') -RedirectStandardError (Join-Path $localRoot 'redis-error.log') | Out-Null
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 500
    if (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
        $pong = & $cli -h 127.0.0.1 -p 6379 PING
        if ($pong -eq 'PONG') { Write-Output 'Redis ativo em 127.0.0.1:6379 (PONG).'; exit 0 }
    }
}
throw "Redis nao iniciou. Consulte $localRoot\redis-error.log."
