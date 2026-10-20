package com.mahudesh.tvremote

import android.app.Application
import com.mahudesh.tvremote.tv.AdbClient
import com.mahudesh.tvremote.tv.TlsIdentity
import com.mahudesh.tvremote.tv.TvController

class TvRemoteApp : Application() {
    lateinit var controller: TvController
        private set
    lateinit var updater: com.mahudesh.tvremote.data.Updater
        private set

    override fun onCreate() {
        super.onCreate()
        TlsIdentity.init(this)
        AdbClient.init(this)
        controller = TvController(this)
        updater = com.mahudesh.tvremote.data.Updater(this)
    }
}
