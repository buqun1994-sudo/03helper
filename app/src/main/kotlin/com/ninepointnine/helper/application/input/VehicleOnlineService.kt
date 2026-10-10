package com.ninepointnine.helper.application.input

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ninepointnine.helper.InstallerApplication
import com.ninepointnine.helper.MainActivity
import com.ninepointnine.helper.R
import com.ninepointnine.helper.domain.session.InstallationSessionCommand
import com.ninepointnine.helper.domain.session.DeviceConnectionStatus
import com.ninepointnine.helper.domain.input.RemoteInputStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Resource host only: connection, keyboard and send state remain in InstallerRuntime/session. */
class VehicleOnlineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as InstallerApplication).installerRuntime
    private lateinit var notifications: NotificationManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastOnlineText: String? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val remoteInputNoticeTracker = RemoteInputNoticeEdgeTracker()

    @Suppress("DEPRECATION")
    private fun acquireNetworkLease() {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        if (wifi?.isWifiEnabled == true) {
            wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "03helper:vehicle-session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
        runCatching { acquireNetworkLease() }
        val notification = onlineNotification(getString(R.string.online_connecting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ONLINE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ONLINE_ID, notification)
        scope.launch {
            runtime.session.snapshots
                .map { snapshot ->
                    val input = snapshot.remoteInput
                    RemoteInputNoticeProjection(
                        connected = snapshot.device?.connectionStatus == DeviceConnectionStatus.CONFIRMED,
                        connectionGeneration = input.connectionGeneration,
                        hasState = input.hasState,
                        keyboardOpen = input.keyboardOpen,
                        available = input.available,
                        localSending = input.status == RemoteInputStatus.SENDING,
                        vehicleSending = input.vehicleSending,
                        deviceBusy = input.deviceBusy,
                        unknownResult = input.status == RemoteInputStatus.UNKNOWN_RESULT,
                        pageVisible = RemoteInputPageVisibility.visible,
                    )
                }
                .distinctUntilChanged()
                .collect { projection ->
                    val text = getString(if (projection.connected) R.string.online_connected else R.string.online_reconnecting)
                    if (lastOnlineText != text) {
                        notifications.notify(ONLINE_ID, onlineNotification(text))
                        lastOnlineText = text
                    }
                    when (remoteInputNoticeTracker.update(projection)) {
                        RemoteInputNoticeAction.SHOW -> {
                            if (canNotify()) notifications.notify(INPUT_ID, inputNotification())
                        }

                        RemoteInputNoticeAction.CANCEL -> notifications.cancel(INPUT_ID)
                        RemoteInputNoticeAction.NONE -> Unit
                    }
                    if (RemoteInputPageVisibility.visible) notifications.cancel(INPUT_ID)
                }
        }
        // Network callbacks are hints. InstallerRuntime verifies the same device identity.
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runtime.onNetworkAvailable() }
            override fun onLost(network: Network) { runtime.onNetworkLost() }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            runtime.dispatch(InstallationSessionCommand.DisconnectDevice)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_HIDE_INPUT) notifications.cancel(INPUT_ID)
        if (intent == null) {
            if (runtime.onlineTarget() == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            runtime.onNetworkAvailable()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        notifications.cancel(INPUT_ID)
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        notifications.createNotificationChannel(NotificationChannel(ONLINE_CHANNEL, getString(R.string.online_channel), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(INPUT_CHANNEL, getString(R.string.input_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun openPage(input: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (input) action = MainActivity.ACTION_OPEN_INPUT
        }
        return PendingIntent.getActivity(this, if (input) 2 else 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun onlineNotification(text: String): Notification {
        val disconnect = PendingIntent.getService(this, 3, Intent(this, javaClass).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ONLINE_CHANNEL)
            .setSmallIcon(R.drawable.helper_logo).setContentTitle(getString(R.string.app_name))
            .setContentText(text).setContentIntent(openPage(false)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.online_disconnect), disconnect).build()
    }
    private fun inputNotification(): Notification = NotificationCompat.Builder(this, INPUT_CHANNEL)
        .setSmallIcon(R.drawable.helper_logo).setContentTitle(getString(R.string.input_notification_title))
        .setContentText(getString(R.string.input_notification_message)).setContentIntent(openPage(true))
        .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    private fun canNotify(): Boolean = notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    companion object {
        private const val ONLINE_CHANNEL = "vehicle_online"
        private const val INPUT_CHANNEL = "vehicle_input"
        private const val ONLINE_ID = 3101
        private const val INPUT_ID = 3102
        const val ACTION_DISCONNECT = "com.ninepointnine.helper.DISCONNECT_VEHICLE"
        private const val ACTION_HIDE_INPUT = "com.ninepointnine.helper.HIDE_INPUT_NOTICE"
        fun setHosting(context: Context, hosting: Boolean) {
            val intent = Intent(context, VehicleOnlineService::class.java)
            if (hosting) androidx.core.content.ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
        }
        fun hideInputNotice(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(INPUT_ID) }
    }
}

/** Lifecycle marker only; it never carries keyboard/connection state. */
object RemoteInputPageVisibility { @Volatile var visible: Boolean = false }
