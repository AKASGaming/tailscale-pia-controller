"""Pydantic request/response schemas."""

from datetime import datetime

from pydantic import BaseModel, Field


class DeviceRegisterRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=128)
    platform: str = Field(default="unknown", max_length=32)
    tailscale_ip: str | None = None
    pairing_secret: str | None = None
    pairing_code: str | None = None


class DeviceRegisterResponse(BaseModel):
    device_id: str
    api_token: str
    name: str


class VpnUpdateRequest(BaseModel):
    enabled: bool
    region: str | None = None


class VpnStatusResponse(BaseModel):
    device_id: str
    enabled: bool
    region: str | None = None
    exit_node_hostname: str | None = None
    allow_lan_access: bool = True
    stack_status: str | None = None
    public_ip: str | None = None
    message: str | None = None


class RegionInfo(BaseModel):
    id: str
    display_name: str
    server_region: str
    hostname: str
    stack_status: str


class RegionListResponse(BaseModel):
    regions: list[RegionInfo]


class HealthResponse(BaseModel):
    status: str
    version: str = "1.0.0"
    active_stacks: int
    registered_devices: int


class PairingInfoResponse(BaseModel):
    required: bool
    instructions: str
    secret: str | None = None
    pairing_code: str | None = None
    pairing_code_expires_at: str | None = None


class DeviceSummary(BaseModel):
    id: str
    name: str
    platform: str
    created_at: str
    vpn_enabled: bool
    region: str | None = None
    region_display_name: str | None = None
    exit_node_hostname: str | None = None
    stack_status: str | None = None


class DeviceListResponse(BaseModel):
    devices: list[DeviceSummary]


class DashboardStateResponse(BaseModel):
    active_stacks: int
    registered_devices: int
    regions: list[RegionInfo]
    devices: list[DeviceSummary]
    pairing_required: bool
    pairing_code: str | None = None
    pairing_code_expires_at: str | None = None
