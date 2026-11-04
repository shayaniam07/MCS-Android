package com.mcs.android

import android.content.Context
import android.provider.Settings
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Compatible with desktop licensing.py key layout: version(1), device(8), expires(4), issued(4), Ed25519 signature(64). */
class LicenseManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("mcs_license", Context.MODE_PRIVATE)
    private val product = "MasteryOfClipsWithShayan"

    val deviceBytes: ByteArray by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        MessageDigest.getInstance("SHA-256").digest("$androidId|$product".toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)
    }

    val deviceId: String get() = deviceBytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        .chunked(4).joinToString("-")

    private fun loadPublicKey(): ByteArray? = runCatching {
        val content = context.assets.open("license_public.key").bufferedReader().use { it.readText() }.trim()
        Base64.getDecoder().decode(content)
    }.getOrNull()?.takeIf { it.size == 32 }

    fun hasPublicKey(): Boolean = loadPublicKey() != null
    fun savedKey(): String = prefs.getString("license_key", "") ?: ""

    data class Status(val valid: Boolean, val message: String, val developerMode: Boolean = false)

    fun verify(key: String = savedKey()): Status {
        val pub = loadPublicKey()
        if (pub == null) {
            return if (BuildConfig.DEBUG) Status(true, "DEBUG preview only — no public key installed", true)
            else Status(false, "License public key missing: bundle your existing PUBLIC key before a release.")
        }
        if (key.isBlank()) return Status(false, "Activate using a license issued for this Android device ID.")
        return try {
            val decoded = decodeBase32(key)
            if (decoded.size != 81) return Status(false, "Invalid license length")
            val payload = decoded.copyOfRange(0, 17)
            val signature = decoded.copyOfRange(17, 81)
            val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(pub, 0)) }
            verifier.update(payload, 0, payload.size)
            if (!verifier.verifySignature(signature)) return Status(false, "License signature invalid")
            if (payload[0].toInt() != 1) return Status(false, "Unsupported key version")
            if (!payload.copyOfRange(1, 9).contentEquals(deviceBytes)) return Status(false, "License belongs to another device")
            val bytes = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            bytes.position(9)
            val expires = bytes.int.toLong() and 0xffffffffL
            val issued = bytes.int.toLong() and 0xffffffffL
            val now = System.currentTimeMillis() / 1000
            val lastSeen = prefs.getLong("last_seen", 0)
            if (issued > now + 86400) return Status(false, "Device clock appears incorrect")
            if (now < lastSeen - 86400) return Status(false, "Device clock was moved backward")
            if (now > expires) return Status(false, "License expired")
            Status(true, "Licensed · ${((expires - now + 86399) / 86400)} days remaining")
        } catch (_: Exception) { Status(false, "The activation key could not be decoded") }
    }

    fun activate(key: String): Status {
        val status = verify(key)
        if (status.valid && !status.developerMode) {
            prefs.edit().putString("license_key", key.trim())
                .putLong("last_seen", System.currentTimeMillis() / 1000).apply()
        }
        return status
    }

    fun touchIfValid() {
        val status = verify()
        if (status.valid && !status.developerMode) {
            prefs.edit().putLong("last_seen", System.currentTimeMillis() / 1000).apply()
        }
    }

    private fun decodeBase32(text: String): ByteArray {
        val symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = text.uppercase().filter { it in symbols }
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>()
        for (ch in clean) {
            value = (value shl 5) or symbols.indexOf(ch)
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((value shr bits) and 0xff).toByte())
                value = value and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
