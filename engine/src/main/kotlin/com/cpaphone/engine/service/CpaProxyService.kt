package com.cpaphone.engine.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cpaphone.engine.server.LocalProxyServer
import com.cpaphone.engine.server.ProxyServerStateListener

/**
 * Android 前台保活服务 (Foreground Service)
 * 维持 CPAphone 本地代理长效监听，绑定常驻通知栏，获取 Partial Wakelock，
 * 并具备网络漫游自动感知与自愈能力（Wi-Fi / 蜂窝网络切换自适应）。
 */
class CpaProxyService : Service(), ProxyServerStateListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val CHANNEL_ID = "cpa_proxy_service_channel"
        const val NOTIFICATION_ID = 8317

        const val ACTION_START = "com.cpaphone.action.START"
        const val ACTION_STOP = "com.cpaphone.action.STOP"

        @Volatile
        var activeServerInstance: LocalProxyServer? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        registerNetworkWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val notification = buildNotification("正在运行", "监听端口: 8317")
                startForeground(NOTIFICATION_ID, notification)
                activeServerInstance?.setStateListener(this)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkWatcher()
        stopProxy()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopProxy() {
        activeServerInstance?.stop()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CPAphone:ProxyWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 最长 12 小时自恢复
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    private fun registerNetworkWatcher() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 网络切换自愈：更新通知栏状态
                    val isRunning = activeServerInstance?.isServerRunning() == true
                    if (isRunning) {
                        val notification = buildNotification("运行中", "网络已连接 · 监听: 8317")
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, notification)
                    }
                }

                override fun onLost(network: Network) {
                    val isRunning = activeServerInstance?.isServerRunning() == true
                    if (isRunning) {
                        val notification = buildNotification("运行中 (离线)", "网络连接中断，等待重连...")
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {
            // 忽略权限或平台版本异常
        }
    }

    private fun unregisterNetworkWatcher() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CPAphone 本地代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 CPAphone 本地嵌入式代理服务常驻运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, detailText: String): Notification {
        val stopIntent = Intent(this, CpaProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CPAphone 本地代理: $statusText")
            .setContentText(detailText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止服务", stopPendingIntent)
            .build()
    }

    override fun onStateChanged(isRunning: Boolean, host: String, port: Int) {
        val statusText = if (isRunning) "运行中" else "已停止"
        val detailText = if (isRunning) "监听地址: $host:$port" else "服务未启动"
        val notification = buildNotification(statusText, detailText)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onMetricsUpdated(totalRequests: Long, currentQps: Double) {
        val notification = buildNotification("运行中", "已处理请求: $totalRequests 次")
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
}
