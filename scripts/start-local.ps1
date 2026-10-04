param(
    [ValidateRange(1024, 65535)] [int]$Port = 8080,
    [ValidatePattern('^jachai_(local|demo_[A-Za-z0-9_-]+)$')]
    [string]$DatabaseName = 'jachai_local'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDir = Join-Path $projectRoot 'target\local-dev'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$appLog = if ($Port -eq 8080) { 'app.out.log' } else { "app.$Port.out.log" }
$appErrorLog = if ($Port -eq 8080) { 'app.err.log' } else { "app.$Port.err.log" }

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
function Test-Java21Home([string]$JdkDirectory) {
    if (-not $JdkDirectory) { return $false }
    $executable = Join-Path $JdkDirectory 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { return $false }
    return (Get-Item -LiteralPath $executable).VersionInfo.ProductVersion -match '^21(\.|$)'
}
if (-not (Test-Java21Home $env:JAVA_HOME) -and (Test-Java21Home $java21Home)) {
    $env:JAVA_HOME = $java21Home
}
if (-not (Test-Java21Home $env:JAVA_HOME)) {
    throw 'JAVA_HOME must point to JDK 21.'
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = "$Port"
$env:SERVER_ADDRESS = '127.0.0.1'
$env:MONGODB_URI = "mongodb://127.0.0.1:27017/$DatabaseName"
$env:APP_PUBLIC_BASE_URL = "http://127.0.0.1:$Port"
$env:CACHE_ENABLED = 'false'
$env:REDIS_REVOCATION_ENABLED = 'false'
$env:AI_PROVIDER = 'none'
$env:AI_TUTOR_PROVIDER = 'none'
if (-not $env:JWT_SECRET) {
    $secretBytes = New-Object byte[] 48
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
    $env:JWT_SECRET = [Convert]::ToBase64String($secretBytes)
    Write-Warning 'Using a temporary local JWT secret. Existing sign-ins will expire after restart.'
}

Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -WorkingDirectory $projectRoot `
    -ArgumentList '/d', '/c', '.\mvnw.cmd spring-boot:run' `
    -RedirectStandardOutput (Join-Path $logDir $appLog) `
    -RedirectStandardError (Join-Path $logDir $appErrorLog) | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2 | Out-Null
        Write-Host "JachAI is ready at http://127.0.0.1:$Port/"
        Write-Host "Development email links appear in $(Join-Path $logDir $appLog)"
        exit 0
    } catch {
        Start-Sleep -Seconds 2
    }
}
throw "JachAI did not become ready. Check $logDir\app.out.log and app.err.log."
