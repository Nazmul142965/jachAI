param(
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDir = Join-Path $projectRoot 'target\local-dev'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Test-LocalPort([int]$Number) {
    return [bool](Get-NetTCPConnection -LocalPort $Number -State Listen -ErrorAction SilentlyContinue)
}

if (-not (Test-LocalPort 27017)) {
    $knownMongo = Join-Path $env:USERPROFILE 'Documents\Codex\local-tools\mongodb\server\mongodb-win32-x86_64-windows-8.0.29\bin\mongod.exe'
    $onPath = Get-Command mongod -ErrorAction SilentlyContinue
    $mongoExe = @($env:JACHAI_MONGOD_EXE, $onPath.Source, $knownMongo) |
        Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
    if (-not $mongoExe) {
        throw 'MongoDB is not running. Install MongoDB or set JACHAI_MONGOD_EXE to mongod.exe.'
    }

    $mongoData = if ($env:JACHAI_MONGO_DATA_DIR) {
        $env:JACHAI_MONGO_DATA_DIR
    } else {
        Join-Path $env:USERPROFILE 'Documents\Codex\local-tools\mongodb\data'
    }
    New-Item -ItemType Directory -Path $mongoData -Force | Out-Null
    Start-Process -FilePath $mongoExe -WindowStyle Hidden -PassThru `
        -ArgumentList "--bind_ip 127.0.0.1 --port 27017 --dbpath `"$mongoData`"" `
        -RedirectStandardOutput (Join-Path $logDir 'mongo.out.log') `
        -RedirectStandardError (Join-Path $logDir 'mongo.err.log') | Out-Null
    for ($i = 0; $i -lt 15 -and -not (Test-LocalPort 27017); $i++) {
        Start-Sleep -Seconds 1
    }
    if (-not (Test-LocalPort 27017)) {
        throw "MongoDB did not start. Check $logDir\mongo.err.log."
    }
}

$healthUrl = "http://127.0.0.1:$Port/api/v1/health"
if (Test-LocalPort $Port) {
    try {
        Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3 | Out-Null
        Write-Host "JachAI is already running at http://127.0.0.1:$Port/"
        exit 0
    } catch {
        throw "Port $Port is occupied by another service; JachAI was not started."
    }
}

$java21Home = Join-Path $env:USERPROFILE '.jdks\ms-21.0.12.1'
if ((Test-Path -LiteralPath (Join-Path $java21Home 'bin\java.exe')) -and
    (-not $env:JAVA_HOME -or -not ((& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Out-String) -match 'version "21'))) {
    $env:JAVA_HOME = $java21Home
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'JDK 21 is required. Set JAVA_HOME to your JDK 21 installation.'
}
$javaVersion = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Out-String
if ($javaVersion -notmatch 'version "21') {
    throw 'JAVA_HOME must point to JDK 21.'
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = "$Port"
$env:MONGODB_URI = 'mongodb://127.0.0.1:27017/jachai_local'
$env:CACHE_ENABLED = 'false'
$env:REDIS_REVOCATION_ENABLED = 'false'
$env:AI_PROVIDER = 'none'
$env:AI_TUTOR_PROVIDER = 'none'
if (-not $env:JWT_SECRET) {
    $secretBytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Fill($secretBytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($secretBytes)
    Write-Warning 'Using a temporary local JWT secret. Existing sign-ins will expire after restart.'
}

Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -WorkingDirectory $projectRoot `
    -ArgumentList '/d', '/c', '.\mvnw.cmd spring-boot:run' `
    -RedirectStandardOutput (Join-Path $logDir 'app.out.log') `
    -RedirectStandardError (Join-Path $logDir 'app.err.log') | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2 | Out-Null
        Write-Host "JachAI is ready at http://127.0.0.1:$Port/"
        Write-Host "Development email links appear in $logDir\app.out.log"
        exit 0
    } catch {
        Start-Sleep -Seconds 2
    }
}
throw "JachAI did not become ready. Check $logDir\app.out.log and app.err.log."
