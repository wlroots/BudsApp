package io.github.wlroots.budsapp

import android.app.Application
import io.github.wlroots.budsapp.bt.BudsRepository

class BudsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BudsRepository.initialize(this)
    }
}
