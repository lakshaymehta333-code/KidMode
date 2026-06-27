package com.phonenap

import android.content.Context

object PrefsManager {
    private const val PREFS = "phonenap"
    private const val KEY_ACTIVE = "active"

    fun isActive(ctx: Context) =
        ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ACTIVE, false)

    fun setActive(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ACTIVE, v).apply()
}
