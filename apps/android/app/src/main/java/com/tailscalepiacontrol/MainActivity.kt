package com.tailscalepiacontrol

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tailscalepiacontrol.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var regions: List<RegionInfo> = emptyList()
    private var vpnUpdateInProgress = false
    private var suppressRegionChange = true
    private var exitNodePollJob: Job? = null
    private var exitNodeAnnounceDone = false
    private var activeRegionId: String? = null
    private var regionAdapter: ArrayAdapter<String>? = null
    private var cachedRegionIds: List<String>? = null

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) return@registerForActivityResult
        applyPairingPayload(result.contents)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            toast("Camera permission is required to scan pairing QR codes")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        AppLogger.info("MainActivity", "App started")

        prefs.controllerUrl?.let { binding.controllerUrlInput.setText(it) }

        binding.checkConnectionButton.setOnClickListener { checkConnection() }
        binding.scanQrButton.setOnClickListener { startQrScan() }
        binding.registerButton.setOnClickListener { registerDevice() }
        binding.refreshButton.setOnClickListener { refreshStatus() }
        binding.openTailscaleButton.setOnClickListener { TailscaleHelper.openTailscaleApp(this) }
        binding.testIpButton.setOnClickListener { testIpAndLocation() }
        binding.exportLogsButton.setOnClickListener { exportLogs() }
        binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked -> onVpnToggled(isChecked) }
        binding.regionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressRegionChange || !binding.vpnSwitch.isChecked || vpnUpdateInProgress) return
                if (position < 0 || position >= regions.size) return
                val regionId = regions[position].id
                if (regionId == activeRegionId) return
                switchRegion(regionId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        if (prefs.isRegistered) {
            enableControls()
        } else if (!prefs.controllerUrl.isNullOrBlank()) {
            checkConnection()
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.info("MainActivity", "onResume registered=${prefs.isRegistered}")
        if (prefs.isRegistered) {
            refreshStatus()
        }
    }

    private fun exportLogs() {
        try {
            val saved = LogExporter.saveToDownloads(this)
            AppLogger.info("MainActivity", "Exported logs to Downloads/$saved")
            toast(getString(R.string.export_logs_saved, saved))
            LogExporter.shareLogs(this)
        } catch (error: Exception) {
            AppLogger.error("MainActivity", "Failed to export logs", error)
            toast(getString(R.string.export_logs_failed, error.message ?: "unknown error"))
        }
    }

    private fun startQrScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchQrScanner()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        scanQrLauncher.launch(
            ScanOptions().apply {
                setPrompt("Scan the pairing QR code from the controller dashboard")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun applyPairingPayload(raw: String) {
        try {
            val json = JsonParser.parseString(raw.trim()).asJsonObject
            val url = json.get("url")?.asString?.trim().orEmpty()
            val code = json.get("code")?.asString?.trim().orEmpty()

            if (url.isNotBlank()) {
                binding.controllerUrlInput.setText(url)
                prefs.controllerUrl = url
            }
            if (code.isNotBlank()) {
                binding.pairingCodeInput.setText(code.uppercase())
            }
            toast("Pairing details loaded from QR code")
            if (url.isNotBlank()) {
                checkConnection()
            }
        } catch (_: Exception) {
            toast("Unrecognized QR code format")
        }
    }

    private fun checkConnection() {
        val baseUrl = binding.controllerUrlInput.text?.toString()?.trim().orEmpty()
        if (baseUrl.isBlank()) {
            toast("Enter the controller URL first")
            return
        }

        lifecycleScope.launch {
            try {
                val pairing = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl).getPairingInfo()
                }
                prefs.controllerUrl = baseUrl
                updatePairingUi(pairing)
                toast("Connected to controller")
            } catch (error: Exception) {
                binding.pairingStatusText.text = "Could not reach controller: ${error.message}"
                binding.pairingCodeLayout.visibility = View.GONE
                toast("Connection failed: ${error.message}")
            }
        }
    }

    private fun updatePairingUi(pairing: PairingInfoResponse) {
        binding.pairingStatusText.text = pairing.instructions
        if (pairing.required) {
            binding.pairingCodeLayout.visibility = View.VISIBLE
            val code = pairing.pairing_code?.uppercase()
            if (!code.isNullOrBlank() && binding.pairingCodeInput.text.isNullOrBlank()) {
                binding.pairingCodeInput.setText(code)
            }
        } else {
            binding.pairingCodeLayout.visibility = View.GONE
            binding.pairingCodeInput.setText("")
        }
    }

    private fun registerDevice() {
        val baseUrl = binding.controllerUrlInput.text?.toString()?.trim().orEmpty()
        val name = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        val pairingCode = binding.pairingCodeInput.text?.toString()?.trim().orEmpty().ifBlank { null }

        if (baseUrl.isBlank() || name.isBlank()) {
            toast("Controller URL and device name are required")
            return
        }

        lifecycleScope.launch {
            try {
                val pairing = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl).getPairingInfo()
                }
                updatePairingUi(pairing)
                if (pairing.required && pairingCode.isNullOrBlank()) {
                    toast("Pairing code required. Scan the QR code or enter the 6-character code from the dashboard.")
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl).register(name, "android", null, pairingCode)
                }
                prefs.controllerUrl = baseUrl
                prefs.apiToken = response.api_token
                prefs.deviceId = response.device_id
                toast("Registered as ${response.name}")
                enableControls()
                refreshStatus()
            } catch (error: Exception) {
                toast("Registration failed: ${error.message}")
            }
        }
    }

    private suspend fun loadRegions(): Boolean {
        val baseUrl = prefs.controllerUrl ?: return false
        val token = prefs.apiToken ?: return false

        return try {
            val response = withContext(Dispatchers.IO) {
                ControllerClient(baseUrl, token).listRegions()
            }
            regions = response.regions
            val labels = regions.map { "${it.display_name} (${it.hostname})" }
            val regionIds = regions.map { it.id }

            suppressRegionChange = true
            if (cachedRegionIds != regionIds) {
                cachedRegionIds = regionIds
                regionAdapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels
                )
                binding.regionSpinner.adapter = regionAdapter
            }
            selectRegionInSpinner(activeRegionId)
            binding.regionSpinner.post { suppressRegionChange = false }
            true
        } catch (error: Exception) {
            if (!handleApiError(error)) {
                toast("Failed to load regions: ${error.message}")
            }
            false
        }
    }

    private fun refreshStatus() {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        lifecycleScope.launch {
            binding.refreshButton.isEnabled = false
            try {
                loadRegions()

                val status = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl, token).getVpnStatus()
                }
                applyStatusToUi(status)
                reconcileExitNode(status)
            } catch (error: Exception) {
                if (!handleApiError(error)) {
                    if (prefs.lastAppliedExitNode != null) {
                        clearExitNode(getString(R.string.controller_unreachable_cleared_exit))
                    }
                    toast("Status refresh failed: ${error.message}")
                }
            } finally {
                if (prefs.isRegistered) {
                    binding.refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun switchRegion(regionId: String) {
        if (vpnUpdateInProgress) return
        if (regionId == activeRegionId) {
            AppLogger.info("MainActivity", "switchRegion ignored — already on $regionId")
            return
        }
        AppLogger.info("MainActivity", "switchRegion regionId=$regionId")
        if (!ensureTailscaleConnected()) {
            selectRegionInSpinner(activeRegionId)
            return
        }
        updateVpnState(enabled = true, regionId = regionId, clearExitNodeFirst = true)
    }

    private fun onVpnToggled(enabled: Boolean) {
        AppLogger.info("MainActivity", "onVpnToggled enabled=$enabled")
        if (vpnUpdateInProgress) {
            setVpnSwitchChecked(!enabled)
            return
        }

        if (enabled && !ensureTailscaleConnected()) {
            setVpnSwitchChecked(false)
            return
        }

        val region = if (enabled) {
            val index = binding.regionSpinner.selectedItemPosition
            if (index < 0 || index >= regions.size) {
                setVpnSwitchChecked(false)
                toast("Select a region first")
                return
            }
            regions[index].id
        } else {
            null
        }

        updateVpnState(enabled = enabled, regionId = region, clearExitNodeFirst = enabled)
    }

    private fun updateVpnState(enabled: Boolean, regionId: String?, clearExitNodeFirst: Boolean) {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        if (enabled && !ensureTailscaleConnected()) {
            setVpnSwitchChecked(false)
            return
        }

        if (clearExitNodeFirst) {
            clearExitNode()
        }

        vpnUpdateInProgress = true
        binding.vpnSwitch.isEnabled = false
        binding.regionSpinner.isEnabled = false

        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl, token).updateVpn(enabled, regionId)
                }

                if (!enabled) {
                    activeRegionId = null
                    clearExitNode()
                    exitNodePollJob?.cancel()
                } else {
                    activeRegionId = status.region
                    updateStatusText(status)
                    selectRegionInSpinner(status.region)
                    reconcileExitNode(status)
                    scheduleExitNodePolling()
                }
            } catch (error: Exception) {
                setVpnSwitchChecked(!enabled)
                selectRegionInSpinner(activeRegionId)
                if (!handleApiError(error)) {
                    toast("VPN update failed: ${error.message}")
                }
            } finally {
                vpnUpdateInProgress = false
                if (prefs.isRegistered) {
                    binding.vpnSwitch.isEnabled = true
                    binding.regionSpinner.isEnabled = true
                }
            }
        }
    }

    private fun applyStatusToUi(status: VpnStatusResponse) {
        activeRegionId = status.region
        setVpnSwitchChecked(status.enabled)
        selectRegionInSpinner(status.region)
        updateStatusText(status)
    }

    private fun selectRegionInSpinner(regionId: String?) {
        if (regionId.isNullOrBlank()) return
        val index = regions.indexOfFirst { it.id == regionId }
        if (index < 0) return
        suppressRegionChange = true
        binding.regionSpinner.setSelection(index)
        binding.regionSpinner.post { suppressRegionChange = false }
    }

    private fun updateStatusText(status: VpnStatusResponse) {
        val message = buildString {
            append("Device: ${status.device_id}\n")
            append("Enabled: ${status.enabled}\n")
            append("Region: ${status.region ?: "-"}\n")
            append("Exit node: ${status.exit_node_hostname ?: "-"}\n")
            append("Stack: ${status.stack_status ?: "-"}\n")
            status.message?.let { append("\n$it") }
        }
        binding.statusText.text = message
    }

    private fun ensureTailscaleConnected(): Boolean {
        if (TailscaleHelper.isConnected(this)) return true

        if (!TailscaleHelper.isInstalled(this)) {
            toast(getString(R.string.tailscale_not_installed))
        } else {
            toast(getString(R.string.tailscale_not_connected))
        }
        TailscaleHelper.openTailscaleApp(this)
        return false
    }

    private fun clearExitNode(message: String? = null) {
        if (prefs.lastAppliedExitNode != null) {
            TailscaleHelper.setExitNode(this, null, true)
            prefs.lastAppliedExitNode = null
            exitNodeAnnounceDone = false
            message?.let { toast(it) }
        }
    }

    private fun reconcileExitNode(status: VpnStatusResponse) {
        AppLogger.info(
            "MainActivity",
            "reconcileExitNode enabled=${status.enabled} stack=${status.stack_status} exit=${status.exit_node_hostname ?: "-"} lastApplied=${prefs.lastAppliedExitNode ?: "-"}"
        )
        val shouldUseExitNode = status.enabled &&
            status.stack_status == "running" &&
            !status.exit_node_hostname.isNullOrBlank()

        if (!shouldUseExitNode) {
            if (prefs.lastAppliedExitNode != null && status.stack_status != "starting") {
                val message = when {
                    !status.enabled -> getString(R.string.vpn_disabled_cleared_exit)
                    status.stack_status == "error" -> getString(R.string.stack_failed_cleared_exit)
                    else -> getString(R.string.stack_unavailable_cleared_exit)
                }
                clearExitNode(message)
            }
            return
        }

        if (!TailscaleHelper.isConnected(this)) return
        applyExitNodeWhenReady(status)
    }

    private fun applyExitNodeWhenReady(status: VpnStatusResponse) {
        if (!status.enabled || status.stack_status != "running") return
        val hostname = status.exit_node_hostname ?: return
        if (prefs.lastAppliedExitNode == hostname) return

        TailscaleHelper.setExitNode(this, hostname, status.allow_lan_access)
        prefs.lastAppliedExitNode = hostname

        if (!exitNodeAnnounceDone) {
            exitNodeAnnounceDone = true
            toast("Exit node set to $hostname")
        }
    }

    private fun scheduleExitNodePolling() {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        exitNodePollJob?.cancel()
        exitNodePollJob = lifecycleScope.launch {
            repeat(36) {
                delay(5000)
                try {
                    val status = withContext(Dispatchers.IO) {
                        ControllerClient(baseUrl, token).getVpnStatus()
                    }
                    if (!status.enabled) {
                        activeRegionId = null
                        reconcileExitNode(status)
                        return@launch
                    }

                    activeRegionId = status.region
                    updateStatusText(status)
                    reconcileExitNode(status)

                    if (status.stack_status == "running" && prefs.lastAppliedExitNode == status.exit_node_hostname) {
                        return@launch
                    }
                    if (status.stack_status == "error") {
                        toast("Regional stack failed to start")
                        return@launch
                    }
                } catch (error: Exception) {
                    if (handleApiError(error)) return@launch
                    if (prefs.lastAppliedExitNode != null) {
                        clearExitNode(getString(R.string.controller_unreachable_cleared_exit))
                    }
                    return@launch
                }
            }
        }
    }

    private fun setVpnSwitchChecked(checked: Boolean) {
        binding.vpnSwitch.setOnCheckedChangeListener(null)
        binding.vpnSwitch.isChecked = checked
        binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked -> onVpnToggled(isChecked) }
    }

    private fun testIpAndLocation() {
        lifecycleScope.launch {
            try {
                binding.testIpButton.isEnabled = false
                val result = withContext(Dispatchers.IO) { IpLookup.fetch() }
                binding.statusText.text = IpLookup.format(result)
                toast("IP lookup complete")
            } catch (error: Exception) {
                binding.statusText.text = "IP lookup failed: ${error.message}"
                toast("IP lookup failed: ${error.message}")
            } finally {
                binding.testIpButton.isEnabled = true
            }
        }
    }

    private fun handleApiError(error: Exception): Boolean {
        if (error.message?.contains("HTTP 401") == true) {
            resetRegistration()
            toast("Device removed from controller — please register again")
            return true
        }
        return false
    }

    private fun resetRegistration() {
        exitNodePollJob?.cancel()
        clearExitNode()
        activeRegionId = null
        cachedRegionIds = null
        regionAdapter = null
        prefs.clearRegistration()
        binding.vpnSwitch.isEnabled = false
        setVpnSwitchChecked(false)
        binding.regionSpinner.isEnabled = false
        binding.refreshButton.isEnabled = false
        binding.registerButton.visibility = View.VISIBLE
        binding.checkConnectionButton.visibility = View.VISIBLE
        binding.scanQrButton.visibility = View.VISIBLE
        binding.deviceNameInput.isEnabled = true
        binding.pairingCodeInput.isEnabled = true
        binding.controllerUrlInput.isEnabled = true
        binding.statusText.text = getString(R.string.status_idle)
    }

    private fun enableControls() {
        binding.vpnSwitch.isEnabled = true
        binding.regionSpinner.isEnabled = true
        binding.refreshButton.isEnabled = true
        binding.registerButton.visibility = View.GONE
        binding.checkConnectionButton.visibility = View.GONE
        binding.scanQrButton.visibility = View.GONE
        binding.deviceNameInput.isEnabled = false
        binding.pairingCodeInput.isEnabled = false
        binding.controllerUrlInput.isEnabled = false
    }

    private fun toast(message: String) {
        AppLogger.info("UI", message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
