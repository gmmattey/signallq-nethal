package com.nethal.lab

import android.app.Application
import com.nethal.core.consent.ConsentRepository
import com.nethal.lab.data.consent.ConsentDataStoreRepository
import com.nethal.lab.data.consent.consentDataStore

class NetHalApplication : Application() {

    lateinit var consentRepository: ConsentRepository
        private set

    override fun onCreate() {
        super.onCreate()
        consentRepository = ConsentDataStoreRepository(consentDataStore)
    }
}
