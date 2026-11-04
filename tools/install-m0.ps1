#Requires -Version 5.1
<#
.SYNOPSIS
  Install @dsh-mobile/bridge into the DSH desktop profile for M0.

.DESCRIPTION
  - backs up cordis.patch.yml and package.json
  - runs `corepack pnpm add file:<repo>/packages/bridge`
  - appends (or updates) the bridge insert block in cordis.patch.yml
  - does NOT restart DSH

.PARAMETER Profile
  DSH profile directory. Defaults to $HOME\.dsh\profiles\desktop.

.PARAMETER PublicUrl
  Public base URL written into the bridge config, for example
  https://machine.tailnet.ts.net or https://dsh.example.com.
  Empty means "use the local webserver origin" (loopback only).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tools\install-m0.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\tools\install-m0.ps1 -PublicUrl "https://machine.tailnet.ts.net"
#>
[CmdletBinding()]
param(
    [string]$Profile = (Join-Path $HOME '.dsh\profiles\desktop'),
    [string]$PublicUrl = ''
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$bridge = Join-Path $repo 'packages\bridge'
$patchPath = Join-Path $Profile 'cordis.patch.yml'
$packagePath = Join-Path $Profile 'package.json'

if (-not (Test-Path (Join-Path $bridge 'package.json'))) {
    throw "bridge package not found: $bridge"
}
if (-not (Test-Path $patchPath)) {
    throw "DSH profile not found (cordis.patch.yml missing): $Profile"
}
if (-not (Test-Path $packagePath)) {
    throw "DSH profile not found (package.json missing): $Profile"
}

$stamp = Get-Date -Format 'yyyyMMddHHmmss'
Copy-Item -LiteralPath $patchPath -Destination "$patchPath.bak-$stamp"
Copy-Item -LiteralPath $packagePath -Destination "$packagePath.bak-$stamp"
Write-Host "[ok] backups: $patchPath.bak-$stamp"

Push-Location $Profile
try {
    $spec = 'file:' + ($bridge -replace '\\', '/')
    Write-Host "[..] corepack pnpm add $spec"
    & corepack pnpm add $spec
    if ($LASTEXITCODE -ne 0) {
        throw "pnpm add failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
Write-Host "[ok] @dsh-mobile/bridge installed in $Profile"

$patch = [System.IO.File]::ReadAllText($patchPath)
$escaped = $PublicUrl.Replace("'", "''")

if ($patch -notmatch 'dsh-mobile-bridge') {
    $block = @"

# DSH Mobile bridge (M0)
- insert:
    - id: dsh-mobile-bridge
      name: '@dsh-mobile/bridge'
      config:
        publicUrl: '$escaped'
        pairingTtlSeconds: 120
"@
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText($patchPath, $block, $utf8)
    Write-Host "[ok] bridge insert block added to cordis.patch.yml"
}
elseif ($PublicUrl -ne '') {
    $pattern = "(?s)(- id: dsh-mobile-bridge.*?publicUrl:\s*)'[^']*'"
    $replacement = "`$1'$escaped'"
    $updated = [regex]::Replace($patch, $pattern, $replacement, 1)
    if ($updated -eq $patch) {
        Write-Warning "bridge block exists but publicUrl was not updated; edit $patchPath manually."
    }
    else {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($patchPath, $updated, $utf8)
        Write-Host "[ok] publicUrl updated to $PublicUrl"
    }
}
else {
    Write-Host "[ok] bridge insert block already present; publicUrl unchanged"
}

Write-Host ''
Write-Host 'Next:'
Write-Host '  1. Fully quit and restart DSH (tray icon too).'
Write-Host '  2. Get-Content $HOME\.dsh\mobile\pairing.json'
Write-Host '  3. node .\tools\smoke-auth.mjs'
