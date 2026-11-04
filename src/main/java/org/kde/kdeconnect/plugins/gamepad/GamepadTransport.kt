/*
 * SPDX-FileCopyrightText: 2026 Powerful Control Station contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.gamepad

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class PadState(
    val buttons: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f
)

internal object PadProtocol {
    const val PREFIX = "PCS-GP1"
    const val PORT = 28442
    fun axis(value: Float): Int = (value.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
    fun trigger(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()
    fun body(id: String, sequence: Long, s: PadState): String =
        "$PREFIX|STATE|$id|$sequence|\${s.buttons}|\${axis(s.lx)}|\${axis(s.ly)}|\${axis(s.rx)}|\${axis(s.ry)}|\${trigger(s.lt)}|\${trigger(s.rt)}"
    fun signature(body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Independent gamepad input path, separate from KDE Connect's keyboard/mouse packet queue. */
internal class GamepadTransport(
    private val context: Context,
    private val status: (String) -> Unit
) : Closeable {
    private val prefs = context.getSharedPreferences("pcs_gamepad", Context.MODE_PRIVATE)
    private val clientId = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("client_id", it).apply()
    }
    private val main = Handler(Looper.getMainLooper())
    private val tasks = Executors.newSingleThreadScheduledExecutor()
    private val reader = Executors.newSingleThreadExecutor()
    private val socket = DatagramSocket().also {
        it.broadcast = true
        it.soTimeout = 1000
    }
    @Volatile private var secret = prefs.getString("pair_token", "") ?: ""
    @Volatile private var remote: InetSocketAddress? = null
    @Volatile private var manualAddress = prefs.getString("manual_ip", "") ?: ""
    @Volatile private var state = PadState()
    @Volatile private var closing = false
    @Volatile private var lastResponse = 0L
    private var lastHello = 0L
    private var sequence = 0L

    init {
        announce("Searching for Windows gamepad companion…")
        tasks.scheduleAtFixedRate({
            if (closing) return@scheduleAtFixedRate
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastResponse > 2500L && remote != null) {
                    remote = null
                    announce("Connection lost — searching…")
                }
                if (now - lastHello > 1000L) {
                    sendHello()
                    lastHello = now
                }
                remote?.let { sendState(it, state) }
            } catch (_: Exception) { announce("Network unavailable — retrying") }
        }, 0, 33, TimeUnit.MILLISECONDS)
        reader.execute {
            val buffer = ByteArray(1024)
            while (!closing) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val parts = message.split('|')
                    if (parts.size < 3 || parts[0] != PadProtocol.PREFIX) continue
                    val sender = InetSocketAddress(packet.address, PadProtocol.PORT)
                    when (parts[1]) {
                        "OFFER" -> announce("Approve \${parts[2]} on Windows to pair")
                        "PAIRED" -> if (parts.size == 4 && validSecret(parts[3])) {
                            secret = parts[3]
                            prefs.edit().putString("pair_token", secret).apply()
                            remote = sender
                            lastResponse = SystemClock.elapsedRealtime()
                            announce("Paired with \${parts[2]}")
                        }
                        "READY" -> if (parts.size == 4 && secret.isNotEmpty() &&
                            PadProtocol.signature("\${PadProtocol.PREFIX}|READY|\${parts[2]}", secret) == parts[3]) {
                            remote = sender
                            lastResponse = SystemClock.elapsedRealtime()
                            announce("Connected: \${parts[2]} • Xbox controller")
                        }
                        "ERROR" -> announce(parts.drop(2).joinToString("|").take(140))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!closing) announce("Waiting for companion…")
                }
            }
        }
    }

    fun updateState(next: PadState) { state = next }

    fun setManualIp(ip: String) {
        manualAddress = ip.trim()
        remote = null
        prefs.edit().putString("manual_ip", manualAddress).apply()
        announce(if (manualAddress.isBlank()) "Automatic discovery" else "Connecting to $manualAddress")
    }

    fun getManualIp(): String = manualAddress

    private fun sendHello() {
        val challenge = SystemClock.elapsedRealtime().toString()
        val body = "\${PadProtocol.PREFIX}|HELLO|$clientId|$challenge"
        val proof = if (secret.isNotEmpty()) PadProtocol.signature(body, secret) else ""
        val message = "$body|$proof"
        val targets = mutableSetOf<InetAddress>()
        if (manualAddress.isNotBlank()) {
            try { targets.add(InetAddress.getByName(manualAddress)) } catch (_: Exception) { }
        } else {
            targets.add(InetAddress.getByName("255.255.255.255"))
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val dhcp = wifi.dhcpInfo
                if (dhcp != null && dhcp.netmask != 0) {
                    val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                    targets.add(InetAddress.getByAddress(byteArrayOf(
                        broadcast.toByte(), (broadcast ushr 8).toByte(),
                        (broadcast ushr 16).toByte(), (broadcast ushr 24).toByte()
                    )))
                }
            } catch (_: Exception) { }
            remote?.let { targets.add(it.address) }
        }
        targets.forEach { address -> send(message, InetSocketAddress(address, PadProtocol.PORT)) }
    }

    private fun sendState(address: InetSocketAddress, current: PadState) {
        if (secret.isEmpty()) return
        val body = PadProtocol.body(clientId, ++sequence, current)
        send("$body|\${PadProtocol.signature(body, secret)}", address)
    }

    private fun send(message: String, address: InetSocketAddress) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, address))
    }

    private fun announce(message: String) { main.post { if (!closing) status(message) } }

    private fun validSecret(token: String): Boolean = try {
        Base64.getUrlDecoder().decode(token).size == 32
    } catch (_: IllegalArgumentException) { false }

    override fun close() {
        if (closing) return
        remote?.let { address ->
            repeat(3) { try { sendState(address, PadState()) } catch (_: Exception) { } }
        }
        closing = true
        tasks.shutdownNow()
        socket.close()
        reader.shutdownNow()
    }
}
