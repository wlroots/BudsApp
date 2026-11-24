package io.github.wlroots.budsapp.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bluetooth Classic RFCOMM (SPP) transport for Nothing/CMF earbuds.
 *
 * Nothing X and the desktop implementations use the vendor SPP service:
 *   aeac4a03-dff5-498f-843a-34487cf133eb
 * Some units don't expose it through SDP (or only expose it when no other
 * controlling app is connected), so we fall back to an insecure SDP connection
 * and finally to the known fixed channels 15/16/28 via the hidden
 * BluetoothDevice.createRfcommSocket(int) API.
 */
class RfcommTransport(
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : BudsTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val label: String = "RFCOMM ${device.address}"

    @SuppressLint("MissingPermission")
    override fun open() {
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }

        var lastError: Exception? = null

        val sdpAttempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )

        for ((label, factory) in sdpAttempts) {
            try {
                log("Trying $label connection…")
                attach(connectWithTimeout(factory))
                log("Connected using $label")
                return
            } catch (e: Exception) {
                log("$label failed: ${e.message ?: e.javaClass.simpleName}")
                lastError = e
            }
        }

        for (channel in FALLBACK_CHANNELS) {
            try {
                log("Trying RFCOMM channel $channel…")
                val socket = channelSocket(channel)
                attach(connectWithTimeout { socket })
                log("Connected using RFCOMM channel $channel")
                return
            } catch (e: Exception) {
                log("Channel $channel failed: ${e.message ?: e.javaClass.simpleName}")
                lastError = e
            }
        }

        throw IOException(
            "Could not open an RFCOMM connection. Is Nothing X running? " +
                "Try unpairing/re-pairing the earbuds or reconnecting them.",
            lastError,
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectWithTimeout(factory: () -> BluetoothSocket): BluetoothSocket {
        val candidate = factory()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit { candidate.connect() }
            try {
                future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return candidate
            } catch (e: TimeoutException) {
                runCatching { candidate.close() }
                throw IOException("Connection timed out after ${CONNECT_TIMEOUT_SECONDS}s", e)
            } catch (e: Exception) {
                runCatching { candidate.close() }
                throw e
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Suppress("PrivateApi")
    private fun channelSocket(channel: Int): BluetoothSocket {
        val method = BluetoothDevice::class.java.getMethod(
            "createRfcommSocket",
            Integer.TYPE,
        )
        return method.invoke(device, channel) as? BluetoothSocket
            ?: throw IOException("createRfcommSocket($channel) returned null")
    }

    private fun attach(newSocket: BluetoothSocket) {
        socket = newSocket
        input = newSocket.inputStream
        output = newSocket.outputStream
    }

    override fun read(buffer: ByteArray): Int {
        val stream = input ?: throw IOException("Transport is not open")
        return stream.read(buffer)
    }

    override fun write(data: ByteArray) {
        val stream = output ?: throw IOException("Transport is not open")
        synchronized(this) {
            stream.write(data)
            stream.flush()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        Log.d(TAG, "Closed $label")
    }

    companion object {
        private const val TAG = "RfcommTransport"
        private const val CONNECT_TIMEOUT_SECONDS = 12L
        val SPP_UUID: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")
        val FALLBACK_CHANNELS = intArrayOf(15, 16, 28)
    }
}
