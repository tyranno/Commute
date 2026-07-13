package com.commute.app.wifi

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.commute.app.data.CommuteDatabase
import com.commute.app.data.CommuteEvent
import com.commute.app.data.CommuteEventType
import com.commute.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that watches Wi-Fi connectivity and records an ARRIVE/LEAVE
 * event whenever the device connects to / disconnects from the registered company SSID.
 */
class WifiMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var settingsRepository: SettingsRepository
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        settingsRepository = SettingsRepository(applicationContext)
        ensureNotificationChannels(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification(this, "출퇴근 감지 중"))
        if (networkCallback == null) {
            registerNetworkCallback()
        }
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onWifiObserved(extractSsid(capabilities) ?: currentWifiSsid(applicationContext))
            }

            override fun onLost(network: Network) {
                onWifiObserved(null)
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun onWifiObserved(connectedSsid: String?) {
        serviceScope.launch {
            stateMutex.withLock {
                val companySsid = settingsRepository.companySsid.first()
                if (companySsid.isNullOrBlank()) return@withLock

                val wasAtWork = settingsRepository.isAtWork.first()
                val isNowAtCompanyWifi = connectedSsid == companySsid

                if (isNowAtCompanyWifi && !wasAtWork) {
                    recordEvent(CommuteEventType.ARRIVE, companySsid)
                    settingsRepository.setIsAtWork(true)
                    showEventNotification(applicationContext, "출근 기록됨", "회사 와이파이($companySsid) 연결 감지")
                } else if (!isNowAtCompanyWifi && wasAtWork) {
                    recordEvent(CommuteEventType.LEAVE, companySsid)
                    settingsRepository.setIsAtWork(false)
                    showEventNotification(applicationContext, "퇴근 기록됨", "회사 와이파이($companySsid) 연결 해제 감지")
                }
            }
        }
    }

    private suspend fun recordEvent(type: CommuteEventType, ssid: String) {
        CommuteDatabase.getInstance(applicationContext).commuteDao().insert(
            CommuteEvent(type = type, ssid = ssid, timestamp = System.currentTimeMillis())
        )
    }

    override fun onDestroy() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiMonitorService::class.java))
        }
    }
}
