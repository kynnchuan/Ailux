package com.ailux.chatdemo

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user preferences across app restarts.
 *
 * Currently stores:
 * - Last selected [ProviderMode]
 * - Last used model file path (for LOCAL_RUNTIME mode)
 */
object AppPreferences {

    private const val PREFS_NAME = "ailux_chat_demo_prefs"
    private const val KEY_PROVIDER_MODE = "provider_mode"
    private const val KEY_MODEL_PATH = "model_path"

    private lateinit var prefs: SharedPreferences

    /**
     * Initialize preferences. Must be called once at app startup
     * (before reading any values).
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Save the selected provider mode. */
    var savedProviderMode: ProviderMode
        get() {
            val name = prefs.getString(KEY_PROVIDER_MODE, null)
            return try {
                if (name != null) ProviderMode.valueOf(name) else ProviderMode.MOCK
            } catch (_: IllegalArgumentException) {
                ProviderMode.MOCK
            }
        }
        set(value) {
            prefs.edit().putString(KEY_PROVIDER_MODE, value.name).apply()
        }

    /** Save the model file path used in LOCAL_RUNTIME mode. */
    var savedModelPath: String?
        get() = prefs.getString(KEY_MODEL_PATH, null)
        set(value) {
            prefs.edit().apply {
                if (value != null) putString(KEY_MODEL_PATH, value)
                else remove(KEY_MODEL_PATH)
            }.apply()
        }
}
