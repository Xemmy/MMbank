package com.xemmy.mmbank

import android.app.Application
import com.xemmy.mmbank.di.AppContainer

class MMBankApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
