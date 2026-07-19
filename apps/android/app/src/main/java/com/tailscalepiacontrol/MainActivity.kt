package com.tailscalepiacontrol

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tailscalepiacontrol.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var regions: List<RegionInfo> = emptyList()
    private var vpnUpdateInProgress = false
    private var suppressRegionChange = true
    private var exitNodePollJob: Job? = null
    private var statusPollJob: Job? = null
    private var statusPollTick = 0
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(getString(R.string.notifications_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        applySystemBarInsets()
        prefs = Prefs(this)
        AppLogger.info("MainActivity", "App started")
        NotificationHelper.createChannels(this)

        restoreSetupFields()

        setSupportActionBar(binding.toolbar)
        binding.versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        binding.checkConnectionButton.setOnClickListener { checkConnection() }
        binding.scanQrButton.setOnClickListener { startQrScan() }
        binding.registerButton.setOnClickListener { onRegisterClicked() }
        binding.saveNameButton.setOnClickListener { saveDeviceName() }
        binding.resetDataButton.setOnClickListener { confirmResetAllData() }
        binding.setupToggle.setOnClickListener { toggleSetupSection() }
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

        updateRegistrationUi()
        if (prefs.isRegistered) {
            ensureNotificationPermission()
            enableVpnControls()
            refreshStatus()
        } else {
            showIdleStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isInForeground = true
        if (prefs.isRegistered) {
            VpnMonitorService.stop(this)
            startStatusPolling()
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.info("MainActivity", "onResume registered=${prefs.isRegistered}")
        if (prefs.isRegistered) {
            refreshStatus()
        }
    }

    override fun onStop() {
        AppVisibility.isInForeground = false
        stopStatusPolling()
        if (prefs.isRegistered) {
            VpnMonitorService.start(this)
        }
        super.onStop()
    }

    override fun onPause() {
        persistSetupFields()
        super.onPause()
    }

    override fun onDestroy() {
        stopStatusPolling()
        super.onDestroy()
    }

    companion object {
        private const val STATUS_POLL_INTERVAL_MS = 3_000L
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.mainScrollView.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.rootLayout)
    }

    private fun restoreSetupFields() {
        binding.controllerUrlInput.setText(prefs.controllerUrl.orEmpty())
        binding.deviceNameInput.setText(
            prefs.deviceName?.takeIf { it.isNotBlank() } ?: getString(R.string.device_name_default)
        )
    }

    private fun persistSetupFields() {
        val url = binding.controllerUrlInput.text?.toString()?.trim().orEmpty()
        val name = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        if (url.isNotBlank()) {
            prefs.controllerUrl = url
        }
        if (name.isNotBlank()) {
            prefs.deviceName = name
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
            }
            if (code.isNotBlank()) {
                binding.pairingCodeInput.setText(code.uppercase())
            }
            persistSetupFields()
            toast(getString(R.string.qr_loaded))
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

    private fun onRegisterClicked() {
        if (prefs.isRegistered) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reregister_title)
                .setMessage(R.string.reregister_message)
                .setPositiveButton(R.string.reregister) { _, _ -> registerDevice(forceNew = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            registerDevice(forceNew = false)
        }
    }

    private fun registerDevice(forceNew: Boolean) {
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

                if (forceNew) {
                    prefs.clearRegistration()
                }

                val response = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl).register(name, "android", null, pairingCode)
                }
                prefs.controllerUrl = baseUrl
                prefs.deviceName = name
                prefs.apiToken = response.api_token
                prefs.deviceId = response.device_id
                binding.deviceNameInput.setText(response.name)
                toast("Registered as ${response.name}")
                updateRegistrationUi()
                ensureNotificationPermission()
                enableVpnControls()
                refreshStatus()
            } catch (error: Exception) {
                toast("Registration failed: ${error.message}")
            }
        }
    }

    private fun saveDeviceName() {
        val baseUrl = prefs.controllerUrl
        val token = prefs.apiToken
        val name = binding.deviceNameInput.text?.toString()?.trim().orEmpty()

        if (!prefs.isRegistered || baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            toast("Register the device before saving the name")
            return
        }
        if (name.isBlank()) {
            toast("Device name is required")
            return
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl, token).updateDeviceName(name)
                }
                prefs.deviceName = response.name
                binding.deviceNameInput.setText(response.name)
                toast(getString(R.string.device_name_saved))
            } catch (error: Exception) {
                if (!handleApiError(error)) {
                    toast("Failed to save device name: ${error.message}")
                }
            }
        }
    }

    private fun confirmResetAllData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_all_data_title)
            .setMessage(R.string.reset_all_data_message)
            .setPositiveButton(R.string.reset_all_data_confirm) { _, _ -> resetAllData() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetAllData() {
        exitNodePollJob?.cancel()
        stopStatusPolling()
        VpnMonitorService.stop(this)
        clearExitNode()
        activeRegionId = null
        cachedRegionIds = null
        regionAdapter = null
        prefs.clearAll()
        binding.controllerUrlInput.setText("")
        binding.deviceNameInput.setText(getString(R.string.device_name_default))
        binding.pairingCodeInput.setText("")
        binding.pairingCodeLayout.visibility = View.GONE
        binding.pairingStatusText.text = getString(R.string.pairing_status_default)
        binding.vpnSwitch.isEnabled = false
        setVpnSwitchChecked(false)
        binding.regionSpinner.isEnabled = false
        binding.refreshButton.isEnabled = false
        showIdleStatus()
        updateRegistrationUi()
        toast(getString(R.string.reset_all_data_done))
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
                    R.layout.item_spinner,
                    R.id.spinnerText,
                    labels,
                ).apply {
                    setDropDownViewResource(R.layout.item_spinner_dropdown)
                }
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
        lifecycleScope.launch {
            binding.refreshButton.isEnabled = false
            try {
                syncStatusFromController(notifyRemoteChanges = false, showErrors = true)
            } finally {
                if (prefs.isRegistered) {
                    binding.refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun startStatusPolling() {
        if (!prefs.isRegistered) return
        if (statusPollJob?.isActive == true) return

        statusPollJob = lifecycleScope.launch {
            while (isActive) {
                if (!prefs.isRegistered) break
                syncStatusFromController(notifyRemoteChanges = true, showErrors = false)
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
        statusPollTick = 0
    }

    private suspend fun syncStatusFromController(notifyRemoteChanges: Boolean, showErrors: Boolean) {
        if (vpnUpdateInProgress) return

        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        try {
            if (statusPollTick++ % 5 == 0) {
                loadRegions()
            }

            val status = withContext(Dispatchers.IO) {
                ControllerClient(baseUrl, token).getVpnStatus()
            }
            if (vpnUpdateInProgress) return

            val wasEnabled = binding.vpnSwitch.isChecked
            val wasRegion = activeRegionId
            applyStatusToUi(status)
            reconcileExitNode(status)

            if (!status.enabled) {
                exitNodePollJob?.cancel()
            } else if (status.stack_status == "starting" && exitNodePollJob?.isActive != true) {
                scheduleExitNodePolling()
            }

            if (notifyRemoteChanges) {
                when {
                    wasEnabled && !status.enabled ->
                        toast(getString(R.string.vpn_disabled_remotely))
                    !wasEnabled && status.enabled ->
                        toast(getString(R.string.vpn_enabled_remotely))
                    wasEnabled && status.enabled && wasRegion != status.region ->
                        toast(getString(R.string.vpn_enabled_remotely))
                }
            }
            VpnRemoteSync.updateLastSynced(prefs, status)
        } catch (error: Exception) {
            if (!handleApiError(error) && showErrors) {
                if (prefs.lastAppliedExitNode != null) {
                    clearExitNode(getString(R.string.controller_unreachable_cleared_exit))
                }
                toast("Status refresh failed: ${error.message}")
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
                    updateStatusHero(status)
                } else {
                    activeRegionId = status.region
                    updateStatusHero(status)
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
        updateStatusHero(status)
    }

    private fun selectRegionInSpinner(regionId: String?) {
        if (regionId.isNullOrBlank()) return
        val index = regions.indexOfFirst { it.id == regionId }
        if (index < 0) return
        suppressRegionChange = true
        binding.regionSpinner.setSelection(index)
        binding.regionSpinner.post { suppressRegionChange = false }
    }

    private fun updateStatusHero(status: VpnStatusResponse?) {
        if (status == null || !status.enabled) {
            binding.statusTitle.text = getString(R.string.vpn_off_title)
            binding.statusSubtitle.text = getString(R.string.vpn_off_subtitle)
            binding.statusHeroCard.setCardBackgroundColor(getColor(R.color.hero_disconnected))
            setStatusDotColor(R.color.status_disconnected)
            binding.statusDetails.visibility = View.GONE
            return
        }

        when (status.stack_status) {
            "running" -> {
                binding.statusTitle.text = getString(R.string.vpn_on_title)
                val regionLabel = regions.find { it.id == status.region }?.display_name ?: status.region ?: "-"
                binding.statusSubtitle.text = "$regionLabel · ${status.exit_node_hostname ?: "exit node pending"}"
                binding.statusHeroCard.setCardBackgroundColor(getColor(R.color.hero_connected))
                setStatusDotColor(R.color.success)
            }
            "starting" -> {
                binding.statusTitle.text = getString(R.string.vpn_starting_title)
                binding.statusSubtitle.text = status.message ?: getString(R.string.vpn_starting_subtitle)
                binding.statusHeroCard.setCardBackgroundColor(getColor(R.color.hero_starting))
                setStatusDotColor(R.color.warning)
            }
            "error" -> {
                binding.statusTitle.text = getString(R.string.vpn_error_title)
                binding.statusSubtitle.text = status.message ?: getString(R.string.vpn_error_subtitle)
                binding.statusHeroCard.setCardBackgroundColor(getColor(R.color.surface_container_high))
                setStatusDotColor(R.color.error)
            }
            else -> {
                binding.statusTitle.text = getString(R.string.vpn_on_title)
                binding.statusSubtitle.text = status.region ?: getString(R.string.vpn_starting_subtitle)
                binding.statusHeroCard.setCardBackgroundColor(getColor(R.color.hero_disconnected))
                setStatusDotColor(R.color.status_disconnected)
            }
        }

        val details = buildString {
            append("Device ${status.device_id.take(8)}…\n")
            append("Stack: ${status.stack_status ?: "-"}\n")
            append("Exit node: ${status.exit_node_hostname ?: "-"}")
            status.message?.let { append("\n$it") }
        }
        binding.statusDetails.text = details
        binding.statusDetails.visibility = View.VISIBLE
    }

    private fun showIdleStatus() {
        updateStatusHero(null)
    }

    private fun setStatusDotColor(colorRes: Int) {
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
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
                        applyStatusToUi(status)
                        reconcileExitNode(status)
                        return@launch
                    }

                    activeRegionId = status.region
                    updateStatusHero(status)
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
                binding.statusDetails.text = IpLookup.format(result)
                binding.statusDetails.visibility = View.VISIBLE
                toast("IP lookup complete")
            } catch (error: Exception) {
                binding.statusDetails.text = "IP lookup failed: ${error.message}"
                binding.statusDetails.visibility = View.VISIBLE
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
        stopStatusPolling()
        VpnMonitorService.stop(this)
        clearExitNode()
        activeRegionId = null
        cachedRegionIds = null
        regionAdapter = null
        prefs.clearRegistration()
        binding.vpnSwitch.isEnabled = false
        setVpnSwitchChecked(false)
        binding.regionSpinner.isEnabled = false
        binding.refreshButton.isEnabled = false
        updateRegistrationUi()
        showIdleStatus()
    }

    private fun toggleSetupSection() {
        val visible = binding.setupContent.visibility == View.VISIBLE
        binding.setupContent.visibility = if (visible) View.GONE else View.VISIBLE
        binding.setupToggle.text = getString(if (visible) R.string.show_setup else R.string.hide_setup)
    }

    private fun updateRegistrationUi() {
        val registered = prefs.isRegistered
        binding.registerButton.text = getString(if (registered) R.string.reregister else R.string.register)
        binding.saveNameButton.visibility = if (registered) View.VISIBLE else View.GONE
        binding.connectionChip.text = getString(
            if (registered) R.string.status_registered else R.string.status_not_registered
        )
        binding.setupToggle.visibility = if (registered) View.VISIBLE else View.GONE
        if (!registered) {
            binding.setupContent.visibility = View.VISIBLE
            binding.setupToggle.text = getString(R.string.hide_setup)
        }
    }

    private fun enableVpnControls() {
        binding.vpnSwitch.isEnabled = true
        binding.regionSpinner.isEnabled = true
        binding.refreshButton.isEnabled = true
        updateRegistrationUi()
        if (AppVisibility.isInForeground) {
            startStatusPolling()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(message: String) {
        AppLogger.info("UI", message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
