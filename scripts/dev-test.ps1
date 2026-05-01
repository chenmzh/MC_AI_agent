param(
    [string] $BaseUrl = $(if ($env:MCAI_DEV_TEST_URL) { $env:MCAI_DEV_TEST_URL } else { "http://127.0.0.1:8790" }),
    [string] $Player = ""
)

$ErrorActionPreference = "Stop"

function Invoke-DevTestJson {
    param(
        [ValidateSet("GET", "POST")] [string] $Method,
        [string] $Path
    )

    $uri = "${BaseUrl}${Path}"
    if ($Player -and $Path -notmatch "\?") {
        $uri = "${uri}?player=$([uri]::EscapeDataString($Player))"
    } elseif ($Player) {
        $uri = "${uri}&player=$([uri]::EscapeDataString($Player))"
    }

    Write-Host "== $Method $uri =="
    if ($Method -eq "GET") {
        Invoke-RestMethod -Method Get -Uri $uri -TimeoutSec 35 | ConvertTo-Json -Depth 12
    } else {
        Invoke-RestMethod -Method Post -Uri $uri -Body "{}" -ContentType "application/json" -TimeoutSec 35 | ConvertTo-Json -Depth 12
    }
}

Invoke-DevTestJson -Method GET -Path "/health"
Invoke-DevTestJson -Method GET -Path "/state"
Invoke-DevTestJson -Method GET -Path "/observation"
Invoke-DevTestJson -Method GET -Path "/skills"
Invoke-DevTestJson -Method POST -Path "/test/chest"
Invoke-DevTestJson -Method POST -Path "/test/all"
