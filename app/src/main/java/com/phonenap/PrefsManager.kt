package com.phonenap

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrefsManager(context: Context) {

    // Application context prevents Activity/Service leaks
    private val appContext = context.applicationContext

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "phonenap_secure",
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Binary file for face vectors — EncryptedSharedPreferences is not suited
    // for ~900 KB of float data (25 vectors × 9216 floats × 4 bytes each).
    private val vectorFile get() = File(appContext.filesDir, "kid_face_vectors.bin")

    // ── Boolean flags ─────────────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)
    fun markSetupComplete()        = prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
    fun isActive(): Boolean        = prefs.getBoolean(KEY_ACTIVE, false)
    fun setActive(on: Boolean)     = prefs.edit().putBoolean(KEY_ACTIVE, on).apply()
    fun hasKidFace(): Boolean      = vectorFile.exists() && vectorFile.length() > 8L

    // ── Face vector storage ───────────────────────────────────────────────────

    /**
     * Persist all 25 (or however many were captured) LBPH vectors as a flat
     * binary file:
     *   [count: Int32][vecSize: Int32][float × vecSize × count]
     */
    fun saveKidFaceVectors(vecs: List<FloatArray>) {
        if (vecs.isEmpty()) return
        val vecSize    = vecs[0].size
        val totalBytes = 8 + vecs.size * vecSize * 4
        val buf = ByteBuffer.allocate(totalBytes).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(vecs.size)
            putInt(vecSize)
            for (v in vecs) for (f in v) putFloat(f)
        }
        vectorFile.writeBytes(buf.array())
    }

    /** Read all enrolled face vectors, or empty list if none saved yet. */
    fun getKidFaceVectors(): List<FloatArray> {
        val file = vectorFile
        if (!file.exists() || file.length() < 9L) return emptyList()
        return try {
            val bytes = file.readBytes()
            val buf   = ByteBuffer.wrap(bytes).apply { order(ByteOrder.LITTLE_ENDIAN) }
            val count   = buf.int
            val vecSize = buf.int
            if (count <= 0 || vecSize <= 0) return emptyList()
            List(count) { FloatArray(vecSize) { buf.float } }
        } catch (e: Exception) {
            android.util.Log.e("PrefsManager", "Failed to read face vectors: ${e.message}")
            emptyList()
        }
    }

    // ── Clear everything ──────────────────────────────────────────────────────

    fun clearAll() {
        prefs.edit().clear().apply()
        vectorFile.delete()
    }

    companion object {
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_ACTIVE     = "active"
    }
}
