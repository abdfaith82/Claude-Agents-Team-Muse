package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * شاشة Muse4AI: توليد صور وفيديو ومحادثة عبر جسر Muse4AI المحلي.
 *
 * المتطلبات (بلا كمبيوتر لا يعمل): كمبيوتر Windows يشغّل تطبيق
 * Muse4AI مع حساب Muse مسجّل فيه، والهاتف على نفس شبكة Wi-Fi.
 * العنوان هو IP الكمبيوتر مع المنفذ 47821 — لا تكتب 127.0.0.1
 * لأنها من الهاتف تعني الهاتف نفسه. من محاكي أندرويد استخدم
 * http://10.0.2.2:47821. المفتاح msk_… يُنشأ من التطبيق على
 * الكمبيوتر: Settings → API & agent، ويُحفظ هنا مشفّراً.
 */
@Composable
fun Muse4AiTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { Muse4AiConfig(Storage(ctx)) }
    var kind by remember { mutableIntStateOf(0) } // 0 صورة، 1 فيديو، 2 محادثة
    var prompt by remember { mutableStateOf("") }
    var convId by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }

    val kinds = listOf("image" to "🖼️ صورة", "video" to "🎬 فيديو", "chat" to "💬 محادثة")
    val base = Muse4AiClient.normalizeBase(cfg.baseUrl)
    val key = cfg.apiKey.trim()

    fun refreshJobs() {
        scope.launch {
            busy = true
            Muse4AiClient.listJobs(base, key)
                .onSuccess { jobs = it; msg = if (it.isEmpty()) tr("لا مهام بعد في الجسر.") else "" }
                .onFailure { msg = "❌ ${it.message}" }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                tr("Muse4AI جسر على كمبيوتر Windows يولّد الصور والفيديو عبر جلسة Muse الخاصة بك. بلا كمبيوتر يشغّل الجسر لن يعمل هذا التبويب إطلاقاً — والجسر مشروع مستقل غير تابع لـ Meta، وأتمتة الجلسة قد تتعطل عند تغيّر الموقع وقد لا تناسب شروط الاستخدام."),
                style = MaterialTheme.typography.bodySmall
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("الاتصال بالجسر"), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        cfg.baseUrl, { cfg.baseUrl = it; saved = false },
                        label = { Text(tr("عنوان الجسر (مثال: http://192.168.1.5:47821)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        cfg.apiKey, { cfg.apiKey = it; saved = false },
                        label = { Text(tr("مفتاح API (msk_…)")) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { cfg.save(); saved = true }) { Text(tr("حفظ")) }
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                cfg.save()
                                Muse4AiClient.check(base, key)
                                    .onSuccess { msg = "✅ $it" }
                                    .onFailure { msg = "❌ ${it.message}" }
                                busy = false
                            }
                        }) { Text(tr("فحص الاتصال")) }
                    }
                    if (saved) Text(tr("✅ تم الحفظ (المفتاح مشفّر)"), style = MaterialTheme.typography.bodySmall)
                    Text(
                        tr("على الكمبيوتر: ثبّت Muse4AI، سجّل حساب Muse في Accounts، ثم أنشئ مفتاحاً من Settings → API & agent. تأكد أن جدار الحماية يسمح بالمنفذ 47821 على الشبكة المحلية."),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("مهمة جديدة"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kinds.forEachIndexed { i, (_, label) ->
                            FilterChip(selected = kind == i, onClick = { kind = i }, label = { Text(tr(label)) })
                        }
                    }
                    OutlinedTextField(
                        prompt, { prompt = it },
                        label = { Text(tr("الوصف (prompt) — اكتب نسبة العرض والكلام المنطوق بين علامتي تنصيص للفيديو")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                    if (kind == 2) {
                        OutlinedTextField(
                            convId, { convId = it },
                            label = { Text(tr("معرّف المحادثة لمتابعتها (اختياري — يأتي من رد سابق)")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    Button(
                        enabled = prompt.isNotBlank() && key.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                Muse4AiClient.createJob(base, key, kinds[kind].first, prompt.trim(), convId.trim())
                                    .onSuccess { j ->
                                        val id = Muse4AiClient.jobId(j)
                                        msg = tr("✅ أُنشئت المهمة") + if (id.isNotBlank()) " — $id" else ""
                                        if (id.isNotBlank()) jobs = listOf(j) + jobs
                                        val c = Muse4AiClient.jobConversationId(j)
                                        if (c.isNotBlank()) convId = c
                                    }
                                    .onFailure { msg = "❌ ${it.message}" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr("إنشاء المهمة")) }
                    Text(
                        tr("الصور نحو 30–90 ثانية، والفيديو عدة دقائق (مقطع نحو 8–10 ثوانٍ بدقة 1280×720). روابط التنزيل صالحة 60 دقيقة. كل توليد يستهلك حد استخدام Muse في حسابك."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { refreshJobs() }) { Text(tr("تحديث قائمة المهام")) }
            }
            Text(tr("المهام"), style = MaterialTheme.typography.titleMedium)
        }
        if (jobs.isEmpty()) {
            item { Text(tr("لا مهام معروضة. أنشئ مهمة أعلاه أو حدّث القائمة."), style = MaterialTheme.typography.bodySmall) }
        }
        items(jobs, key = { Muse4AiClient.jobId(it).ifBlank { it.toString().hashCode().toString() } }) { j ->
            val id = Muse4AiClient.jobId(j)
            val status = Muse4AiClient.jobStatus(j)
            val text = Muse4AiClient.jobText(j)
            val downloads = Muse4AiClient.jobDownloads(j)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${Muse4AiClient.jobKind(j).ifBlank { tr("مهمة") }} • $status",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val p = Muse4AiClient.jobPrompt(j)
                    if (p.isNotBlank()) Text(p, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    if (id.isNotBlank()) Text(id, style = MaterialTheme.typography.labelSmall)
                    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall)
                    downloads.forEachIndexed { i, url ->
                        TextButton(onClick = { openLink(ctx, url) }) { Text(trf("⬇️ تنزيل النتيجة %s", i + 1)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = id.isNotBlank() && !busy, onClick = {
                            scope.launch {
                                busy = true
                                Muse4AiClient.getJob(base, key, id)
                                    .onSuccess { fresh ->
                                        jobs = jobs.map { if (Muse4AiClient.jobId(it) == id) fresh else it }
                                        msg = ""
                                    }
                                    .onFailure { msg = "❌ ${it.message}" }
                                busy = false
                            }
                        }) { Text(tr("تحديث الحالة")) }
                        OutlinedButton(enabled = id.isNotBlank() && !busy, onClick = {
                            scope.launch {
                                busy = true
                                Muse4AiClient.cancelJob(base, key, id)
                                    .onSuccess { msg = tr("تم طلب الإلغاء") }
                                    .onFailure { msg = "❌ ${it.message}" }
                                busy = false
                            }
                        }) { Text(tr("إلغاء")) }
                        if (Muse4AiClient.jobKind(j) == "video") {
                            OutlinedButton(enabled = id.isNotBlank() && !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    Muse4AiClient.extendVideo(base, key, id)
                                        .onSuccess { fresh ->
                                            jobs = jobs.map { if (Muse4AiClient.jobId(it) == id) fresh else it }
                                            msg = tr("تم طلب التمديد")
                                        }
                                        .onFailure { msg = "❌ ${it.message}" }
                                    busy = false
                                }
                            }) { Text(tr("تمديد الفيديو")) }
                        }
                    }
                }
            }
        }
    }
}
