package com.relay.agents

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * محطة اختبار داخلية — واجهة فحص داخل التطبيق، مستوحاة من Android Test Station.
 *
 * بصراحة، حتى لا يُباع وهم: هذه ليست ATS الحقيقي من جوجل. ATS الحقيقي أداة
 * مضيف (host) تعمل على Ubuntu وتشغّل مجموعات اختبار مثل CTS عبر TradeFed
 * على أجهزة موصولة بـ USB، ولا يمكن تضمينها أو تشغيلها داخل تطبيق أندرويد.
 * ما تفعله هذه الشاشة بدلاً من ذلك هو فحص التطبيق من الداخل: إعدادات
 * الوكلاء والمفاتيح، صلاحية إعدادات VPN، الشبكة والإنترنت، وصول النماذج
 * المحلية، وسلامة التخزين المشفّر — وكلها تتم على جهازك دون إرسال أي مفتاح
 * أو إعداد إلى أي خادم. الفحص الذي يحتاج إنترنت يجري طلباً خفيفاً فقط
 * (generate_204 للاتصال، و/models للنموذج المحلي) ولا يرسل مفاتيح API.
 */
enum class SelfTestStatus { PASS, FAIL, WARN, RUNNING }

data class SelfTest(
    val group: String,
    val name: String,
    val status: SelfTestStatus,
    val detail: String,
    /** وجهة للانتقال إليها لإصلاح المشكلة (0 وكلاء، 18 VPN)، أو null بلا زر. */
    val fixDest: Int? = null
)

private fun httpStatus(url: String, timeoutMs: Int = 5000): Int {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Claude-Agents-Team-Muse/TestStation")
        }
        conn.connect()
        conn.responseCode
    } finally {
        conn?.disconnect()
    }
}

@Composable
fun TestStationTab(vm: RelayViewModel, onNavigate: (Int) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<SelfTest>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf("") }

    fun runTests() {
        if (running) return
        running = true
        results = emptyList()
        scope.launch(Dispatchers.IO) {
            val out = mutableListOf<SelfTest>()
            // 1) هوية التطبيق
            val pkg = runCatching {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "${ctx.packageName} • ${info.versionName ?: "?"}"
            }.getOrDefault(ctx.packageName)
            out += SelfTest(
                tr("التطبيق"), tr("هوية التطبيق"), SelfTestStatus.PASS,
                if (pkg.contains("com.claudeagents.team.muse")) "$pkg — ${tr("اسم الحزمة الجديد صحيح")}" else pkg
            )
            // 2) الوكلاء: مفعّلون، ومفاتيحهم، وعناوين النماذج المحلية
            val enabled = vm.agents.filter { it.enabled }
            if (enabled.isEmpty()) {
                out += SelfTest(
                    tr("الوكلاء"), tr("وكيل مفعّل"), SelfTestStatus.FAIL,
                    tr("لا يوجد أي وكيل مفعّل — أضف وكيلاً أولاً"), fixDest = 0
                )
            } else {
                enabled.forEach { a ->
                    val missingKey = !a.provider.manual && !a.provider.custom &&
                        a.provider != Provider.OLLAMA && a.provider != Provider.LMSTUDIO &&
                        a.apiKey.isBlank()
                    val missingBase = a.provider.custom && a.baseUrl.isBlank()
                    when {
                        a.provider.manual -> out += SelfTest(
                            tr("الوكلاء"), a.name, SelfTestStatus.WARN,
                            tr("وكيل يدوي: يفتح موقع الدردشة وتلصق الرد بنفسك — لا يُفحص عبر API")
                        )
                        missingKey -> out += SelfTest(
                            tr("الوكلاء"), a.name, SelfTestStatus.FAIL,
                            tr("مفتاح API مفقود لهذا الوكيل"), fixDest = 0
                        )
                        missingBase -> out += SelfTest(
                            tr("الوكلاء"), a.name, SelfTestStatus.FAIL,
                            tr("عنوان الخادم (baseUrl) مفقود لهذا الوكيل المخصّص"), fixDest = 0
                        )
                        else -> out += SelfTest(
                            tr("الوكلاء"), a.name, SelfTestStatus.PASS,
                            "${a.provider.label} • ${a.model}"
                        )
                    }
                }
            }
            // 3) VPN: الخوادم وصلاحية إعداد WireGuard وحالة محرك النفق
            if (vm.vpnServers.isEmpty()) {
                out += SelfTest(
                    tr("VPN"), tr("خوادم VPN"), SelfTestStatus.WARN,
                    tr("لا خوادم بعد — VPN لن يعمل بلا خادم تملكه بإعداد صالح"), fixDest = 18
                )
            } else {
                vm.vpnServers.forEach { s ->
                    if (s.protocol == VpnProtocol.WIREGUARD) {
                        val v = WireGuardConfig.validate(s.configText)
                        out += SelfTest(
                            tr("VPN"), s.name,
                            if (v.ok) SelfTestStatus.PASS else SelfTestStatus.FAIL,
                            if (v.ok) "${tr("الإعداد صالح")} • ${s.endpoint}"
                            else v.errors.joinToString("; "),
                            fixDest = if (v.ok) null else 18
                        )
                    } else {
                        out += SelfTest(
                            tr("VPN"), s.name, SelfTestStatus.WARN,
                            tr("OpenVPN: يُتحقق من الإعداد عند الاتصال")
                        )
                    }
                }
            }
            out += SelfTest(
                tr("VPN"), tr("محرك النفق"),
                if (RelayVpnService.TUNNEL_ENGINE_AVAILABLE) SelfTestStatus.PASS else SelfTestStatus.WARN,
                if (RelayVpnService.TUNNEL_ENGINE_AVAILABLE) tr("محرك النفق مضمّن")
                else tr("محرك النفق غير مضمّن بعد — صدّر .conf وافتحه في تطبيق WireGuard الرسمي")
            )
            // 4) الشبكة والإنترنت
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> tr("بيانات الهاتف")
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> ""
            }
            out += SelfTest(
                tr("الشبكة"), tr("الاتصال بالشبكة"),
                if (hasNet) SelfTestStatus.PASS else SelfTestStatus.FAIL,
                if (hasNet) tr("متصل") + if (transport.isNotEmpty()) " • $transport" else ""
                else tr("لا يوجد اتصال بالإنترنت — تحقق من Wi-Fi أو البيانات")
            )
            if (hasNet) {
                val code = runCatching { httpStatus("https://www.google.com/generate_204") }.getOrNull()
                out += SelfTest(
                    tr("الشبكة"), tr("الوصول إلى الإنترنت"),
                    if (code == 204) SelfTestStatus.PASS else SelfTestStatus.FAIL,
                    if (code == 204) tr("الإنترنت يعمل (204)") else trf("تعذّر الوصول (رمز: %s)", code ?: "—")
                )
            }
            // 5) النماذج المحلية: هل يرد الخادم المحلي فعلاً؟
            val locals = enabled.filter { it.provider == Provider.OLLAMA || it.provider == Provider.LMSTUDIO }
            if (locals.isEmpty()) {
                out += SelfTest(
                    tr("النماذج المحلية"), tr("خادم محلي"), SelfTestStatus.WARN,
                    tr("لا وكيل محلي مضاف — أضف Ollama أو LM Studio من شاشة الوكلاء إن كان لديك كمبيوتر"), fixDest = 0
                )
            } else {
                locals.forEach { a ->
                    val base = a.baseUrl.trim().trimEnd('/')
                    val code = runCatching { httpStatus("$base/models", 4000) }.getOrNull()
                    out += SelfTest(
                        tr("النماذج المحلية"), a.name,
                        if (code == 200) SelfTestStatus.PASS else SelfTestStatus.FAIL,
                        if (code == 200) "${tr("الخادم المحلي يرد")} • $base"
                        else tr("الخادم المحلي لا يرد — تأكد أن الكمبيوتر يعمل وعلى نفس Wi-Fi وأن العنوان صحيح")
                    )
                }
            }
            // 6) التخزين المشفّر: جولة تشفير/فك تشفير عبر Keystore دون إظهار أي مفتاح
            val probe = "test-station-${System.currentTimeMillis()}"
            val roundTripOk = runCatching { SecretStore.decrypt(SecretStore.encrypt(probe)) == probe }.getOrDefault(false)
            out += SelfTest(
                tr("التخزين"), tr("التشفير (Keystore)"),
                if (roundTripOk) SelfTestStatus.PASS else SelfTestStatus.FAIL,
                if (roundTripOk) tr("تشفير وفك تشفير المفاتيح يعملان")
                else tr("فشل اختبار التشفير — قد لا تُحفظ المفاتيح بشكل آمن على هذا الجهاز")
            )
            withContext(Dispatchers.Main) {
                results = out
                running = false
                lastRun = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                tr("محطة فحص داخلية مستوحاة من Android Test Station: تفحص إعدادات تطبيقك نفسه — الوكلاء، VPN، الشبكة، النماذج المحلية، والتخزين المشفّر — دون إرسال أي مفتاح أو إعداد إلى أي خادم."),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                tr("ليست ATS الحقيقي من جوجل: ذلك يعمل على Ubuntu ويشغّل CTS على أجهزة USB، ولا يمكن تشغيله داخل الهاتف."),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runTests() }, enabled = !running) {
                    Text(if (running) tr("جارٍ الفحص…") else tr("▶ تشغيل الفحص"))
                }
                if (results.isNotEmpty()) {
                    val ok = results.count { it.status == SelfTestStatus.PASS }
                    val bad = results.count { it.status == SelfTestStatus.FAIL }
                    val warn = results.count { it.status == SelfTestStatus.WARN }
                    Text(
                        trf("ناجح: %s • فاشل: %s • تحذير: %s", ok, bad, warn) +
                            if (lastRun.isNotEmpty()) " • $lastRun" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        if (results.isEmpty() && !running) {
            item { Text(tr("اضغط «تشغيل الفحص» لبدء فحص التطبيق من الداخل."), style = MaterialTheme.typography.bodySmall) }
        }
        items(results, key = { it.group + "/" + it.name }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (t.status) {
                                SelfTestStatus.PASS -> "🟢"
                                SelfTestStatus.FAIL -> "🔴"
                                SelfTestStatus.WARN -> "🟡"
                                SelfTestStatus.RUNNING -> "⏳"
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text(t.group, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(t.detail, style = MaterialTheme.typography.bodySmall)
                    val fix = t.fixDest
                    if (fix != null && t.status == SelfTestStatus.FAIL) {
                        OutlinedButton(onClick = { onNavigate(fix) }) {
                            Text(if (fix == 18) tr("فتح شاشة VPN") else tr("فتح شاشة الوكلاء"))
                        }
                    }
                }
            }
        }
    }
}
