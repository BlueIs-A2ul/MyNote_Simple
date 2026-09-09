package com.mynote.app

import android.app.Application
import com.mynote.app.di.AppContainer

class MyNoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
