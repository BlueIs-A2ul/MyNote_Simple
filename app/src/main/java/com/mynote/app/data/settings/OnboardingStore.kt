package com.mynote.app.data.settings

import android.content.Context

/** 首次使用引导状态：欢迎笔记只生成一次，用户删除后不再重建。 */
class OnboardingStore(context: Context) {

    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun isWelcomeSeeded(): Boolean = prefs.getBoolean(KEY_WELCOME_SEEDED, false)

    fun markWelcomeSeeded() {
        prefs.edit().putBoolean(KEY_WELCOME_SEEDED, true).apply()
    }

    private companion object {
        const val KEY_WELCOME_SEEDED = "welcome_seeded"
    }
}
