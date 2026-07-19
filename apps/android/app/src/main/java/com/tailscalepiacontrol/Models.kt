package com.tailscalepiacontrol

data class RegionInfo(
    val id: String,
    val display_name: String,
    val hostname: String,
    val stack_status: String
)

data class RegionListResponse(
    val regions: List<RegionInfo>
)

data class DeviceRegisterResponse(
    val device_id: String,
    val api_token: String,
    val name: String
)

data class VpnStatusResponse(
    val device_id: String,
    val enabled: Boolean,
    val region: String?,
    val exit_node_hostname: String?,
    val allow_lan_access: Boolean,
    val stack_status: String?,
    val public_ip: String?,
    val message: String?
)

data class VpnUpdateRequest(
    val enabled: Boolean,
    val region: String?
)
