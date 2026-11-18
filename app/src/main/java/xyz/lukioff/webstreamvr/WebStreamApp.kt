package xyz.lukioff.webstreamvr

import android.app.Application
import xyz.lukioff.webstreamvr.diag.AppLog

class WebStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
