package io.github.wlroots.budsapp.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.wlroots.budsapp.R
import io.github.wlroots.budsapp.bt.BudsRepository
import io.github.wlroots.budsapp.bt.BudsService
import io.github.wlroots.budsapp.databinding.ActivityMainBinding
import io.github.wlroots.budsapp.model.BudsState
import io.github.wlroots.budsapp.model.ConnectionStatus
import io.github.wlroots.budsapp.model.DeviceCatalog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = DeviceAdapter { item -> onDeviceClicked(item) }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter
        binding.emptyAction.setOnClickListener { onEmptyAction() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BudsRepository.state.collect { state -> refreshDevices(state) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_show_all)?.isChecked = BudsRepository.showAllDevices(this)
        menu.findItem(R.id.action_auto_reconnect)?.isChecked = BudsRepository.autoReconnect(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_show_all -> {
            val enabled = !item.isChecked
            item.isChecked = enabled
            BudsRepository.setShowAllDevices(this, enabled)
            refreshDevices()
            true
        }

        R.id.action_auto_reconnect -> {
            val enabled = !item.isChecked
            item.isChecked = enabled
            BudsRepository.setAutoReconnect(this, enabled)
            true
        }

        R.id.action_demo -> {
            BudsRepository.startDemo(this)
            startActivity(Intent(this, DeviceActivity::class.java))
            true
        }

        R.id.action_diagnostics -> {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        R.id.action_bluetooth_settings -> {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices(state: BudsState = BudsRepository.state.value) {
        if (state.demo) {
            adapter.submit(
                listOf(
                    DeviceListItem(
                        mac = DEMO_MAC,
                        name = state.deviceName.ifEmpty { "BudsApp Demo" },
                        configName = state.config?.name,
                        modelId = state.config?.modelId,
                        status = getString(R.string.demo_banner),
                        connected = true,
                        known = true,
                    ),
                ),
            )
            binding.emptyState.isVisible = false
            binding.deviceList.isVisible = true
            return
        }

        val bluetooth = BluetoothAdapter.getDefaultAdapter()

        if (bluetooth == null) {
            showEmpty(
                title = getString(R.string.main_unsupported),
                text = getString(R.string.main_empty_text),
                action = null,
            )
            adapter.submit(emptyList())
            return
        }

        if (!bluetooth.isEnabled) {
            showEmpty(
                title = getString(R.string.main_bt_disabled),
                text = getString(R.string.main_bt_disabled_text),
                action = getString(R.string.main_bt_enable),
            )
            adapter.submit(emptyList())
            return
        }

        val showAll = BudsRepository.showAllDevices(this)
        val lastMac = BudsRepository.lastDeviceMac()
        val bonded = try {
            bluetooth.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet<BluetoothDevice>()
        }

        val items = bonded.mapNotNull { device ->
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            }.orEmpty()
            val config = DeviceCatalog.byName(this, name)
            val known = config != null
            if (!known && !showAll) return@mapNotNull null

            val isCurrent = state.mac.equals(device.address, ignoreCase = true)
            val status = when {
                isCurrent && state.status == ConnectionStatus.CONNECTED ->
                    listOfNotNull(
                        getString(R.string.status_connected),
                        state.battery.summary().ifEmpty { null },
                    ).joinToString("\n")

                isCurrent && state.status == ConnectionStatus.CONNECTING ->
                    getString(R.string.status_connecting)

                isCurrent && state.status == ConnectionStatus.ERROR ->
                    getString(R.string.status_error)

                device.address.equals(lastMac, ignoreCase = true) ->
                    getString(R.string.main_last_device)

                else -> getString(R.string.main_paired)
            }

            DeviceListItem(
                mac = device.address,
                name = name.ifEmpty { device.address },
                configName = config?.name,
                modelId = config?.modelId,
                status = status,
                connected = isCurrent && state.status == ConnectionStatus.CONNECTED,
                known = known,
            )
        }.sortedWith(
            compareByDescending<DeviceListItem> { it.connected }
                .thenByDescending { it.mac.equals(lastMac, ignoreCase = true) }
                .thenBy { it.name.lowercase() },
        )

        adapter.submit(items)
        binding.emptyState.isVisible = items.isEmpty()
        binding.deviceList.isVisible = items.isNotEmpty()
        if (items.isEmpty()) {
            showEmpty(
                title = getString(R.string.main_empty_title),
                text = getString(R.string.main_empty_text),
                action = getString(R.string.main_empty_action),
            )
        }
    }

    private fun showEmpty(title: String, text: String, action: String?) {
        binding.emptyState.isVisible = true
        binding.emptyTitle.text = title
        binding.emptyText.text = text
        binding.emptyAction.isVisible = action != null
        binding.emptyAction.text = action.orEmpty()
    }

    private fun onEmptyAction() {
        val bluetooth = BluetoothAdapter.getDefaultAdapter()
        if (bluetooth != null && !bluetooth.isEnabled) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun onDeviceClicked(item: DeviceListItem) {
        if (item.mac == DEMO_MAC && BudsRepository.state.value.demo) {
            startActivity(Intent(this, DeviceActivity::class.java))
            return
        }
        if (!item.known) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_unsupported_title)
                .setMessage(R.string.dialog_unsupported_message)
                .setPositiveButton(R.string.dialog_unsupported_continue) { _, _ -> connect(item) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        connect(item)
    }

    @SuppressLint("MissingPermission")
    private fun connect(item: DeviceListItem) {
        val bluetooth = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = try {
            bluetooth.getRemoteDevice(item.mac)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            BudsRepository.appendLog("Unable to open ${item.mac}")
            return
        }
        BudsService.connect(this, device)
        startActivity(Intent(this, DeviceActivity::class.java))
    }

    companion object {
        private const val DEMO_MAC = "AA:BB:CC:DD:EE:FF"
    }
}
