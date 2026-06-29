package com.phonenap

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrefsManager(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "phonenap_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)
    fun markSetupComplete()        = prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
    fun isActive(): Boolean        = prefs.getBoolean(KEY_ACTIVE, false)
    fun setActive(on: Boolean)     = prefs.edit().putBoolean(KEY_ACTIVE, on).apply()

    fun saveKidFaceVector(vec: FloatArray) {
        val buf = ByteBuffer.allocate(vec.size * 4).apply {
            order(ByteOrder.nativeOrder())
            vec.forEach { putFloat(it) }
        }
        prefs.edit().putString(KEY_KID, Base64.encodeToString(buf.array(), Base64.NO_WRAP)).apply()
    }

    fun getKidFaceVector(): FloatArray? {
        val b64   = prefs.getString(KEY_KID, null) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val buf   = ByteBuffer.wrap(bytes).apply { order(ByteOrder.nativeOrder()) }
        return FloatArray(bytes.size / 4) { buf.getFloat() }.takeIf { it.isNotEmpty() }
    }

    fun hasKidFace(): Boolean = prefs.contains(KEY_KID)
    fun clearAll()            = prefs.edit().clear().apply()

    companion object {
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_ACTIVE     = "active"
        private const val KEY_KID        = "kid_face"
    }
}
