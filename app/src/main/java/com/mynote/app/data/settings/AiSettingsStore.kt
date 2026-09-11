package com.mynote.app.data.settings

import android.content.Context

class AiSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun selectedServiceId(): String? = prefs.getString(KEY_SELECTED_SERVICE, null)

    fun setSelectedServiceId(id: String) {
        prefs.edit().putString(KEY_SELECTED_SERVICE, id).apply()
    }

    fun isPrivacyAccepted(serviceId: String): Boolean =
        prefs.getBoolean(privacyKey(serviceId), false)

    fun acceptPrivacy(serviceId: String) {
        prefs.edit().putBoolean(privacyKey(serviceId), true).apply()
    }

    private fun privacyKey(serviceId: String) = "privacy_accepted_$serviceId"

    private companion object {
        const val KEY_SELECTED_SERVICE = "selected_service_id"
    }
}
