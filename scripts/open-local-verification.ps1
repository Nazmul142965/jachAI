param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[^@\s]+@[^@\s]+\.[^@\s]+$')]
    [string]$Email,
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logName = if ($Port -eq 8080) { 'app.out.log' } else { "app.$Port.out.log" }
$logPath = Join-Path $projectRoot "target\local-dev\$logName"
if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
    throw "Local app log is missing. Start JachAI with scripts/start-local.ps1 on port $Port first."
}

$normalizedEmail = $Email.Trim().ToLowerInvariant()
$prefix = 'Development VERIFICATION email for ' + $normalizedEmail + ': '
$entry = Get-Content -LiteralPath $logPath | Where-Object { $_ -match [regex]::Escape($prefix) } | Select-Object -Last 1
if (-not $entry) {
    throw 'No local verification link for this email was found. Register or request a new link, then wait for the mail worker.'
}

$urlMatch = [regex]::Match($entry, [regex]::Escape($prefix) + '(https?://\S+)')
$uri = $null
if (-not $urlMatch.Success -or -not [uri]::TryCreate($urlMatch.Groups[1].Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -ne $Port -or
        $uri.AbsolutePath -ne '/verify-email.html' -or $uri.Query -notmatch '^\?token=[A-Za-z0-9_-]+$') {
    throw 'The local log does not contain a valid verification link for this app port.'
}

if ($CheckOnly) {
    Write-Host "A local verification link is available for $normalizedEmail on port $Port."
    exit 0
}

# The one-time token stays in the user's browser; do not echo it to the shell.
Start-Process -FilePath $uri.AbsoluteUri
Write-Host 'Opened the verification page in your browser. Return to sign-in after it confirms verification.'
