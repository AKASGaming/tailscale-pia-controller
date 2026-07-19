#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Tailscale PIA Controller client for Windows.

.EXAMPLE
    ./vpn-control.ps1 register -ControllerUrl http://192.168.1.10:8090 -Name "My PC"

.EXAMPLE
    ./vpn-control.ps1 regions

.EXAMPLE
    ./vpn-control.ps1 enable -Region mexico

.EXAMPLE
    ./vpn-control.ps1 disable
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("register", "regions", "status", "enable", "disable")]
    [string]$Command = "status",

    [string]$ControllerUrl,
    [string]$Name = $env:COMPUTERNAME,
    [string]$Region,
    [string]$PairingSecret,
    [string]$ConfigPath = "$PSScriptRoot/.vpn-control.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Config {
    if (-not (Test-Path $ConfigPath)) {
        return @{ controller_url = $null; api_token = $null; device_id = $null }
    }
    return Get-Content $ConfigPath -Raw | ConvertFrom-Json -AsHashtable
}

function Save-Config($Config) {
    $Config | ConvertTo-Json | Set-Content -Path $ConfigPath -Encoding UTF8
}

function Invoke-Controller {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token
    )

    $config = Get-Config
    $baseUrl = if ($ControllerUrl) { $ControllerUrl.TrimEnd("/") } else { $config.controller_url }
    if (-not $baseUrl) { throw "Controller URL is required. Pass -ControllerUrl or register first." }

    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }

    $params = @{
        Uri = "$baseUrl$Path"
        Method = $Method
        Headers = $headers
    }
    if ($Body) {
        $params.Body = ($Body | ConvertTo-Json)
        $params.ContentType = "application/json"
    }

    return Invoke-RestMethod @params
}

function Set-TailscaleExitNode {
    param([string]$Hostname)

    if (-not (Get-Command tailscale -ErrorAction SilentlyContinue)) {
        Write-Warning "tailscale CLI not found. Install Tailscale for Windows and enable CLI, then run manually:"
        if ($Hostname) {
            Write-Warning "  tailscale set --exit-node=$Hostname --exit-node-allow-lan-access=true"
        } else {
            Write-Warning "  tailscale set --exit-node="
        }
        return
    }

    if ($Hostname) {
        & tailscale set --exit-node=$Hostname --exit-node-allow-lan-access=true
    } else {
        & tailscale set --exit-node=
    }
}

switch ($Command) {
    "register" {
        if (-not $ControllerUrl) { throw "-ControllerUrl is required for register" }
        $body = @{ name = $Name; platform = "windows"; pairing_secret = $PairingSecret }
        $response = Invoke-Controller -Method POST -Path "/devices/register" -Body $body
        Save-Config @{
            controller_url = $ControllerUrl.TrimEnd("/")
            api_token = $response.api_token
            device_id = $response.device_id
        }
        Write-Host "Registered device $($response.name) ($($response.device_id))"
    }

    "regions" {
        $config = Get-Config
        $response = Invoke-Controller -Method GET -Path "/regions" -Token $config.api_token
        $response.regions | Format-Table id, display_name, hostname, stack_status
    }

    "status" {
        $config = Get-Config
        if (-not $config.api_token) { throw "Not registered. Run: ./vpn-control.ps1 register -ControllerUrl <url>" }
        $response = Invoke-Controller -Method GET -Path "/devices/me/vpn" -Token $config.api_token
        $response | Format-List
    }

    "enable" {
        if (-not $Region) { throw "-Region is required for enable" }
        $config = Get-Config
        if (-not $config.api_token) { throw "Not registered. Run register first." }
        $response = Invoke-Controller -Method PUT -Path "/devices/me/vpn" -Body @{ enabled = $true; region = $Region } -Token $config.api_token
        $response | Format-List
        if ($response.exit_node_hostname -and $response.stack_status -eq "running") {
            Set-TailscaleExitNode -Hostname $response.exit_node_hostname
        } else {
            Write-Host "Stack is starting. Run './vpn-control.ps1 status' in 30 seconds, then enable exit node if needed."
        }
    }

    "disable" {
        $config = Get-Config
        if (-not $config.api_token) { throw "Not registered. Run register first." }
        $response = Invoke-Controller -Method PUT -Path "/devices/me/vpn" -Body @{ enabled = $false; region = $null } -Token $config.api_token
        $response | Format-List
        Set-TailscaleExitNode -Hostname $null
    }
}
