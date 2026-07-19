package com.tailscalepiacontrol

import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var regions: List<RegionInfo> = emptyList()
    private var vpnUpdateInProgress = false

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

        prefs.controllerUrl?.let { binding.controllerUrlInput.setText(it) }

        binding.checkConnectionButton.setOnClickListener { checkConnection() }
        binding.scanQrButton.setOnClickListener { startQrScan() }
        binding.registerButton.setOnClickListener { registerDevice() }
        binding.refreshButton.setOnClickListener { refreshStatus() }
        binding.openTailscaleButton.setOnClickListener { TailscaleHelper.openTailscaleApp(this) }
        binding.testIpButton.setOnClickListener { testIpAndLocation() }
        binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked -> onVpnToggled(isChecked) }

        if (prefs.isRegistered) {
            enableControls()
            refreshStatus()
        } else if (!prefs.controllerUrl.isNullOrBlank()) {
            checkConnection()
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
            binding.regionSpinner.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
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
                setVpnSwitchChecked(status.enabled)

                status.region?.let { regionId ->
                    val index = regions.indexOfFirst { it.id == regionId }
                    if (index >= 0) binding.regionSpinner.setSelection(index)
                }

                val message = buildString {
                    append("Device: ${status.device_id}\n")
                    append("Enabled: ${status.enabled}\n")
                    append("Region: ${status.region ?: "-"}\n")
                    append("Exit node: ${status.exit_node_hostname ?: "-"}\n")
                    append("Stack: ${status.stack_status ?: "-"}\n")
                    status.message?.let { append("\n$it") }
                }
                binding.statusText.text = message
            } catch (error: Exception) {
                if (!handleApiError(error)) {
                    toast("Status refresh failed: ${error.message}")
                }
            } finally {
                if (prefs.isRegistered) {
                    binding.refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun onVpnToggled(enabled: Boolean) {
        if (vpnUpdateInProgress) {
            setVpnSwitchChecked(!enabled)
            return
        }

        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

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

        vpnUpdateInProgress = true
        binding.vpnSwitch.isEnabled = false
        binding.regionSpinner.isEnabled = false

        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl, token).updateVpn(enabled, region)
                }

                if (enabled && !status.exit_node_hostname.isNullOrBlank()) {
                    if (status.stack_status == "running") {
                        TailscaleHelper.setExitNode(this@MainActivity, status.exit_node_hostname, status.allow_lan_access)
                    } else {
                        toast("Stack starting — refresh in 30s, then exit node will be applied")
                    }
                } else if (!enabled) {
                    TailscaleHelper.setExitNode(this@MainActivity, null, true)
                }

                refreshStatus()
            } catch (error: Exception) {
                setVpnSwitchChecked(!enabled)
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
