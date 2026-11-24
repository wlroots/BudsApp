package io.github.wlroots.budsapp.bt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.wlroots.budsapp.R
import io.github.wlroots.budsapp.model.BudsState
import io.github.wlroots.budsapp.model.ConnectionStatus
import io.github.wlroots.budsapp.ui.DeviceActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the lifetime of an active earbuds session.
 * All protocol work happens in [BudsRepository]; this service only keeps the
 * process alive and mirrors state in the notification.
 */
class BudsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent)
            ACTION_DISCONNECT -> {
                BudsRepository.disconnect()
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                val state = BudsRepository.state.value
                if (state.status == ConnectionStatus.IDLE ||
                    state.status == ConnectionStatus.DISCONNECTED
                ) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleConnect(intent: Intent) {
        val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice? = try {
            adapter?.getRemoteDevice(mac)
        } catch (_: Exception) {
            null
        }

        if (device == null) {
            BudsRepository.appendLog("Unable to resolve Bluetooth device $mac")
            stopSelf()
            return
        }

        startForegroundCompat(buildNotification("Connecting…", "Preparing connection"))
        BudsRepository.connect(this, device)
        observeState()
    }

    private fun observeState() {
        observer?.cancel()
        observer = scope.launch {
            BudsRepository.state.collectLatest { state ->
                when (state.status) {
                    ConnectionStatus.CONNECTED -> {
                        val title = state.deviceName.ifEmpty { "Earbuds connected" }
                        val detail = state.battery.summary().ifEmpty { state.config?.name.orEmpty() }
                        updateNotification(title, detail.ifEmpty { "Connected" }, ongoing = true)
                    }

                    ConnectionStatus.CONNECTING -> updateNotification(
                        state.deviceName.ifEmpty { "Connecting…" },
                        state.statusMessage.ifEmpty { "Connecting" },
                        ongoing = true,
                    )

                    ConnectionStatus.ERROR -> updateNotification(
                        state.deviceName.ifEmpty { "Connection lost" },
                        state.statusMessage.ifEmpty { "Disconnected" },
                        ongoing = false,
                    )

                    ConnectionStatus.DISCONNECTED,
                    ConnectionStatus.IDLE -> {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun updateNotification(title: String, text: String, ongoing: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, ongoing))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean = true): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DeviceActivity::class.java),
            pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BudsService::class.java).setAction(ACTION_DISCONNECT),
            pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.disconnect), disconnectIntent)
            .build()
    }

    private fun pendingFlags(base: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) base or PendingIntent.FLAG_IMMUTABLE else base

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "buds_connection"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "io.github.wlroots.budsapp.action.CONNECT"
        const val ACTION_DISCONNECT = "io.github.wlroots.budsapp.action.DISCONNECT"
        const val EXTRA_MAC = "extra_mac"

        fun connect(context: Context, device: BluetoothDevice) {
            val intent = Intent(context, BudsService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_MAC, device.address)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, BudsService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
