param(
    [Parameter(Mandatory=$true)]
    [string]$FilePath
)

$lines = Get-Content $FilePath -Encoding UTF8

if ($lines -match 'EnabledIfEnvironmentVariable') {
    Write-Host "Already has annotation: $FilePath"
    return
}

$newLines = @()
$packageFound = $false
$classFound = $false

foreach ($line in $lines) {
    $newLines += $line
    
    # After package declaration, add import
    if (-not $packageFound -and $line.TrimStart().StartsWith('package ')) {
        $packageFound = $true
        $newLines += 'import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;'
    }
    
    # Before class declaration, add annotation
    if (-not $classFound -and $line.TrimStart().StartsWith('class ') -and $line -match 'class \w+IntegrationTests') {
        $classFound = $true
        # Remove the last line we just added (the class line) and prepend annotation
        $newLines[$newLines.Count - 1] = '@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")'
        $newLines += $line
    }
}

if (-not $packageFound) { Write-Error "No package declaration found in $FilePath"; return }
if (-not $classFound) { Write-Error "No class declaration found in $FilePath"; return }

# Write without BOM using .NET directly
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines($FilePath, $newLines, $utf8NoBom)
Write-Host "Updated: $FilePath"