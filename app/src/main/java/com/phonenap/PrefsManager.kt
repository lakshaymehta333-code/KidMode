package com.phonenap

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun saveParentFaceVector(vec: FloatArray) = saveVec(KEY_PARENT, vec)
    fun saveKidFaceVector(vec: FloatArray)    = saveVec(KEY_KID, vec)
    fun getParentFaceVector(): FloatArray?    = getVec(KEY_PARENT)
    fun getKidFaceVector(): FloatArray?       = getVec(KEY_KID)
    fun hasParentFace(): Boolean              = prefs.contains(KEY_PARENT)
    fun hasKidFace(): Boolean                 = prefs.contains(KEY_KID)
    fun clearAll()                            = prefs.edit().clear().apply()

    private fun saveVec(key: String, vec: FloatArray) =
        prefs.edit().putString(key, vec.joinToString(",")).apply()

    private fun getVec(key: String): FloatArray? =
        prefs.getString(key, null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_ACTIVE     = "active"
        private const val KEY_PARENT     = "parent_face"
        private const val KEY_KID        = "kid_face"
    }
}
