package com.relay.agents

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

/**
 * شاشة VPN: إضافة خادم بإعداد ‎.conf / ‎.ovpn، التحقق منه، تصديره،
 * وطلب إذن النظام ثم الاتصال. لا تعرض "متصل" أبداً إلا إذا أكدت الخدمة ذلك،
 * وتشرح صراحة أن محرك النفق غير مضمّن بعد (انظر Vpn.kt وREADME_VPN_AI.md).
 */
@Composable
fun VpnTab(vm: RelayViewModel) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(VpnProtocol.WIREGUARD) }
    var config by remember { mutableStateOf("") }
    var dns by remember { mutableStateOf("1.1.1.1") }
    var msg by remember { mutableStateOf("") }
    var exportServer by remember { mutableStateOf<VpnServer?>(null) }

    val prepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.startVpn(ctx) else {
            msg = tr("رفضت إذن VPN من النظام، فلم يبدأ الاتصال")
            vm.refreshVpnStatus()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            config = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: ""
            if (config.isBlank()) msg = tr("تعذّر قراءة الملف")
            else {
                protocol = if (config.contains("[Interface]")) VpnProtocol.WIREGUARD else VpnProtocol.OPENVPN
                msg = tr("تم استيراد الإعداد، تحقق منه ثم اضغط إضافة خادم")
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val s = exportServer
        if (uri != null && s != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(s.configText.toByteArray()) }
            }
            msg = tr("تم تصدير الإعداد — افتحه في تطبيق WireGuard الرسمي للاتصال الفعلي")
        }
        exportServer = null
    }

    fun connect() {
        vm.refreshVpnStatus()
        val intent = RelayVpnService.prepareIntent(ctx)
        if (intent != null) prepareLauncher.launch(intent) else vm.startVpn(ctx)
        msg = ""
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                tr("VPN احترافي يحتاج خادماً تملكه أنت: لا خوادم مجانية داخل التطبيق. أضف إعداد WireGuard (.conf) من خادمك، وسيُحفظ مشفّراً ويُتحقق منه قبل الاتصال."),
                style = MaterialTheme.typography.bodySmall
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val st = vm.vpnStatus
                    Text(
                        when (st) {
                            "connected" -> tr("🟢 متصل")
                            "connecting" -> tr("🟡 جارٍ الاتصال…")
                            "needs_engine" -> tr("⚠️ الإعداد صالح — محرك النفق غير مضمّن في هذا البناء")
                            "error" -> tr("🔴 خطأ")
                            else -> tr("⚪ غير متصل")
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (vm.vpnDetail.isNotEmpty()) Text(vm.vpnDetail, style = MaterialTheme.typography.bodySmall)
                    if (st == "needs_engine") {
                        Text(
                            tr("الحل العامل اليوم: صدّر ‎.conf بالزر أدناه وافتحه في تطبيق WireGuard الرسمي. لإدماج النفق داخل هذا التطبيق أضف مكتبة WireGuard كما في README_VPN_AI.md."),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { connect() },
                            enabled = vm.vpnSelectedId != null && st != "connected" && st != "connecting"
                        ) { Text(tr("اتصال")) }
                        OutlinedButton(
                            onClick = { vm.stopVpn(ctx) },
                            enabled = st == "connected" || st == "connecting"
                        ) { Text(tr("قطع")) }
                        OutlinedButton(onClick = { vm.refreshVpnStatus() }) { Text(tr("تحديث الحالة")) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Kill Switch"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("عند تفعيله في إعدادات النظام يمنع أندرويد أي اتصال بلا VPN. هذا التطبيق يحفظ اختيارك ويذكّرك بتفعيله في النظام."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = vm.vpnKillSwitch, onCheckedChange = { vm.setVpnKillSwitch(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(tr("إعدادات VPN في النظام")) }
                OutlinedButton(onClick = { vm.setVpnAutoReconnect(!vm.vpnAutoReconnect) }) {
                    Text(if (vm.vpnAutoReconnect) tr("إعادة اتصال تلقائية: مفعّلة") else tr("إعادة اتصال تلقائية: متوقفة"))
                }
            }
            Divider()
            Text(tr("إضافة خادم"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VpnProtocol.values().forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = { protocol = p },
                        label = { Text(if (p == VpnProtocol.WIREGUARD) "WireGuard" else "OpenVPN") }
                    )
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text(tr("اسم الخادم (مثال: ألمانيا 1)")) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(dns, { dns = it }, label = { Text(tr("DNS (اختياري)")) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(config, { config = it }, label = { Text(tr("الصق ملف الإعداد (.conf / .ovpn) هنا")) },
                modifier = Modifier.fillMaxWidth(), minLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "*/*")) }) {
                    Text(tr("استيراد ملف .conf"))
                }
                Button(onClick = {
                    val err = vm.addVpnServer(name, protocol, config, dns)
                    msg = err ?: tr("تمت إضافة الخادم وحفظ إعداده مشفّراً")
                    if (err == null) { name = ""; config = "" }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("إضافة خادم")) }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
            Divider()
            Text(tr("خوادمي"), style = MaterialTheme.typography.titleMedium)
        }
        if (vm.vpnServers.isEmpty()) {
            item { Text(tr("لا خوادم بعد. بدون خادم بإعداد صالح لن يعمل VPN — وهذه هي النقطة الأهم."), style = MaterialTheme.typography.bodySmall) }
        }
        items(vm.vpnServers, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text("${s.protocol} • ${s.endpoint}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = vm.vpnSelectedId == s.id, onCheckedChange = { if (it) vm.selectVpnServer(s.id) })
                        TextButton(onClick = { vm.removeVpnServer(s.id) }) { Text(tr("حذف")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportServer = s; exportLauncher.launch("${s.name}.conf") }) {
                            Text(tr("تصدير .conf"))
                        }
                        val v = WireGuardConfig.validate(s.configText)
                        Text(
                            if (s.protocol == VpnProtocol.WIREGUARD && v.ok) tr("الإعداد صالح ✅")
                            else if (s.protocol == VpnProtocol.WIREGUARD) v.errors.joinToString("; ")
                            else tr("OpenVPN: يُتحقق عند الاتصال"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            Text(
                tr("ملاحظة أمان: لا تلصق أبداً مفتاحاً خاصاً في محادثة أو مشاركة عامة. الإعداد يُحفظ مشفّراً على جهازك فقط، ولا يُرسل إلى أي خادم من هذا التطبيق."),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
