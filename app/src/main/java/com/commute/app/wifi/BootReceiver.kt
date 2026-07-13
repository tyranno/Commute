package com.commute.app.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.commute.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts Wi-Fi monitoring after a reboot if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SettingsRepository(appContext).monitoringEnabled.first()
                if (enabled) {
                    WifiMonitorService.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
