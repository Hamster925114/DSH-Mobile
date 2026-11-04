# Show concrete LAN IPv4 addresses for the DSH webserver.
# DSH rejects 0.0.0.0, so pick one of these addresses for webserver.host.
[CmdletBinding()]
param(
    [int]$Port = 53985,
    [string]$IpAddress = ''
)

$addresses = @(
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.AddressState -eq 'Preferred'
        } |
        Sort-Object InterfaceAlias, IPAddress
)

if ($addresses.Count -eq 0) {
    Write-Error 'No non-loopback IPv4 address found. Connect Wi-Fi/Ethernet first.'
    exit 1
}

function Test-PrivateIPv4([string]$Address) {
    $bytes = [System.Net.IPAddress]::Parse($Address).GetAddressBytes()
    if ($bytes[0] -eq 10) { return $true }
    if ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) { return $true }
    if ($bytes[0] -eq 192 -and $bytes[1] -eq 168) { return $true }
    return $false
}

Write-Host 'Available IPv4 addresses:' -ForegroundColor Cyan
$addresses | Select-Object InterfaceAlias, IPAddress, PrefixLength | Format-Table -AutoSize

$private = $addresses | Where-Object { Test-PrivateIPv4 $_.IPAddress } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($IpAddress)) {
    if ($null -ne $private) {
        $IpAddress = $private.IPAddress
        Write-Host "Using the first RFC1918 LAN address by default: $IpAddress" -ForegroundColor Yellow
    } else {
        $IpAddress = $addresses[0].IPAddress
        Write-Warning 'No RFC1918 LAN address found. 100.64.0.0/10 is CGNAT (Tailscale uses it too); a phone on the same Wi-Fi may not reach it. Use your actual LAN IP or Tailscale.'
        Write-Host "Using the first address by default: $IpAddress" -ForegroundColor Yellow
    }
}

$match = $addresses | Where-Object { $_.IPAddress -eq $IpAddress } | Select-Object -First 1
if ($null -eq $match) {
    Write-Error "'$IpAddress' is not one of the addresses above."
    exit 1
}

$base = "http://${IpAddress}:${Port}"
Write-Host ''
Write-Host 'Suggested profile patch:' -ForegroundColor Cyan
Write-Host @"
- id: webserver
  config:
    host: '$IpAddress'
    port: $Port

- id: web-runtime
  config:
    publicUrl: '$base'

- id: connection
  config:
    trustedHosts:
      - '${IpAddress}:${Port}'
"@

Write-Host ''
Write-Host "Desktop pairing page after restart: $base/mobile/pair" -ForegroundColor Green
Write-Host 'Keep this port on the private LAN; do not expose it to the internet.' -ForegroundColor Yellow
