package com.tailscalepiacontrol

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tailscalepiacontrol.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var regions: List<RegionInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        prefs.controllerUrl?.let { binding.controllerUrlInput.setText(it) }

        binding.checkConnectionButton.setOnClickListener { checkConnection() }
        binding.registerButton.setOnClickListener { registerDevice() }
        binding.refreshButton.setOnClickListener { refreshStatus() }
        binding.openTailscaleButton.setOnClickListener { TailscaleHelper.openTailscaleApp(this) }
        binding.testIpButton.setOnClickListener { testIpAndLocation() }
        binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked -> onVpnToggled(isChecked) }

        if (prefs.isRegistered) {
            enableControls()
            loadRegions()
            refreshStatus()
        } else if (!prefs.controllerUrl.isNullOrBlank()) {
            checkConnection()
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
                binding.pairingSecretLayout.visibility = View.GONE
                toast("Connection failed: ${error.message}")
            }
        }
    }

    private fun updatePairingUi(pairing: PairingInfoResponse) {
        binding.pairingStatusText.text = pairing.instructions
        if (pairing.required) {
            binding.pairingSecretLayout.visibility = View.VISIBLE
            if (!pairing.secret.isNullOrBlank() && binding.pairingSecretInput.text.isNullOrBlank()) {
                binding.pairingSecretInput.setText(pairing.secret)
            }
        } else {
            binding.pairingSecretLayout.visibility = View.GONE
            binding.pairingSecretInput.setText("")
        }
    }

    private fun registerDevice() {
        val baseUrl = binding.controllerUrlInput.text?.toString()?.trim().orEmpty()
        val name = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        val secret = binding.pairingSecretInput.text?.toString()?.trim().orEmpty().ifBlank { null }

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
                if (pairing.required && secret.isNullOrBlank()) {
                    toast("Pairing secret required. Tap Check connection or open the controller in your browser.")
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl).register(name, "android", secret)
                }
                prefs.controllerUrl = baseUrl
                prefs.apiToken = response.api_token
                prefs.deviceId = response.device_id
                toast("Registered as ${response.name}")
                enableControls()
                loadRegions()
                refreshStatus()
            } catch (error: Exception) {
                toast("Registration failed: ${error.message}")
            }
        }
    }

    private fun loadRegions() {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        lifecycleScope.launch {
            try {
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
            } catch (error: Exception) {
                if (!handleApiError(error)) {
                    toast("Failed to load regions: ${error.message}")
                }
            }
        }
    }

    private fun refreshStatus() {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    ControllerClient(baseUrl, token).getVpnStatus()
                }
                binding.vpnSwitch.setOnCheckedChangeListener(null)
                binding.vpnSwitch.isChecked = status.enabled
                binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked -> onVpnToggled(isChecked) }

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
            }
        }
    }

    private fun onVpnToggled(enabled: Boolean) {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        val region = if (enabled) {
            val index = binding.regionSpinner.selectedItemPosition
            if (index < 0 || index >= regions.size) {
                binding.vpnSwitch.isChecked = false
                toast("Select a region first")
                return
            }
            regions[index].id
        } else {
            null
        }

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
                binding.vpnSwitch.isChecked = !enabled
                if (!handleApiError(error)) {
                    toast("VPN update failed: ${error.message}")
                }
            }
        }
    }

    private fun testIpAndLocation() {
        lifecycleScope.launch {
            try {
                binding.testIpButton.isEnabled = false
                val result = withContext(Dispatchers.IO) { IpLookup.fetch() }
                binding.statusText.text = IpLookup.format(result)
                toast("IP lookup complete")
            } catch (error: Exception) {
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
        binding.vpnSwitch.isChecked = false
        binding.regionSpinner.isEnabled = false
        binding.refreshButton.isEnabled = false
        binding.registerButton.visibility = View.VISIBLE
        binding.checkConnectionButton.visibility = View.VISIBLE
        binding.deviceNameInput.isEnabled = true
        binding.pairingSecretInput.isEnabled = true
        binding.controllerUrlInput.isEnabled = true
        binding.statusText.text = getString(R.string.status_idle)
    }

    private fun enableControls() {
        binding.vpnSwitch.isEnabled = true
        binding.regionSpinner.isEnabled = true
        binding.refreshButton.isEnabled = true
        binding.registerButton.visibility = View.GONE
        binding.checkConnectionButton.visibility = View.GONE
        binding.deviceNameInput.isEnabled = false
        binding.pairingSecretInput.isEnabled = false
        binding.controllerUrlInput.isEnabled = false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
