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
// BoxService removed
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions

/**
 * Реальный VPN-сервис: поднимает tun-интерфейс через android.net.VpnService
 * и передаёт его файловый дескриптор движку sing-box (libbox).
 *
 * ВАЖНО про PlatformInterface: это интерфейс, который generated-биндинг gomobile
 * ожидает от Kotlin-стороны (см. https://github.com/SagerNet/sing-box-for-android,
 * файл bg/PlatformInterfaceWrapper.kt + bg/VPNService.kt — код openTun() ниже
 * практически 1-в-1 оттуда). Набор методов может чуть отличаться между версиями
 * libbox — если Android Studio подсветит "class is not abstract and does not
 * implement member", жми Alt+Enter → "Implement members", он доставит недостающие
 * заглушки, а мы допишем их логику.
 */
class CoreVpnService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "CoreVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.vpnclient.app.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnclient.app.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"

        /** Удобный способ запустить/остановить сервис из UI. */
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
        try {
            boxService?.close()
        } catch (e: Exception) {
            Log.e(TAG, "ошибка при остановке", e)
        }
        boxService = null
        try {
            tunFd?.close()
        } catch (_: Exception) {
        }
        tunFd = null
        VpnStatus.update(VpnState.DISCONNECTED)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            boxService?.close()
        } catch (_: Exception) {
        }
        boxService = null
        VpnStatus.update(VpnState.DISCONNECTED)
        super.onDestroy()
    }

    override fun onRevoke() {
        // Пользователь отозвал разрешение VPN в системных настройках.
        stopVpn()
    }

    // ---------- PlatformInterface: сюда движок sing-box дёргает нас ----------

    /** Открывает tun-интерфейс. Код ниже — адаптация openTun() из официального SFA. */
    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: нет разрешения на VPN (VpnService.prepare)")

        val builder = Builder()
            .setSession("VpnClient")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            // Само приложение не должно попадать в свой же туннель.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
        }

        val pfd = builder.establish()
            ?: error("android: не удалось поднять VPN-интерфейс (приложение не подготовлено / отозвано)")
        tunFd = pfd
        return pfd.fd
    }

    override fun writeLog(message: String) {
        Log.d(TAG, message)
    }

    override fun useProcFS(): Boolean = true

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformDefaultInterfaceMonitor(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
        // На Android нет прямого системного API для этого — no-op.
    }

    // ---------- Уведомление foreground-сервиса ----------

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VpnClient")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
