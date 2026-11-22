package com.relay.agents

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

private data class FreeSite(val id: String, val name: String, val url: String, val desc: String)

/**
 * المتصفح المجاني: كل نموذج ذكاء اصطناعي له متصفحه الخاص، منفصل تماماً
 * عن متصفح GitHub وعن باقي النماذج: تبويباته في مساحة "free:<النموذج>"
 * ببيئة تسجيل دخول واحدة للنموذج (كوكيز وتخزين مستقل)، وكل ضغطة على
 * بطاقة النموذج تفتح تبويباً جديداً له بجانب تبويباته — فتتراكم تبويبات
 * متوازية للنموذج الواحد (كلاود عدة تبويبات، وباقي النماذج كذلك) —
 * وزر + في متصفح النموذج يفتح تبويباً جديداً للنموذج نفسه أيضاً.
 * المستخدم يسجّل دخوله بنفسه في كل موقع ويكتب بنفسه؛ التطبيق
 * لا يرسل شيئاً تلقائياً داخل هذه المواقع ولا يقرأ إجاباتها، ولا يحقن نصاً.
 * الحدود المجانية (عدد الرسائل اليومي) تتغير مع الوقت حسب كل شركة.
 *
 * إصلاح إصدار المتصفح: GeckoView حُدّث من 130 (آب 2024) إلى
 * 137.0.20250414091429 (نيسان 2025)، فزال تحذير "This browser is out of date" في
 * المواقع التي ترفض المتصفحات القديمة. (جرّبنا أولاً الأحدث 160 ففشل
 * البناء: يحتاج compileSdk 37 وAGP 9.1؛ ثم 140 ففشل: يجلب kotlin-stdlib
 * 2.1.21 ومترجم Kotlin ‏1.9.22 لا يقرأ بياناتها؛ أما 137 فتبعياته
 * kotlin-stdlib ‏1.9.24 وandroidx.core ‏1.15.0 وlifecycle ‏2.8.7 —
 * كلها متوافقة مع الأدوات الحالية.)
 */
@Composable
fun FreeAiTab() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<FreeSite?>(null) }
    val site = open
    if (site != null) {
        BackHandler { open = null }
        FreeBrowserView(site) { open = null }
        return
    }
    val sites = listOf(
        FreeSite("claude", "Claude", "https://claude.ai", tr("كتابة وبرمجة وملفات طويلة — مجاني بحد يومي، ثم يتوقف حتى يتجدد.")),
        FreeSite("chatgpt", "ChatGPT", "https://chatgpt.com", tr("الأشهر والأوسع استخداماً — مجاني بحد للرسائل، مع بحث وتوليد صور.")),
        FreeSite("gemini", "Gemini", "https://gemini.google.com", tr("من جوجل — مجاني، بحث وملفات طويلة، ويتكامل مع حساب جوجل.")),
        FreeSite("deepseek", "DeepSeek", "https://chat.deepseek.com", tr("الأقوى مجاناً للبرمجة والتفكير العميق — دردشة مجانية بلا خطة مدفوعة وبلا حد معلن.")),
        FreeSite("qwen", "Qwen", "https://chat.qwen.ai", tr("من علي بابا — مجاني بلا حد معلن، يكتب ويبرمج ويولّد صوراً وفيديو.")),
        FreeSite("copilot", "Copilot", "https://copilot.microsoft.com", tr("من مايكروسوفت — مجاني، بحث ويب لحظي، ويعمل بحساب مايكروسوفت.")),
        FreeSite("perplexity", "Perplexity", "https://perplexity.ai", tr("بحث بإجابات موثقة بالمصادر — مجاني، مع 5 أسئلة Pro يومياً.")),
        FreeSite("metaai", "Meta AI", "https://meta.ai", tr("من ميتا — مجاني بلا خطة مدفوعة، ويعمل بحساب ميتا.")),
        FreeSite("mistral", "Mistral", "https://chat.mistral.ai", tr("أوروبي (Le Chat) — مجاني بحد يومي، وبياناته في أوروبا.")),
        FreeSite("grok", "Grok", "https://grok.com", tr("من xAI — أخبار لحظية من منصة X، مجاني بحد محدود.")),
        FreeSite("muse", "Muse", "https://muse.ai", tr("حسابك مسجل فيه — مجاني، لكن توفره حسب بلدك وقد يطلب انتظاراً."))
    )
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("كل زر يفتح ذكاءً اصطناعياً في تبويب مستقل ببيئة تسجيل دخول منفصلة — سجّل دخولك بنفسك في كل موقع واكتب بنفسك. التطبيق لا يرسل شيئاً تلقائياً ولا يقرأ إجاباتك، والحدود المجانية تتغير مع الوقت."),
            style = MaterialTheme.typography.bodySmall
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sites, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable {
                    val space = "free:${s.id}"
                    BrowserHub.init(ctx, s.url, space)
                    // كل ضغطة على النموذج تفتح تبويباً جديداً له بجانب تبويباته،
                    // فتتراكم تبويبات متوازية للنموذج الواحد (كلاود عدة تبويبات، وهكذا).
                    BrowserHub.newTabForSpace(ctx, space, s.url)
                    open = s
                }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.desc, style = MaterialTheme.typography.bodySmall)
                        Text(tr("فتح"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** متصفح نموذج واحد: شريط تبويباته فقط، وزر + يفتح تبويباً جديداً للنموذج نفسه. */
@Composable
private fun FreeBrowserView(site: FreeSite, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val space = "free:${site.id}"
    remember { BrowserHub.init(ctx, site.url, space); true }
    val spaceTabs = BrowserHub.tabs.filter { it.space == space }
    val tab = BrowserHub.selectedTab(space)

    var addr by remember(tab?.id) { mutableStateOf(tab?.url ?: "") }
    LaunchedEffect(tab?.url) { tab?.url?.let { addr = it } }
    var moreMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = tab?.canBack == true) { tab?.session?.goBack() }

    fun go(raw: String) {
        val t = raw.trim()
        val u = when {
            t.isEmpty() -> return
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        tab?.session?.loadUri(u)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("رجوع")) }
            Text(site.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        // شريط تبويبات هذا النموذج فقط.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            spaceTabs.forEach { t ->
                FilterChip(
                    selected = t.id == tab?.id,
                    onClick = { BrowserHub.select(t) },
                    label = { Text("🔒${t.env} · " + t.title.ifBlank { Uri.parse(t.url).host ?: tr("تبويب") }.take(16), maxLines = 1) },
                    trailingIcon = { Text("✕", Modifier.clickable { BrowserHub.close(t) }.padding(2.dp)) }
                )
            }
            // زر + يفتح تبويباً جديداً للنموذج نفسه: نفس الرابط، ونفس بيئة تسجيل الدخول.
            AssistChip(onClick = { BrowserHub.newTabForSpace(ctx, space, site.url) }, label = { Text("＋") })
        }
        if (tab != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = tab.canBack, onClick = { tab.session.goBack() }) { Text(if (I18n.isRtl) "→" else "←") }
                TextButton(enabled = tab.canFwd, onClick = { tab.session.goForward() }) { Text(if (I18n.isRtl) "←" else "→") }
                TextButton(onClick = { if (tab.loading) tab.session.stop() else tab.session.reload() }) { Text(if (tab.loading) "✕" else "⟳") }
                OutlinedTextField(
                    addr, { addr = it }, singleLine = true, modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large, textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(addr) })
                )
                Box {
                    TextButton(onClick = { moreMenu = true }) { Text("⋮") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("فتح في المتصفح الخارجي")) }, onClick = {
                            moreMenu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                        DropdownMenuItem(text = { Text(trf("مسح بيانات البيئة %s", tab.env)) }, onClick = {
                            moreMenu = false; BrowserHub.clearEnvironment(tab)
                        })
                    }
                }
            }
            if (tab.loading && tab.progress in 1..99) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())

            var ready by remember(tab.id) { mutableStateOf(tab.session.isOpen) }
            LaunchedEffect(tab.id) {
                while (!tab.session.isOpen) delay(50)
                tab.session.setActive(true)
                ready = true
            }
            if (ready) {
                key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { c -> GeckoView(c).also { it.setSession(tab.session) } },
                        onRelease = { it.releaseSession() }
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { BrowserHub.newTabForSpace(ctx, space, site.url) }) { Text(tr("تبويب جديد")) }
            }
        }
    }
}
