param(
    [ValidateRange(1024, 65535)] [int]$Port = 8080,
    [ValidatePattern('^jachai_(dev|demo_[A-Za-z0-9_-]+|smtp_[A-Za-z0-9_-]+)$')]
    [string]$DatabaseName = 'jachai_dev',
    [switch]$Smtp
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDir = Join-Path $projectRoot 'target\local-dev'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$appLog = if ($Port -eq 8080) { 'app.out.log' } else { "app.$Port.out.log" }
$appErrorLog = if ($Port -eq 8080) { 'app.err.log' } else { "app.$Port.err.log" }

if ($Smtp) {
    if ($DatabaseName -notmatch '^jachai_smtp_[A-Za-z0-9_-]+$') {
        throw 'SMTP testing requires a separate jachai_smtp_* local database; never point it at demo data.'
    }
    foreach ($name in @('MAIL_HOST', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'MAIL_FROM', 'JWT_SECRET')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            throw "SMTP testing requires $name in the private shell environment."
        }
    }
    if ([Text.Encoding]::UTF8.GetByteCount($env:JWT_SECRET) -lt 32) {
        throw 'SMTP testing requires JWT_SECRET to contain at least 32 UTF-8 bytes.'
    }
}

function Test-LocalPort([int]$Number) {
    return [bool](Get-NetTCPConnection -LocalPort $Number -State Listen -ErrorAction SilentlyContinue)
}

$healthUrl = "http://127.0.0.1:$Port/api/v1/health"
if (Test-LocalPort $Port) {
    if ($Smtp) {
        throw "Port $Port is occupied; choose a free port for the isolated SMTP test instance."
    }
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
$env:SPRING_PROFILES_ACTIVE = if ($Smtp) { 'local,smtp' } else { 'local' }
$env:SERVER_PORT = "$Port"
$env:SERVER_ADDRESS = '127.0.0.1'
$env:LOCAL_MONGODB_DATABASE = $DatabaseName
# Use Atlas credentials from MONGODB_URI or the ignored local secrets file.
# Never substitute a localhost MongoDB server or put the database in the URI.
if ($env:MONGODB_URI) {
    $env:SPRING_DATA_MONGODB_URI = $env:MONGODB_URI
} else {
    Remove-Item Env:SPRING_DATA_MONGODB_URI -ErrorAction SilentlyContinue
}
$env:SPRING_DATA_MONGODB_DATABASE = $DatabaseName
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
        if ($Smtp) {
            Write-Host 'SMTP test mode is active; verification links are sent to the registered inbox.'
        } else {
            Write-Host "Development email links appear in $(Join-Path $logDir $appLog)"
        }
        exit 0
    } catch {
        Start-Sleep -Seconds 2
    }
}
throw "JachAI did not become ready. Check $logDir\app.out.log and app.err.log."
