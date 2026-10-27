param(
    [string]$InstallerDir = "",
    [string]$Version = "",
    [switch]$RequireInstaller
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($InstallerDir)) {
    $InstallerDir = Join-Path $repoRoot "build\installer"
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    $buildFile = Join-Path $repoRoot "build.gradle.kts"
    $match = Select-String -Path $buildFile -Pattern '^\s*version\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $match) {
        throw "Could not determine project version from build.gradle.kts"
    }
    $Version = $match.Matches[0].Groups[1].Value
}

Write-Host "==> Verifying TidyFlow $Version package artifacts in $InstallerDir"

if (-not (Test-Path $InstallerDir)) {
    throw "Installer output directory does not exist: $InstallerDir"
}

$portableZip = Join-Path $InstallerDir "TidyFlow-$Version-portable.zip"
$installerExe = Join-Path $InstallerDir "TidyFlow-$Version.exe"
$checksumsFile = Join-Path $InstallerDir "SHA256SUMS.txt"

$minArtifactBytes = 10MB

if (-not (Test-Path $portableZip)) {
    throw "Portable ZIP not found: $portableZip"
}
$zipInfo = Get-Item $portableZip
if ($zipInfo.Length -lt $minArtifactBytes) {
    throw "Portable ZIP is unexpectedly small ($($zipInfo.Length) bytes): $portableZip"
}
Write-Host "OK portable ZIP: $($zipInfo.Name) ($([Math]::Round($zipInfo.Length / 1MB, 1)) MB)"

if (Test-Path $installerExe) {
    $exeInfo = Get-Item $installerExe
    if ($exeInfo.Length -lt $minArtifactBytes) {
        throw "Installer EXE is unexpectedly small ($($exeInfo.Length) bytes): $installerExe"
    }
    Write-Host "OK installer EXE: $($exeInfo.Name) ($([Math]::Round($exeInfo.Length / 1MB, 1)) MB)"
} elseif ($RequireInstaller) {
    throw "Required installer EXE was not produced: $installerExe"
} else {
    Write-Host "NOTE: Installer EXE ($installerExe) is not present (skipped when WiX Toolset is not installed locally)."
}

if (-not (Test-Path $checksumsFile)) {
    throw "SHA256SUMS.txt not found: $checksumsFile"
}
$checksumLines = Get-Content -Path $checksumsFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($checksumLines.Count -lt 1) {
    throw "SHA256SUMS.txt is empty"
}

$expectedChecksums = @{}
foreach ($line in $checksumLines) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') {
        $expectedChecksums[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant()
    } else {
        throw "Malformed line in SHA256SUMS.txt: $line"
    }
}

$filesToVerify = @($zipInfo)
if (Test-Path $installerExe) {
    $filesToVerify += (Get-Item $installerExe)
}

foreach ($artifact in $filesToVerify) {
    if (-not $expectedChecksums.ContainsKey($artifact.Name)) {
        throw "SHA256SUMS.txt is missing entry for $($artifact.Name)"
    }
    $actualHash = (Get-FileHash -Path $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedChecksums[$artifact.Name]) {
        throw "Checksum mismatch for $($artifact.Name): expected $($expectedChecksums[$artifact.Name]), got $actualHash"
    }
    Write-Host "OK checksum: $($artifact.Name) ($actualHash)"
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("tidyflow-verify-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    $extractDir = Join-Path $tempRoot "extracted"
    Expand-Archive -Path $portableZip -DestinationPath $extractDir -Force

    $launcher = Get-ChildItem -Path $extractDir -Filter "TidyFlow.exe" -Recurse | Select-Object -First 1
    if (-not $launcher) {
        throw "TidyFlow.exe not found inside extracted portable archive"
    }

    $reportFile = Join-Path $tempRoot "self-check-report.txt"
    Write-Host "==> Running packaged self-check: $($launcher.FullName) --self-check --out $reportFile"

    $proc = Start-Process -FilePath $launcher.FullName -ArgumentList @("--self-check", "--out", $reportFile) -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        $reportPreview = if (Test-Path $reportFile) { Get-Content -Path $reportFile -Raw } else { "<no report file generated>" }
        throw "Self-check process exited with code $($proc.ExitCode). Report:`n$reportPreview"
    }

    if (-not (Test-Path $reportFile)) {
        throw "Self-check exited with 0, but report file was not created: $reportFile"
    }

    $reportLines = Get-Content -Path $reportFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($rLine in $reportLines) {
        Write-Host "   $rLine"
    }

    $lastLine = if ($reportLines.Count -gt 0) { $reportLines[-1].Trim() } else { "" }
    if ($lastLine -ne "RESULT OK") {
        throw "Self-check did not finish with 'RESULT OK' (last line was '$lastLine')"
    }

    Write-Host "==> Package verification PASSED."
} finally {
    if (Test-Path $tempRoot) {
        Remove-Item -Path $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
