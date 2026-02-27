package com.soulon.app.wallet

import android.content.Context
import android.content.SharedPreferences
import com.soulon.app.security.SecurePrefs
import java.security.MessageDigest

object WalletScope {
    private const val WALLET_PREFS = "wallet_prefs"
    private const val KEY_PUBLIC_KEY_HEX = "public_key"

    fun currentWalletAddress(context: Context): String? {
        val publicKeyHex = runCatching {
            SecurePrefs.create(context, WALLET_PREFS).getString(KEY_PUBLIC_KEY_HEX, null)
        }.getOrNull()
            ?: context.getSharedPreferences(WALLET_PREFS, Context.MODE_PRIVATE).getString(KEY_PUBLIC_KEY_HEX, null)
            ?: return null
        val bytes = runCatching { hexToBytes(publicKeyHex) }.getOrNull() ?: return null
        return runCatching { org.bitcoinj.core.Base58.encode(bytes) }.getOrNull()
    }

    fun scopeId(walletAddress: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(walletAddress.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.take(16)
    }

    fun scopedPrefs(context: Context, baseName: String, walletAddress: String?): SharedPreferences {
        if (walletAddress.isNullOrBlank()) {
            return context.getSharedPreferences(baseName, Context.MODE_PRIVATE)
        }
        val id = scopeId(walletAddress)
        return context.getSharedPreferences("${baseName}_$id", Context.MODE_PRIVATE)
    }

    fun scopedPrefs(context: Context, baseName: String): SharedPreferences {
        return scopedPrefs(context, baseName, currentWalletAddress(context))
    }

    fun scopedName(baseName: String, walletAddress: String?): String {
        if (walletAddress.isNullOrBlank()) return baseName
        return "${baseName}_${scopeId(walletAddress)}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        if (len % 2 != 0) throw IllegalArgumentException("Invalid hex length")
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val b = clean.substring(i, i + 2).toInt(16).toByte()
            out[i / 2] = b
            i += 2
        }
        return out
    }
}
