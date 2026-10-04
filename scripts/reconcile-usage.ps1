#Requires -Version 5.1
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$RepairProvable,
    [ValidateRange(1, 100000)] [int]$MaxOrganizations = 1000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$accessToken = [Environment]::GetEnvironmentVariable('JACHAI_PLATFORM_ACCESS_TOKEN')
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'Set JACHAI_PLATFORM_ACCESS_TOKEN in this shell. Do not put the token in a script or command argument.'
}

$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$baseUri)) {
    throw 'BaseUrl must be an absolute HTTP(S) URL.'
}
$localHost = $baseUri.Host -eq 'localhost' -or $baseUri.Host -eq '127.0.0.1' -or $baseUri.Host -eq '::1'
if (($baseUri.Scheme -ne 'https' -and -not ($baseUri.Scheme -eq 'http' -and $localHost)) -or
        $baseUri.UserInfo -or $baseUri.Query -or $baseUri.Fragment) {
    throw 'Use HTTPS, or HTTP only for loopback. BaseUrl must not contain credentials, query, or fragment.'
}

$api = $baseUri.AbsoluteUri.TrimEnd('/') + '/api/v1/platform'
$headers = @{ Authorization = "Bearer $accessToken" }

function Invoke-JachAiApi {
    param([string]$Method, [string]$Path)
    try {
        return Invoke-RestMethod -Uri ($api + $Path) -Method $Method -Headers $headers -ErrorAction Stop
    } catch {
        $status = 'unknown'
        if ($null -ne $_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        throw "JachAI API request failed: $Method $Path (HTTP $status). Response body omitted."
    }
}

$self = Invoke-JachAiApi -Method 'GET' -Path '/me'
if (-not $self.platformAdmin) {
    throw 'The supplied account is not a platform administrator.'
}

$seen = 0
$page = 0
do {
    $organizations = Invoke-JachAiApi -Method 'GET' -Path "/organizations?page=$page"
    foreach ($organization in @($organizations.content)) {
        if ($seen -ge $MaxOrganizations) { break }
        $seen++
        $id = [string]$organization.id
        $encodedId = [Uri]::EscapeDataString($id)
        $path = "/organizations/$encodedId/billing/usage-reconciliation"
        try {
            $report = Invoke-JachAiApi -Method 'GET' -Path $path
            $before = @($report.entries | Where-Object {
                $_.status -eq 'BACKFILL_REQUIRED' -or $_.status -eq 'STRANDED'
            }).Count
            $repairAttempted = $false
            if ($RepairProvable -and $before -gt 0 -and
                    $PSCmdlet.ShouldProcess($id, 'Repair only provable entitlement usage gaps')) {
                $repairAttempted = $true
                $report = Invoke-JachAiApi -Method 'POST' -Path $path
            }
            [pscustomobject]@{
                OrganizationId = $id
                RepairAttempted = $repairAttempted
                ProvableGapsBefore = $before
                AppliedBuckets = @($report.entries | Where-Object { $null -ne $_.appliedUsage }).Count
                ManualSteps = @($report.manualSteps)
                Status = 'OK'
            }
        } catch {
            [pscustomobject]@{
                OrganizationId = $id
                RepairAttempted = $false
                ProvableGapsBefore = $null
                AppliedBuckets = $null
                ManualSteps = @()
                Status = $_.Exception.Message
            }
        }
    }
    $page++
} while ($seen -lt $MaxOrganizations -and $page -lt [int]$organizations.totalPages)
