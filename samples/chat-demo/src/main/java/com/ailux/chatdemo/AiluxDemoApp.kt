package com.ailux.chatdemo

import android.app.Application

/**
 * Demo Application class.
 *
 * Client construction logic has been moved to [ChatClientManager].
 * This Application subclass only bootstraps the manager on startup.
 */
class AiluxDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ChatClientManager.initialize(context = this)
    }
}
