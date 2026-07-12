package com.vpnclient.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnclient.app.MainActivity
import com.vpnclient.app.core.ConfigBuilder
import com.vpnclient.app.core.ParsedServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession

class CoreVpnService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "CoreVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.vpnclient.app.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnclient.app.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"

        fun start(context: Context, server: ParsedServer) {
            val config = ConfigBuilder.build(server)
            val intent = Intent(context, CoreVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_CONFIG_JSON, config)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CoreVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }
    }

    private var boxService: io.nekohasekai.libbox.BoxService? = null
    private var tunFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        Libbox.setup(filesDir.absolutePath, cacheDir.absolutePath, "", false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (config.isNullOrBlank()) {
                    VpnStatus.reportError("Пустой конфиг")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(config)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startVpn(configJson: String) {
        VpnStatus.update(VpnState.CONNECTING)
        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))
        try {
            val service = Libbox.newBoxService(configJson, this)
            service.start()
            boxService = service
            VpnStatus.update(VpnState.CONNECTED)
            updateNotification("Подключено")
        } catch (e: Exception) {
            Log.e(TAG, "не удалось запустить sing-box", e)
            VpnStatus.reportError(e.message ?: "Ошибка запуска VPN")
            stopVpn()
        }
    }

    private fun stopVpn() {
        VpnStatus.update(VpnState.STOPPING)
        try { boxService?.close() } catch (e: Exception) { Log.e(TAG, "ошибка при остановке", e) }
        boxService = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        VpnStatus.update(VpnState.DISCONNECTED)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        try { boxService?.close() } catch (_: Exception) {}
        boxService = null
        VpnStatus.update(VpnState.DISCONNECTED)
        super.onDestroy()
    }

    override fun onRevoke() { stopVpn() }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("нет разрешения VPN")
        val builder = Builder().setSession("VpnClient").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        val inet4 = options.inet4Address
        while (inet4.hasNext()) { val a = inet4.next(); builder.addAddress(a.address(), a.prefix()) }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) { val a = inet6.next(); builder.addAddress(a.address(), a.prefix()) }
        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }
        val pfd = builder.establish() ?: error("не удалось поднять VPN-интерфейс")
        tunFd = pfd
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = true
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun tailscaleHostname(): String = ""
    override fun clearDNSCache() {}
    override fun checkPlatformShell() {}
    override fun createBridge(options: BridgeOptions): BridgeSession = throw UnsupportedOperationException()
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String): Int = -1
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VpnClient").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}