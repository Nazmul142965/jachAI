# Diagnoses a MongoDB connection string without printing it.
#
# Reads MONGODB_URI and MONGODB_DATABASE from the shell you run this in, so the
# values never appear on a command line, in this file, or in the output. Run it
# from the same window where you set them.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\check-mongo.ps1
#
# Exit codes:
#   0 = OK (connection succeeded)
#   1 = Configuration problem (MONGODB_URI not set, malformed URI, no database named)
#   2 = Authentication failed (bad credentials or missing permissions)
#   3 = Network/unreachable (server not reachable, IP not whitelisted, cluster paused)
#   4 = Other/unknown error

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

if (-not $env:MONGODB_URI) {
    Write-Output 'MONGODB_URI is not set in this shell.'
    Write-Output ''
    Write-Output 'Set it first, for example:'
    Write-Output '  $env:MONGODB_URI = "mongodb+srv://user:pass@cluster/"'
    Write-Output '  $env:MONGODB_DATABASE = "jachai_dev"'
    Write-Output ''
    Write-Output 'For local development with a local MongoDB on localhost:27017:'
    Write-Output '  $env:MONGODB_URI = "mongodb://localhost:27017"'
    Write-Output '  $env:MONGODB_DATABASE = "jachai_dev"'
    exit 1
}

# JDK 21, the same one the application needs.
$java21 = Join-Path $env:USERPROFILE '.jdks\ms-21.0.12.1'
if (Test-Path $java21) { $env:JAVA_HOME = $java21 }
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set and no JDK 21 was found.' }

$classpathFile = Join-Path $projectRoot 'target\doctor-cp.txt'
if (-not (Test-Path $classpathFile)) {
    Write-Output 'Collecting the MongoDB driver (first run only)...'
    & .\mvnw.cmd -o -q dependency:build-classpath "-Dmdep.outputFile=target/doctor-cp.txt" | Out-Null
}

$classpath = (Get-Content $classpathFile -Raw).Trim()
# The driver logs its settings, including the username, at INFO. Silence every
# logger so the only output is the doctor's own, which prints nothing sensitive.
$logback = Join-Path $PSScriptRoot 'doctor-logback.xml'
$exitCode = (& (Join-Path $env:JAVA_HOME 'bin\java.exe') "-Dlogback.configurationFile=$logback" -cp $classpath (Join-Path $PSScriptRoot 'MongoConnectionDoctor.java'))
exit $exitCode
