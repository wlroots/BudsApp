package io.github.wlroots.budsapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import io.github.wlroots.budsapp.R
import io.github.wlroots.budsapp.bt.BudsRepository
import io.github.wlroots.budsapp.databinding.ActivityDeviceBinding
import io.github.wlroots.budsapp.databinding.ItemBatteryBinding
import io.github.wlroots.budsapp.databinding.ItemMultipointBinding
import io.github.wlroots.budsapp.model.BatteryReading
import io.github.wlroots.budsapp.model.BudsState
import io.github.wlroots.budsapp.model.ConnectionStatus
import io.github.wlroots.budsapp.model.DeviceConfig
import io.github.wlroots.budsapp.model.GestureState
import io.github.wlroots.budsapp.model.RingSide
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceBinding

    private var currentConfig: DeviceConfig? = null
    private var updatingUi = false
    private var customEqTouching = false
    private var lastCustomEq: List<Float>? = null

    private val eqPresetChips = LinkedHashMap<Int, Int>()
    private val ancLevelChips = LinkedHashMap<Int, Int>()
    private val gestureSpinners = LinkedHashMap<GestureKey, GestureRow>()

    private var multipointDialog: AlertDialog? = null
    private var multipointContainer: LinearLayout? = null

    private data class GestureKey(val device: Int, val buttonId: Int, val type: Int)

    private data class GestureAction(val label: String, val bytes: List<Int>)

    private data class GestureRow(
        val input: MaterialAutoCompleteTextView,
        val actions: List<GestureAction>,
        val noiseBox: TextInputLayout?,
        val noiseInput: MaterialAutoCompleteTextView?,
        val noiseOptions: List<Pair<String, Int>>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupNoiseControl()
        setupEqualizer()
        setupSoundFeatures()
        setupFindMyBuds()
        setupMultipoint()
        setupInfo()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BudsRepository.state.collect { state -> applyState(state) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_device, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            BudsRepository.refreshBattery()
            BudsRepository.refreshMultipointDevices()
            true
        }

        R.id.action_diagnostics -> {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            true
        }

        R.id.action_disconnect -> {
            BudsRepository.disconnect()
            finish()
            true
        }

        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /* ------------------------------------------------------------------ setup */

    private fun setupNoiseControl() {
        binding.ancToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (updatingUi || !isChecked) return@addOnButtonCheckedListener
            val config = currentConfig ?: return@addOnButtonCheckedListener
            val noise = config.noise ?: return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnAncOff -> noise.offByte
                R.id.btnAncTransparency -> noise.transparencyByte
                R.id.btnAncNc -> noise.noiseCancellationBytes().firstOrNull()
                else -> null
            } ?: return@addOnButtonCheckedListener
            BudsRepository.setAnc(mode)
        }

        binding.switchPersonalizedAnc.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) BudsRepository.setPersonalizedAnc(checked)
        }
    }

    private fun setupEqualizer() {
        binding.eqPresetGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (updatingUi) return@setOnCheckedStateChangeListener
            val preset = checkedIds.firstOrNull()?.let { eqPresetChips[it] } ?: return@setOnCheckedStateChangeListener
            BudsRepository.setEqPreset(preset)
        }

        setupEqSlider(binding.eqBassSeek, binding.eqBassValue)
        setupEqSlider(binding.eqMidSeek, binding.eqMidValue)
        setupEqSlider(binding.eqTrebleSeek, binding.eqTrebleValue)

        binding.applyCustomEq.setOnClickListener {
            BudsRepository.setCustomEq(
                bass = eqValue(binding.eqBassSeek.value),
                mid = eqValue(binding.eqMidSeek.value),
                treble = eqValue(binding.eqTrebleSeek.value),
            )
        }

        binding.switchBass.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            BudsRepository.setEnhancedBass(checked, binding.bassLevelSeek.value.toInt())
            binding.bassBox.isVisible = checked
        }

        binding.bassLevelSeek.addOnChangeListener { _, value, fromUser ->
            binding.bassLevelValue.text = value.toInt().toString()
            if (fromUser && !updatingUi) {
                BudsRepository.setEnhancedBass(binding.switchBass.isChecked, value.toInt())
            }
        }
    }

    private fun setupEqSlider(slider: Slider, valueLabel: TextView) {
        slider.addOnChangeListener { _, value, _ ->
            valueLabel.text = formatDb(eqValue(value))
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                customEqTouching = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                customEqTouching = false
            }
        })
        valueLabel.text = formatDb(eqValue(slider.value))
    }

    private fun setupSoundFeatures() {
        binding.switchLowLatency.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) BudsRepository.setLowLatency(checked)
        }
        binding.switchSpatial.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) BudsRepository.setSpatialAudio(checked)
        }
        binding.switchInEar.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) BudsRepository.setInEarDetection(checked)
        }
    }

    private fun setupFindMyBuds() {
        binding.ringLeftButton.setOnClickListener {
            val config = currentConfig ?: return@setOnClickListener
            val side = if (config.ringLegacy || config.batterySingle) RingSide.SINGLE else RingSide.LEFT
            val playing = side in BudsRepository.state.value.ringPlaying
            BudsRepository.ring(side, !playing)
        }

        binding.ringRightButton.setOnClickListener {
            val playing = RingSide.RIGHT in BudsRepository.state.value.ringPlaying
            BudsRepository.ring(RingSide.RIGHT, !playing)
        }

        binding.ringStopButton.setOnClickListener { BudsRepository.stopAllRings() }

        binding.fitTestButton.setOnClickListener {
            BudsRepository.startFitTest()
            binding.fitResultText.text = getString(R.string.find_fit_running)
        }
    }

    private fun setupMultipoint() {
        binding.switchMultipoint.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) BudsRepository.setMultipoint(checked)
        }

        binding.multipointManageButton.setOnClickListener {
            BudsRepository.refreshMultipointDevices()
            showMultipointDialog()
        }
    }

    private fun setupInfo() {
        binding.diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.disconnectButton.setOnClickListener {
            BudsRepository.disconnect()
            finish()
        }
    }

    /* ------------------------------------------------------------------- state */

    private fun applyState(state: BudsState) {
        updatingUi = true
        try {
            binding.demoBanner.isVisible = state.demo
            binding.toolbar.title = state.deviceName.ifEmpty { getString(R.string.app_name) }

            val statusColor = when (state.status) {
                ConnectionStatus.CONNECTED -> R.color.status_connected
                ConnectionStatus.ERROR -> R.color.status_error
                else -> R.color.status_idle
            }
            val statusDot = ContextCompat.getDrawable(this, R.drawable.ic_status_dot)?.mutate()
            if (statusDot != null) {
                DrawableCompat.setTint(statusDot, ContextCompat.getColor(this, statusColor))
            }
            binding.connectionStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(
                statusDot, null, null, null,
            )

            binding.connectionStatus.text = when (state.status) {
                ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
                ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                ConnectionStatus.ERROR -> state.statusMessage.ifEmpty { getString(R.string.status_error) }
                ConnectionStatus.IDLE -> getString(R.string.status_disconnected)
            }

            val config = state.config
            if (config != currentConfig) {
                currentConfig = config
                lastCustomEq = null
                if (config == null) {
                    resetConfigUi()
                } else {
                    applyConfig(config)
                }
            }

            bindBattery(state)
            bindNoise(state)
            bindEqualizer(state)
            bindSoundFeatures(state)
            bindFindMyBuds(state)
            bindMultipoint(state)
            bindGestures(state)
            bindInfo(state)
            binding.toolbar.subtitle = config?.name ?: state.deviceName.ifEmpty { null }
        } finally {
            updatingUi = false
        }
    }

    private fun resetConfigUi() {
        binding.cardBattery.isVisible = false
        binding.cardAnc.isVisible = false
        binding.cardEq.isVisible = false
        binding.cardSound.isVisible = false
        binding.cardFind.isVisible = false
        binding.cardMultipoint.isVisible = false
        binding.cardGestures.isVisible = false
        binding.eqPresetGroup.removeAllViews()
        binding.ancLevelGroup.removeAllViews()
        binding.gestureContainer.removeAllViews()
        eqPresetChips.clear()
        ancLevelChips.clear()
        gestureSpinners.clear()
    }

    private fun applyConfig(config: DeviceConfig) {
        binding.cardBattery.isVisible = config.batteryLR || config.batteryCase || config.batterySingle
        binding.batteryCase.root.isVisible = config.batteryCase
        binding.batteryRight.root.isVisible = config.batteryLR
        binding.batteryLeft.root.isVisible = config.batteryLR || config.batterySingle

        buildNoiseControls(config)
        buildEqPresets(config)

        binding.cardSound.isVisible =
            config.lowLatencyMode || config.spatialAudioSwitch || config.inEarDetection ||
                config.spatialHeadTracking
        binding.switchLowLatency.isVisible = config.lowLatencyMode
        binding.lowLatencyDesc.isVisible = config.lowLatencyMode
        binding.switchSpatial.isVisible = config.spatialAudioSwitch
        binding.spatialDesc.isVisible = config.spatialAudioSwitch
        binding.switchInEar.isVisible = config.inEarDetection
        binding.inEarDesc.isVisible = config.inEarDetection
        binding.spatialNote.isVisible = config.spatialHeadTracking && !config.spatialAudioSwitch

        binding.cardFind.isVisible = config.ring || config.fitTest
        binding.ringButtons.isVisible = config.ring
        binding.ringRightButton.isVisible = config.ring && !config.ringLegacy && !config.batterySingle
        binding.ringStopButton.isVisible = config.ring && !config.ringLegacy
        binding.ringLeftButton.text = if (config.ringLegacy || config.batterySingle) {
            getString(R.string.find_ring)
        } else {
            getString(R.string.find_ring_left)
        }
        binding.fitTestBox.isVisible = config.fitTest

        binding.cardMultipoint.isVisible = config.dualConnection
        binding.multipointRebootNote.isVisible = config.dualConnectionReboot

        binding.cardGestures.isVisible = config.gestures != null
        config.gestures?.let { buildGestureRows(config) }

        binding.cardEq.isVisible = config.hasEq || config.bassEnhanceLevel > 0
        binding.eqPresetGroup.isVisible = config.hasEq
        binding.switchBass.isVisible = config.bassEnhanceLevel > 0
        binding.bassLevelSeek.valueTo = config.bassEnhanceLevel.coerceAtLeast(1).toFloat()
    }

    /* ----------------------------------------------------------------- battery */

    private fun bindBattery(state: BudsState) {
        val config = currentConfig ?: return
        val battery = state.battery

        if (config.batterySingle) {
            val reading = battery.single ?: battery.left ?: battery.right
            bindBatteryItem(
                binding.batteryLeft,
                getString(R.string.battery_single),
                reading,
                visible = true,
            )
            return
        }

        bindBatteryItem(binding.batteryLeft, getString(R.string.battery_left), battery.left, config.batteryLR)
        bindBatteryItem(binding.batteryRight, getString(R.string.battery_right), battery.right, config.batteryLR)
        bindBatteryItem(binding.batteryCase, getString(R.string.battery_case), battery.case, config.batteryCase)
    }

    private fun bindBatteryItem(
        item: ItemBatteryBinding,
        label: String,
        reading: BatteryReading?,
        visible: Boolean,
    ) {
        item.root.isVisible = visible
        if (!visible) return
        item.batteryLabel.text = label
        if (reading == null) {
            item.batteryProgress.progress = 0
            item.batteryProgress.setIndicatorColor(ContextCompat.getColor(this, R.color.battery_inactive))
            item.batteryPercent.text = getString(R.string.battery_unknown)
            item.batteryStatus.text = getString(R.string.battery_disconnected)
            return
        }

        item.batteryProgress.progress = reading.level
        val color = when {
            reading.charging -> R.color.battery_charging
            reading.level <= 20 -> R.color.battery_low
            reading.level <= 50 -> R.color.battery_medium
            else -> R.color.battery_good
        }
        item.batteryProgress.setIndicatorColor(ContextCompat.getColor(this, color))
        item.batteryPercent.text = getString(R.string.battery_percent, reading.level)
        item.batteryStatus.text = getString(
            if (reading.charging) R.string.battery_charging else R.string.battery_discharging,
        )
    }

    /* ------------------------------------------------------------ noise control */

    private fun buildNoiseControls(config: DeviceConfig) {
        binding.ancLevelGroup.removeAllViews()
        ancLevelChips.clear()

        val noise = config.noise
        binding.cardAnc.isVisible = noise != null
        if (noise == null) return

        binding.btnAncOff.isVisible = noise.offByte != null
        binding.btnAncTransparency.isVisible = noise.transparencyByte != null
        binding.btnAncNc.isVisible = noise.noiseCancellationBytes().isNotEmpty()
        binding.switchPersonalizedAnc.isVisible = config.personalizeAnc
        binding.personalizedAncDesc.isVisible = config.personalizeAnc

        val levels = DeviceConfig.ANC_LEVEL_ORDER.mapNotNull { name ->
            noise.levels[name]?.let { name to it }
        } + noise.levels.entries
            .filter { it.key !in DeviceConfig.ANC_LEVEL_ORDER }
            .map { it.key to it.value }

        val hasLevels = levels.isNotEmpty()
        binding.ancLevelTitle.isVisible = hasLevels
        binding.ancLevelGroup.isVisible = hasLevels

        levels.forEach { (name, mode) ->
            val chip = Chip(this).apply {
                text = levelLabel(name)
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
                setOnCheckedChangeListener { _, checked ->
                    if (!updatingUi && checked) BudsRepository.setAnc(mode)
                }
            }
            ancLevelChips[chip.id] = mode
            binding.ancLevelGroup.addView(chip)
        }
    }

    private fun levelLabel(name: String): String = when (name) {
        "low" -> getString(R.string.anc_low)
        "mid" -> getString(R.string.anc_mid)
        "high" -> getString(R.string.anc_high)
        "adaptive" -> getString(R.string.anc_adaptive)
        else -> Formatting.humanize(name)
    }

    private fun bindNoise(state: BudsState) {
        val config = currentConfig ?: return
        val noise = config.noise ?: return
        val mode = state.ancRaw

        binding.ancToggleGroup.clearChecked()
        when {
            mode == null -> Unit
            mode == noise.offByte -> binding.ancToggleGroup.check(R.id.btnAncOff)
            mode == noise.transparencyByte -> binding.ancToggleGroup.check(R.id.btnAncTransparency)
            noise.noiseCancellationBytes().contains(mode) -> binding.ancToggleGroup.check(R.id.btnAncNc)
        }

        val levelMode = noise.levels.values.firstOrNull { it == mode }
        if (levelMode != null) {
            val chipId = ancLevelChips.entries.firstOrNull { it.value == levelMode }?.key
            if (chipId != null) binding.ancLevelGroup.check(chipId) else binding.ancLevelGroup.clearCheck()
        } else {
            binding.ancLevelGroup.clearCheck()
        }

        binding.switchPersonalizedAnc.isChecked = state.personalizedAnc == true
    }

    /* ----------------------------------------------------------------- equalizer */

    private fun buildEqPresets(config: DeviceConfig) {
        binding.eqPresetGroup.removeAllViews()
        eqPresetChips.clear()

        config.orderedEqPresets().forEach { (name, mode) ->
            val chip = Chip(this).apply {
                text = presetLabel(name)
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
            }
            eqPresetChips[chip.id] = mode
            binding.eqPresetGroup.addView(chip)
        }
    }

    private fun presetLabel(name: String): String = when (name) {
        "balanced" -> getString(R.string.eq_balanced)
        "voice" -> getString(R.string.eq_voice)
        "more_treble" -> getString(R.string.eq_more_treble)
        "more_bass" -> getString(R.string.eq_more_bass)
        "custom" -> getString(R.string.eq_custom)
        else -> Formatting.humanize(name)
    }

    private fun bindEqualizer(state: BudsState) {
        val config = currentConfig ?: return
        val preset = state.eqPresetRaw

        if (preset != null) {
            val chipId = eqPresetChips.entries.firstOrNull { it.value == preset }?.key
            if (chipId != null) {
                binding.eqPresetGroup.check(chipId)
            } else {
                binding.eqPresetGroup.clearCheck()
            }
        }

        val customSelected = preset != null && preset == config.eqPresets["custom"]
        binding.customEqBox.isVisible = customSelected

        val custom = state.customEq
        if (custom != null && custom.size >= 3 && custom != lastCustomEq && !customEqTouching) {
            lastCustomEq = custom
            setEqSlider(binding.eqBassSeek, custom[0])
            setEqSlider(binding.eqMidSeek, custom[1])
            setEqSlider(binding.eqTrebleSeek, custom[2])
        }

        if (config.bassEnhanceLevel > 0) {
            binding.switchBass.isChecked = state.bassEnabled == true
            binding.bassBox.isVisible = state.bassEnabled == true
            val level = (state.bassLevel ?: binding.bassLevelSeek.value.toInt())
                .coerceIn(0, config.bassEnhanceLevel)
            binding.bassLevelSeek.value = level.toFloat()
            binding.bassLevelValue.text = level.toString()
        }
    }

    private fun eqValue(value: Float): Float = (value - EQ_CENTER) / 10f

    private fun setEqSlider(slider: Slider, value: Float) {
        slider.value = (value.coerceIn(-6f, 6f) * 10).roundToInt().toFloat() + EQ_CENTER
    }

    private fun formatDb(value: Float): String = "%+.1f dB".format(value)

    /* -------------------------------------------------------------- sound features */

    private fun bindSoundFeatures(state: BudsState) {
        binding.switchLowLatency.isChecked = state.lowLatency == true
        binding.switchSpatial.isChecked = state.spatial == true
        binding.switchInEar.isChecked = state.inEar == true
    }

    /* --------------------------------------------------------------- find my buds */

    private fun bindFindMyBuds(state: BudsState) {
        val config = currentConfig ?: return
        if (config.ring) {
            val leftSide = if (config.ringLegacy || config.batterySingle) RingSide.SINGLE else RingSide.LEFT
            binding.ringLeftButton.text = if (leftSide in state.ringPlaying) {
                getString(R.string.find_stop)
            } else if (config.ringLegacy || config.batterySingle) {
                getString(R.string.find_ring)
            } else {
                getString(R.string.find_ring_left)
            }
            binding.ringRightButton.text = if (RingSide.RIGHT in state.ringPlaying) {
                getString(R.string.find_stop)
            } else {
                getString(R.string.find_ring_right)
            }
        }

        if (config.fitTest) {
            val result = state.fitResult
            binding.fitResultText.text = if (result == null) {
                ""
            } else {
                getString(
                    R.string.find_fit_result,
                    fitLabel(result.left),
                    fitLabel(result.right),
                )
            }
        }
    }

    private fun fitLabel(value: Int): String = when (value) {
        0 -> getString(R.string.fit_good)
        1 -> getString(R.string.fit_bad)
        else -> getString(R.string.fit_error)
    }

    /* ------------------------------------------------------------------ multipoint */

    private fun bindMultipoint(state: BudsState) {
        val config = currentConfig ?: return
        if (!config.dualConnection) return

        binding.switchMultipoint.isChecked = state.multipoint == true
        val connected = state.multipointDevices.count { it.connected }
        binding.multipointSummary.text = getString(
            R.string.multipoint_summary,
            connected,
            state.multipointDevices.size,
        )

        if (multipointDialog?.isShowing == true) {
            rebuildMultipointRows(state)
        }
    }

    private fun showMultipointDialog() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val intro = TextView(this).apply {
            text = getString(R.string.multipoint_dialog_note)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.brand_red))
            setPadding(0, 0, 0, dp(12))
        }
        outer.addView(intro)

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        outer.addView(rows)
        multipointContainer = rows

        val scroll = ScrollView(this).apply {
            addView(
                outer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        multipointDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.section_multipoint)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.multipoint_refresh) { _, _ ->
                BudsRepository.refreshMultipointDevices()
            }
            .setOnDismissListener {
                multipointDialog = null
                multipointContainer = null
            }
            .show()

        rebuildMultipointRows(BudsRepository.state.value)
    }

    private fun rebuildMultipointRows(state: BudsState) {
        val container = multipointContainer ?: return
        container.removeAllViews()

        if (state.multipointDevices.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_device)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@DeviceActivity, R.color.brand_red),
                )
            }
            val text = TextView(this).apply {
                text = getString(R.string.multipoint_empty)
                textSize = 14f
                gravity = Gravity.CENTER
            }
            empty.addView(icon)
            empty.addView(text)
            container.addView(empty)
            return
        }

        state.multipointDevices.forEach { device ->
            val row = ItemMultipointBinding.inflate(layoutInflater, container, false)
            row.multipointName.text = device.name.ifEmpty { device.mac }
            row.multipointDetail.text = getString(
                if (device.connected) R.string.multipoint_detail_connected
                else R.string.multipoint_detail_disconnected,
                device.mac,
            )
            row.multipointAction.setText(
                if (device.connected) R.string.multipoint_disconnect else R.string.multipoint_connect,
            )
            row.multipointAction.setOnClickListener {
                BudsRepository.setMultipointDevice(device.mac, !device.connected)
            }
            container.addView(row.root)
        }
    }

    /* ------------------------------------------------------------------- gestures */

    private fun createDropdown(
        hint: String,
        values: List<String>,
    ): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val box = TextInputLayout(this).apply {
            this.hint = hint
            // The style attribute cannot be passed as defStyleAttr; configure the
            // outlined box explicitly before enabling the dropdown end icon.
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(12).toFloat(),
                dp(12).toFloat(),
                dp(12).toFloat(),
                dp(12).toFloat(),
            )
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val input = MaterialAutoCompleteTextView(this).apply {
            inputType = InputType.TYPE_NULL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        box.addView(input)
        input.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_item, values))
        input.setText(values.firstOrNull(), false)
        return box to input
    }

    private fun buildGestureRows(config: DeviceConfig) {
        binding.gestureContainer.removeAllViews()
        gestureSpinners.clear()

        val gestures = config.gestures ?: return
        gestures.slots.forEach { slot ->
            val typeByte = gestures.typeByte(slot.type) ?: return@forEach
            val key = GestureKey(slot.device, slot.buttonId, typeByte)

            val actionNames = gestures.gestures[slot.type]?.actions
                ?: gestures.actions.keys.toList()
            val actions = actionNames.mapNotNull { name ->
                gestures.actions[name]?.takeIf { it.isNotEmpty() }?.let { bytes ->
                    GestureAction(Formatting.actionLabel(name), bytes)
                }
            }
            if (actions.isEmpty()) return@forEach

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val (actionBox, input) = createDropdown(
                Formatting.slotLabel(slot.group, slot.type),
                actions.map { it.label },
            )
            row.addView(actionBox)

            val noiseOptions = if (actions.any { action -> action.bytes.any { it in NOISE_CYCLE_BYTES } }) {
                noiseCycleOptions()
            } else {
                emptyList()
            }
            val noiseDropdown = if (noiseOptions.isEmpty()) {
                null
            } else {
                createDropdown(
                    getString(R.string.gesture_noise_cycle),
                    noiseOptions.map { it.first },
                ).also { (box, _) ->
                    box.isVisible = false
                    row.addView(box)
                }
            }

            input.setOnItemClickListener { _, _, position, _ ->
                if (updatingUi) return@setOnItemClickListener
                // Only write once the earbuds have reported this gesture slot.
                val known = BudsRepository.state.value.gestures.any {
                    it.device == slot.device && it.buttonId == slot.buttonId && it.type == typeByte
                }
                if (!known) return@setOnItemClickListener
                val action = actions.getOrNull(position) ?: return@setOnItemClickListener
                noiseDropdown?.first?.isVisible = action.bytes.any { it in NOISE_CYCLE_BYTES }
                val current = BudsRepository.state.value.gestures.firstOrNull {
                    it.device == slot.device && it.buttonId == slot.buttonId && it.type == typeByte
                }?.action
                if (current != null && current in action.bytes) return@setOnItemClickListener
                val byte = action.bytes.firstOrNull() ?: return@setOnItemClickListener
                BudsRepository.setGesture(
                    GestureState(
                        device = slot.device,
                        buttonId = slot.buttonId,
                        type = typeByte,
                        action = byte,
                    ),
                )
            }

            noiseDropdown?.second?.setOnItemClickListener { _, _, position, _ ->
                if (updatingUi) return@setOnItemClickListener
                val known = BudsRepository.state.value.gestures.any {
                    it.device == slot.device && it.buttonId == slot.buttonId && it.type == typeByte
                }
                if (!known) return@setOnItemClickListener
                val action = noiseOptions.getOrNull(position)?.second ?: return@setOnItemClickListener
                val current = BudsRepository.state.value.gestures.firstOrNull {
                    it.device == slot.device && it.buttonId == slot.buttonId && it.type == typeByte
                }?.action
                if (current == action) return@setOnItemClickListener
                BudsRepository.setGesture(
                    GestureState(
                        device = slot.device,
                        buttonId = slot.buttonId,
                        type = typeByte,
                        action = action,
                    ),
                )
            }

            binding.gestureContainer.addView(row)
            gestureSpinners[key] = GestureRow(
                input = input,
                actions = actions,
                noiseBox = noiseDropdown?.first,
                noiseInput = noiseDropdown?.second,
                noiseOptions = noiseOptions,
            )
        }
    }

    private fun noiseCycleOptions(): List<Pair<String, Int>> = listOf(
        getString(R.string.gesture_noise_cycle_all) to 0x0A,
        getString(R.string.gesture_noise_cycle_off_nc) to 0x14,
        getString(R.string.gesture_noise_cycle_transparency_nc) to 0x16,
        getString(R.string.gesture_noise_cycle_off_transparency) to 0x15,
    )

    private fun bindGestures(state: BudsState) {
        if (gestureSpinners.isEmpty()) return
        gestureSpinners.forEach { (key, row) ->
            val current = state.gestures.firstOrNull {
                it.device == key.device && it.buttonId == key.buttonId && it.type == key.type
            } ?: return@forEach
            val index = row.actions.indexOfFirst { current.action in it.bytes }
            if (index >= 0) {
                val label = row.actions[index].label
                if (row.input.text?.toString() != label) {
                    row.input.setText(label, false)
                }
            }

            val noiseInput = row.noiseInput ?: return@forEach
            val isNoise = current.action in NOISE_CYCLE_BYTES
            row.noiseBox?.isVisible = isNoise
            if (isNoise) {
                val label = row.noiseOptions.firstOrNull { it.second == current.action }?.first
                if (label != null && noiseInput.text?.toString() != label) {
                    noiseInput.setText(label, false)
                }
            }
        }
    }

    /* ----------------------------------------------------------------------- info */

    private fun bindInfo(state: BudsState) {
        val config = currentConfig
        val modelName = config?.name ?: getString(R.string.device_unknown)
        val modelId = config?.modelId.orEmpty()
        binding.infoModel.text = if (modelId.isEmpty()) {
            modelName
        } else {
            "$modelName ($modelId)"
        }
        binding.infoFirmware.text = state.firmware.ifEmpty { getString(R.string.device_unknown) }
        binding.infoSerial.text = state.serial.ifEmpty { getString(R.string.device_unknown) }
        binding.infoMac.text = state.mac.ifEmpty { "-" }

        val notes = buildList {
            val autoPowerOff = config?.autoPowerOff.orEmpty()
            if (autoPowerOff.isNotEmpty()) {
                add(
                    getString(
                        R.string.device_auto_power_off,
                        autoPowerOff.joinToString(" / "),
                    ),
                )
            }
            if (config?.spatialHeadTracking == true) {
                add(getString(R.string.device_spatial_head_tracking))
            }
            if (config?.spatialImmersiveModes == true) {
                add(getString(R.string.device_spatial_immersive))
            }
        }
        binding.infoExtra.isVisible = notes.isNotEmpty()
        binding.infoExtra.text = notes.joinToString("\n")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        multipointDialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val EQ_CENTER = 60
        private val NOISE_CYCLE_BYTES = setOf(0x0A, 0x14, 0x15, 0x16)
    }
}
