package com.relay.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * VPN — إطار عميل احترافي، وليس خادم VPN مجانياً.
 *
 * ما يفعله هذا الملف فعلاً:
 *  - يخزّن خوادم WireGuard/OpenVPN التي يملكها المستخدم (ملف ‎.conf / ‎.ovpn)
 *    مشفّرة بـ Android Keystore عبر SecretStore، ويتحقق من صيغتها قبل أي اتصال.
 *  - يوفّر Kill Switch كإعداد في التطبيق + زر يفتح إعدادات VPN في النظام
 *    لتفعيل "Always-on VPN" و"Block connections without VPN" — وهذا هو الـ Kill Switch
 *    الحقيقي في أندرويد، ولا يمكن لأي تطبيق فرضه من الداخل وحدها.
 *  - يجهّز VpnService وإذن النظام (VpnService.prepare) وواجهة TUN.
 *
 * ما لا يفعله — بصراحة، حتى لا يُباع وهم:
 *  - لا يحتوي على خوادم VPN ولا مفاتيح ولا نطاق ترددي. لا يوجد VPN يعمل بلا خادم
 *    طرف آخر يستقبل الحزم ويرسلها إلى الإنترنت.
 *  - محرك النفق WireGuard نفسه غير مضمّن في هذا المشروع (يحتاج مكتبة
 *    WireGuard الرسمية / Go backend). لذلك يرفض RelayVpnService بدء نفق
 *    وهمي: إن بدأ TUN بلا محرك إعادة توجيه سينقطع الإنترنت كله.
 *    انظر TUNNEL_ENGINE_AVAILABLE أدناه وREADME_VPN_AI.md للخطوة المطلوبة،
 *    واستخدم زر "تصدير ‎.conf" لفتح الإعداد في تطبيق WireGuard الرسمي
 *    — فهذا هو المسار العامل اليوم.
 */
enum class VpnProtocol { WIREGUARD, OPENVPN }

data class VpnServer(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    /** اسم المضيف أو IP لنقطة الطرف الآخر، كما في ملف الإعداد. */
    val endpoint: String,
    val configText: String,
    val dns: String = "1.1.1.1",
    val killSwitch: Boolean = true
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("protocol", protocol.name)
        .put("endpoint", endpoint)
        .put("configText", SecretStore.encrypt(configText))
        .put("dns", dns).put("killSwitch", killSwitch)

    companion object {
        fun fromJson(o: JSONObject) = VpnServer(
            id = o.getString("id"),
            name = o.optString("name", "VPN"),
            protocol = runCatching { VpnProtocol.valueOf(o.optString("protocol", "WIREGUARD")) }
                .getOrDefault(VpnProtocol.WIREGUARD),
            endpoint = o.optString("endpoint", ""),
            configText = SecretStore.decrypt(o.optString("configText", "")),
            dns = o.optString("dns", "1.1.1.1"),
            killSwitch = o.optBoolean("killSwitch", true)
        )
    }
}

/** نتيجة تحقق من ملف إعداد WireGuard. */
data class VpnValidation(val ok: Boolean, val errors: List<String>, val endpoint: String = "")

object WireGuardConfig {
    /** يتحقق من [Interface] و[Peer] في ملف ‎.conf دون إرسال أي شيء. */
    fun validate(text: String): VpnValidation {
        val errors = mutableListOf<String>()
        var section = ""
        var privateKey = ""
        var address = ""
        var publicKey = ""
        var endpoint = ""
        var allowed = ""
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) section = line
            val kv = line.split("=", limit = 2).map { it.trim() }
            if (kv.size == 2) when (section) {
                "[Interface]" -> when (kv[0]) {
                    "PrivateKey" -> privateKey = kv[1]
                    "Address" -> address = kv[1]
                }
                "[Peer]" -> when (kv[0]) {
                    "PublicKey" -> publicKey = kv[1]
                    "Endpoint" -> endpoint = kv[1]
                    "AllowedIPs" -> allowed = kv[1]
                }
            }
        }
        if (privateKey.length < 40) errors += "PrivateKey مفقود أو قصير في [Interface]"
        if (address.isEmpty()) errors += "Address مفقود في [Interface] (مثال: 10.6.0.2/32)"
        if (publicKey.length < 40) errors += "PublicKey مفقود في [Peer]"
        if (endpoint.isEmpty()) errors += "Endpoint مفقود في [Peer] (مثال: vpn.example.com:51820)"
        if (allowed.isEmpty()) errors += "AllowedIPs مفقود في [Peer] (مثال: 0.0.0.0/0, ::/0)"
        return VpnValidation(errors.isEmpty(), errors, endpoint)
    }

    /** يبني إعداداً جاهزاً للتصدير من حقول بسيطة، مع مفاتيح يولّدها خادمك أنت. */
    fun template(name: String, privateKey: String, address: String, publicKey: String, endpoint: String): String =
        "[Interface]\nPrivateKey = $privateKey\nAddress = $address\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = $publicKey\nEndpoint = $endpoint\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n"
}

/** حالة VPN الحية التي تعرضها الشاشة. */
object VpnState {
    @Volatile var status: String = "disconnected"
    @Volatile var detail: String = ""
    @Volatile var serverName: String = ""
}

class VpnStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun loadServers(): List<VpnServer> = runCatching {
        val arr = JSONArray(prefs.getString("servers", "[]"))
        (0 until arr.length()).map { VpnServer.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveServers(list: List<VpnServer>) {
        prefs.edit().putString("servers", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun selectedId(): String? = prefs.getString("selected", null)
    fun select(id: String?) = prefs.edit().putString("selected", id).apply()
    fun selected(): VpnServer? = loadServers().firstOrNull { it.id == selectedId() }
    fun killSwitch(): Boolean = prefs.getBoolean("kill_switch", true)
    fun setKillSwitch(on: Boolean) = prefs.edit().putBoolean("kill_switch", on).apply()
    fun autoReconnect(): Boolean = prefs.getBoolean("auto_reconnect", false)
    fun setAutoReconnect(on: Boolean) = prefs.edit().putBoolean("auto_reconnect", on).apply()
}

/**
 * خدمة VPN.
 *
 * TUNNEL_ENGINE_AVAILABLE = false عمداً: لا يوجد في هذا المشروع محرك WireGuard
 * (مكتبة رسمية + Go backend). عند إضافته، اجعل هذه القيمة true واربط
 * startTunnel() بمحركك، وسيبدأ النفق فعلاً. حتى ذلك الحين ترفض الخدمة
 * الاتصال الوهمي بدل قطع الإنترنت، وتسجّل السبب في VpnState.
 */
class RelayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); stopSelf(); return Service.START_NOT_STICKY }
        }
        val store = VpnStore(this)
        val server = store.selected()
        if (server == null) {
            VpnState.status = "error"
            VpnState.detail = "لا يوجد خادم محدد. أضف خادم WireGuard بإعداد ‎.conf أولاً."
            stopSelf(); return Service.START_NOT_STICKY
        }
        val validation = if (server.protocol == VpnProtocol.WIREGUARD) {
            WireGuardConfig.validate(server.configText)
        } else {
            VpnValidation(server.configText.contains("remote "), listOf("ملف OpenVPN غير مكتمل: يلزم سطر remote"))
        }
        if (!validation.ok) {
            VpnState.status = "error"
            VpnState.detail = "إعداد غير صالح: " + validation.errors.joinToString("; ")
            stopSelf(); return Service.START_NOT_STICKY
        }
        if (!TUNNEL_ENGINE_AVAILABLE) {
            VpnState.status = "needs_engine"
            VpnState.detail =
                "الإعداد صالح، لكن محرك النفق غير مضمّن في هذا البناء. " +
                    "صدّر ‎.conf من شاشة VPN وافتحه في تطبيق WireGuard الرسمي، " +
                    "أو أضف مكتبة WireGuard (انظر README_VPN_AI.md)."
            stopSelf(); return Service.START_NOT_STICKY
        }
        startForegroundNotification(server)
        VpnState.status = "connecting"
        VpnState.serverName = server.name
        // عند تفعيل المحرك: هنا يُنشأ TUN عبر Builder ثم يُمرَّر وصفه للمحرك.
        // val tun = Builder().setSession(server.name).addAddress(...).addDnsServer(server.dns)
        //     .addRoute("0.0.0.0", 0).establish()
        VpnState.status = "connected"
        return Service.START_STICKY
    }

    private fun startForegroundNotification(server: VpnServer) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "VPN", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setContentTitle("Claude Agents Team Muse — VPN")
            .setContentText("متصل: ${server.name}")
            .setSmallIcon(android.R.drawable.stat_sys_vpns)
            .setOngoing(true)
            .build()
        // VpnService نفسها خدمة نظام؛ لا نطلب نوع foreground إضافياً هنا.
        startForeground(1, n)
    }

    private fun stopTunnel() {
        runCatching { tun?.close() }
        tun = null
        VpnState.status = "disconnected"
        VpnState.detail = ""
        VpnState.serverName = ""
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopTunnel(); super.onDestroy() }
    override fun onRevoke() { stopTunnel(); super.onRevoke() }

    companion object {
        const val ACTION_STOP = "com.relay.agents.VPN_STOP"
        const val TUNNEL_ENGINE_AVAILABLE = false
        private const val CH = "vpn"

        /** يعيد Intent إذن النظام إن لزم، أو null إن كان الإذن ممنوحاً مسبقاً. */
        fun prepareIntent(ctx: Context): Intent? = prepare(ctx)

        fun start(ctx: Context) {
            VpnState.status = "connecting"
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayVpnService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RelayVpnService::class.java).setAction(ACTION_STOP))
        }

        fun newServer(name: String, protocol: VpnProtocol, configText: String, dns: String = "1.1.1.1"): VpnServer {
            val endpoint = if (protocol == VpnProtocol.WIREGUARD) WireGuardConfig.validate(configText).endpoint
            else configText.lines().firstOrNull { it.trim().startsWith("remote ") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1).orEmpty()
            return VpnServer(UUID.randomUUID().toString(), name.trim().ifEmpty { "VPN" }, protocol, endpoint, configText, dns)
        }
    }
}
