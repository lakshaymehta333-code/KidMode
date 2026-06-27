package com.kidmode

import android.content.Context
import java.security.MessageDigest

/**
 * Thin wrapper around SharedPreferences.
 * PIN is stored as a SHA-256 hash — never in plaintext.
 */
object PrefsManager {

    private const val PREFS_NAME = "kidmode_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_ACTIVE = "kid_mode_active"

    // ── PIN ──────────────────────────────────────────────────────────────────

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin) == stored
    }

    fun hasPin(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    // ── Session state ─────────────────────────────────────────────────────────

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
