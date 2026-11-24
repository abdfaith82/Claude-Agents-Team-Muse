package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoView
import java.util.Locale

/** موقع في المتصفح المجاني — غير خاص لأن شاشة توليد الفيديو تستخدم
 * القائمة نفسها لزر «إرسال إلى موقع». */
internal data class FreeSite(val id: String, val name: String, val url: String, val desc: String)

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
/** قائمة النماذج المجانية — دالة على مستوى الملف لتشترك فيها قائمة
 * المتصفح المجاني (الوجهة 22) وصفحة النماذج المختارة المنفصلة (الوجهة 23)،
 * فتُبنى طازجة عند كل استدعاء لتتبع لغة الواجهة الحالية. */
internal fun freeSitesList() = listOf(
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
        FreeSite("muse", "Muse", "https://muse.ai", tr("حسابك مسجل فيه — مجاني، لكن توفره حسب بلدك وقد يطلب انتظاراً.")),
        // مواقع توليد فيديو مجانية (ويب فقط، بلا API — تُفتح في تبويب مستقل
        // وتولّد فيها بنفسك بعد تسجيل الدخول بحسابك المجاني).
        FreeSite("flow", "Google Flow", "https://flow.google.com", tr("من جوجل (Veo 3.1) — 50 رصيداً مجانياً كل يوم، نحو 5 مقاطع 720p يومياً، بلا بطاقة. لا API له — استخدمه من المتصفح هنا.")),
        FreeSite("dreamina", "Dreamina", "https://dreamina.capcut.com", tr("من CapCut — نحو 120 رصيداً مجانياً كل يوم، مقاطع حتى 30 ثانية بعلامة مائية.")),
        FreeSite("pixverse", "PixVerse", "https://pixverse.ai", tr("60 رصيداً عند التسجيل + 30 كل يوم، حتى 540p بعلامة مائية.")),
        FreeSite("kling", "Kling", "https://kling.ai", tr("جودة حركة قوية — رصيد مجاني شهري عند تسجيل الدخول (المقدار غير معلن)، بعلامة مائية.")),
        FreeSite("haiper", "Haiper", "https://haiper.ai", tr("300 رصيد بداية، حتى 720p و8 ثوانٍ، بعلامة مائية.")),
        FreeSite("raphael", "Raphael AI", "https://raphael.app", tr("توليد صور بلا حدود على النموذج الأساسي وبلا تسجيل — لكن الصور المجانية بعلامة مائية وللاستخدام غير التجاري فقط.")),
        FreeSite("leonardo", "Leonardo.ai", "https://leonardo.ai", tr("150 نقطة مجانية كل يوم تتجدد (نحو 18 صورة يومياً)، بلا علامة مائية — لكن الصور الناتجة عامة.")),
        FreeSite("bingimage", "Bing Image Creator", "https://www.bing.com/images/create", tr("من مايكروسوفت — 15 إنشاءً سريعاً كل يوم بحساب شخصي، ثم سرعة عادية حتى 200 موجّه يومياً، بلا علامة مائية.")),
        FreeSite("hailuo", "Hailuo AI", "https://hailuoai.video", tr("من MiniMax — حزمة ترحيب لمرة واحدة فقط (~150 رصيداً تنتهي خلال 3 أيام)، لا رصيد يومي متجدد، والفيديو بعلامة مائية.")),
        FreeSite("pika", "Pika", "https://pika.art", tr("الباقة المجانية بلا رصيد منذ سبتمبر 2026 — التوليد بحزم رصيد مدفوعة فقط."))
    )

/** جلسة محفوظة: اسم + معرّفات النماذج المختارة + وقت الحفظ.
 * تُخزَّن كل الجلسات في JSON واحد في SharedPreferences، فيمكن حفظ عدة
 * جلسات مسماة (جلسة 1، جلسة 2...) والتبديل بينها. المجموعة المفتوحة
 * حالياً تُحفظ أيضاً في المفتاح القديم free_picked_ids، فتعود آخر مجموعة
 * فُتحت حتى لو لم تُحفَظ كجلسة مسماة. الجلسة الوحيدة القديمة تُهاجَر
 * إلى جلسة باسم «آخر الجلسات» عند أول تحميل. تبويبات Gecko نفسها
 * محفوظة أصلاً في BrowserHub وتُستعاد مع الجلسة. */
data class SavedSession(val id: String, val name: String, val modelIds: List<String>, val savedAt: Long)

object PickedModelsStore {
    val pickedIds = mutableStateListOf<String>()
    val sessions = mutableStateListOf<SavedSession>()
    val currentId = mutableStateOf<String?>(null)
    private const val KEY = "free_sessions_json"
    private const val OLD_KEY = "free_picked_ids"

    fun load(ctx: Context) {
        val st = Storage(ctx)
        sessions.clear()
        runCatching {
            val arr = JSONArray(st.setting(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = o.optString("ids", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (ids.isNotEmpty()) sessions.add(SavedSession(o.optString("id", "s$i"), o.optString("name", "جلسة"), ids, o.optLong("at", 0L)))
            }
        }
        // تهجير الجلسة الوحيدة القديمة إن وُجدت ولا توجد جلسات جديدة.
        if (sessions.isEmpty()) {
            val old = st.setting(OLD_KEY, "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (old.isNotEmpty()) sessions.add(SavedSession("legacy", "آخر الجلسات", old, 0L))
        }
        sessions.sortByDescending { it.savedAt }
        // المجموعة المفتوحة حالياً: آخر مجموعة فُتحت، وإلا أول جلسة محفوظة.
        if (pickedIds.isEmpty()) {
            val last = st.setting(OLD_KEY, "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (last.isNotEmpty()) {
                pickedIds.addAll(last)
                currentId.value = sessions.firstOrNull { it.modelIds == last }?.id
            } else {
                sessions.firstOrNull()?.let { pickedIds.addAll(it.modelIds); currentId.value = it.id }
            }
        }
        save(ctx)
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("ids", s.modelIds.joinToString(",")).put("at", s.savedAt))
        }
        val st = Storage(ctx)
        st.saveSetting(KEY, arr.toString())
        st.saveSetting(OLD_KEY, pickedIds.joinToString(","))
    }

    /** حفظ الجلسة المفتوحة حالياً كجلسة جديدة مسماة (جلسة 1، جلسة 2...). */
    fun saveCurrent(ctx: Context, modelIds: List<String>): SavedSession {
        val s = SavedSession("s" + System.currentTimeMillis(), "جلسة " + (sessions.size + 1), modelIds, System.currentTimeMillis())
        sessions.add(0, s)
        currentId.value = s.id
        save(ctx)
        return s
    }

    /** فتح جلسة محفوظة: تصبح هي المجموعة المختارة الحالية. */
    fun openSession(ctx: Context, s: SavedSession) {
        pickedIds.clear(); pickedIds.addAll(s.modelIds)
        currentId.value = s.id
        save(ctx)
    }

    fun deleteSession(ctx: Context, id: String) {
        sessions.removeAll { it.id == id }
        if (currentId.value == id) currentId.value = sessions.firstOrNull()?.id
        save(ctx)
    }
}

@Composable
fun FreeAiTab(vm: RelayViewModel, onOpenPicked: () -> Unit) {
    val ctx = LocalContext.current
    val sites = freeSitesList()
    var openPage by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(false) }
    var tabPick by remember { mutableStateOf<List<Int>>(emptyList()) }
    // الجلسات المحفوظة: تُستعاد تلقائياً عند فتح القائمة.
    remember { PickedModelsStore.load(ctx); true }
    // موقع أُرسل إليه وصف من شاشة الفيديو: يُفتح تبويبه تلقائياً
    // (والوصف منسوخ أصلاً في الحافظة، فيلصقه المستخدم في الموقع).
    LaunchedEffect(vm.pendingSiteId) {
        val id = vm.pendingSiteId
        if (id.isNotBlank()) {
            vm.pendingSiteId = ""
            val idx = sites.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val s = sites[idx]
                val space = "free:${s.id}"
                BrowserHub.init(ctx, s.url, space)
                BrowserHub.newTabForSpace(ctx, space, s.url)
                openPage = idx
            }
        }
    }
    val open = openPage
    if (open != null) {
        BackHandler { openPage = null }
        FreeModelsPager(sites, open, vm) { openPage = null }
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("كل زر يفتح ذكاءً اصطناعياً في تبويب مستقل ببيئة تسجيل دخول منفصلة — سجّل دخولك بنفسك في كل موقع واكتب بنفسك. التطبيق لا يرسل شيئاً تلقائياً ولا يقرأ إجاباتك، والحدود المجانية تتغير مع الوقت."),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { picking = !picking; if (!picking) tabPick = emptyList() }) { Text(if (picking) tr("إلغاء") else tr("عرض بتبويبات")) }
            if (picking) Text(tr("اختر نموذجين أو أكثر لعرض كل نموذج في صفحة مستقلة والتبديل من التبويب العلوي."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        if (picking && tabPick.size >= 2) {
            Button(onClick = {
                tabPick.forEach { idx ->
                    val picked = sites[idx]
                    val sp = "free:${picked.id}"
                    BrowserHub.init(ctx, picked.url, sp)
                    if (BrowserHub.tabs.none { it.space == sp }) BrowserHub.newTabForSpace(ctx, sp, picked.url)
                }
                // حفظ تلقائي للمجموعة المفتوحة ثم فتحها في صفحتها المنفصلة (الوجهة 23).
                // (تبقى مجموعة بلا اسم حتى يحفظها المستخدم كجلسة مسماة من زر «حفظ الجلسة».)
                PickedModelsStore.pickedIds.clear()
                PickedModelsStore.pickedIds.addAll(tabPick.map { sites[it].id })
                PickedModelsStore.currentId.value = null
                PickedModelsStore.save(ctx)
                onOpenPicked()
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("عرض النماذج المختارة")) }
        }
        // الجلسات المحفوظة: عدة جلسات مسماة — افتح أياً منها أو احذفها.
        if (!picking && PickedModelsStore.sessions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("الجلسات المحفوظة"), style = MaterialTheme.typography.titleSmall)
                PickedModelsStore.sessions.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            s.name + " — " + s.modelIds.mapNotNull { id -> sites.firstOrNull { it.id == id }?.name }.joinToString("، "),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            PickedModelsStore.openSession(ctx, s)
                            onOpenPicked()
                        }) { Text(tr("فتح")) }
                        TextButton(onClick = { PickedModelsStore.deleteSession(ctx, s.id) }) { Text("✕") }
                    }
                }
                Text(tr("تُحفظ جلساتك تلقائياً — أغلق التطبيق وارجع وقتما تشاء."), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sites, key = { it.id }) { s ->
                val idx = sites.indexOf(s)
                Card(Modifier.fillMaxWidth().clickable {
                    if (picking) {
                        // في وضع التبويبات: الضغطة تختار النموذج أو تلغي اختياره،
                        // ولا تفتح متصفحه — ويمكن اختيار أي عدد من النماذج.
                        tabPick = if (idx in tabPick) tabPick - idx else tabPick + idx
                    } else {
                        val space = "free:${s.id}"
                        BrowserHub.init(ctx, s.url, space)
                        // كل ضغطة على النموذج تفتح تبويباً جديداً له بجانب تبويباته،
                        // فتتراكم تبويبات متوازية للنموذج الواحد (كلاود عدة تبويبات، وهكذا).
                        BrowserHub.newTabForSpace(ctx, space, s.url)
                        openPage = idx
                    }
                }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.desc, style = MaterialTheme.typography.bodySmall)
                        if (picking) {
                            if (idx in tabPick) Text(tr("مختارة ✓"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(tr("فتح"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** صفحة النماذج المختارة المنفصلة (الوجهة 23): تقرأ الجلسة المحفوظة
 * تلقائياً، فإن لم توجد جلسة تعرض رسالة وزراً للعودة للقائمة. */
@Composable
fun PickedModelsTab(vm: RelayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { PickedModelsStore.load(ctx); true }
    val sites = freeSitesList()
    val indices = PickedModelsStore.pickedIds.mapNotNull { id ->
        sites.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }
    if (indices.size < 2) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("لا توجد جلسات محفوظة بعد — اختر نماذج من المتصفح المجاني أولاً."), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack) { Text(tr("المتصفح المجاني")) }
        }
        return
    }
    PickedModelsView(sites, indices, vm, onBack)
}

/** عرض نموذج واحد مع التبديل بين جميع النماذج بالسحب يميناً ويساراً:
 * كل صفحة في الـ Pager هي متصفح نموذج كامل (لوحته وتبويباته وجلسة
 * تسجيل دخوله المستقلة)، واسم النموذج في الأعلى يتبع الصفحة الحالية. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FreeModelsPager(sites: List<FreeSite>, initialPage: Int, vm: RelayViewModel, onBack: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = initialPage) { sites.size }
    val scope = rememberCoroutineScope()
    val site = sites[pagerState.currentPage]
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("رجوع")) }
            Text(site.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${pagerState.currentPage + 1} / ${sites.size}", style = MaterialTheme.typography.bodySmall)
        }
        // شريط تبويبات علوي: ضغطة واحدة تفتح أي نموذج بدل السحب عبر 21 صفحة —
        // أسلس وأكثر توافقاً مع الهاتف.
        ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 0.dp) {
            sites.forEachIndexed { i, s ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.scrollToPage(i) } },
                    text = { Text(s.name) }
                )
            }
        }
        Text(
            tr("بدّل من التبويب العلوي أو اسحب يميناً ويساراً."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
            FreeBrowserPanel(sites[page], vm, active = pagerState.currentPage == page)
        }
    }
}

/** متصفح نموذج واحد: شريط تبويباته فقط، وزر + يفتح تبويباً جديداً للنموذج نفسه. */
@Composable
private fun FreeBrowserView(site: FreeSite, vm: RelayViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("رجوع")) }
            Text(site.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        FreeBrowserPanel(site, vm)
    }
}

/** لوحة متصفح نموذج واحد (تبويباته وأزراره وعرضه) — تُستخدم وحدها في متصفح
 * النموذج، ومرة لكل صفحة في عرض النماذج المختارة، فلكل صفحة تبويباتها وجلسة
 * تسجيل دخولها المستقلة عن الصفحات الأخرى. */
@Composable
/**
 * لوحة متصفح نموذج واحد. المعامل active يعني أن هذه الصفحة هي الظاهرة
 * حالياً في الـ Pager؛ الجلسات غير الظاهرة تُعلَّق (setActive(false))
 * فيتوقف فيديوها ومؤقتاتها، فيخفّ المتصفح وتختفي الحركة غير الطبيعية.
 */
private fun FreeBrowserPanel(site: FreeSite, vm: RelayViewModel, active: Boolean = true) {
    val ctx = LocalContext.current
    // رفع الملفات من الهاتف: منتقي أندرويد يسلّم الملفات المختارة إلى
    // جسر Gecko (FilePickerBridge) فيُنسخ إلى cacheDir وتُسلَّم للصفحة —
    // فيعمل زر الرفع داخل مواقع الذكاء الاصطناعي (ChatGPT وClaude وغيرهما).
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        deliverPickedFiles(ctx, uri?.let { listOf(it) })
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        deliverPickedFiles(ctx, uris)
    }
    SideEffect {
        FilePickerBridge.launchPicker = { mime, multiple ->
            if (multiple) multiPicker.launch(mime) else singlePicker.launch(mime)
        }
    }
    val space = "free:${site.id}"
    remember { BrowserHub.init(ctx, site.url, space); true }
    val spaceTabs = BrowserHub.tabs.filter { it.space == space }
    val tab = BrowserHub.selectedTab(space)

    var addr by remember(tab?.id) { mutableStateOf(tab?.url ?: "") }
    LaunchedEffect(tab?.url) { tab?.url?.let { addr = it } }
    var moreMenu by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // التلخيص: يقرأ الوكيل نص الصفحة الحالية ويعطي ملخصاً لها.
    var sumOpen by remember { mutableStateOf(false) }
    var sumQuestion by remember { mutableStateOf("") }
    var sumBusy by remember { mutableStateOf(false) }
    var sumAnswer by remember { mutableStateOf("") }
    fun askSum(q: String) {
        val t = tab ?: return
        sumBusy = true; sumAnswer = ""
        scope.launch {
            val snap = t.snapshot()
            sumAnswer = if (snap == null) tr("تعذّر قراءة الصفحة. انتظر اكتمال التحميل ثم أعد المحاولة (الجسر لا يعمل على صفحات about: وبعض الصفحات المحمية).")
            else vm.askAboutPage(q, snapshotToPrompt(snap))
            sumBusy = false
        }
    }
    // الصوت: قراءة الملخص بصوت عالٍ عبر تحويل النص إلى كلام (TTS) المدمج
    // في أندرويد — بلا مفتاح وبلا إنترنت إن كانت حزمة الصوت العربية مثبّتة.
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { onDispose { tts?.stop(); tts?.shutdown() } }
    fun speakSummary() {
        val text = sumAnswer
        if (text.isBlank()) return
        val existing = tts
        if (existing != null) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "summary")
        } else {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("ar")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "summary")
                }
            }
        }
    }

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
        // شريط تبويبات هذا النموذج فقط.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            spaceTabs.sortedByDescending { it.pinned }.forEach { t ->
                BrowserTabChip(t, t.id == tab?.id, onSelect = { BrowserHub.select(t) }, onClose = { BrowserHub.close(t) })
            }
            // زر + يفتح قائمة كما في متصفح GitHub: تبويب جديد ببيئة منفصلة
            // تماماً (تسجيل دخول مستقل)، أو تبويب بنفس بيئة التبويب الحالي.
            Box {
                AssistChip(onClick = { plusMenu = true }, label = { Text("＋") })
                DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                    DropdownMenuItem(text = { Text(tr("تبويب جديد (بيئة منفصلة)")) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, site.url, null, space) })
                    if (tab != null) DropdownMenuItem(text = { Text(trf("تبويب بنفس بيئة %s", tab.env)) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, site.url, tab.ctxId, space) })
                }
            }
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
                        DropdownMenuItem(text = { Text(tr("تلخيص هذه الصفحة")) }, onClick = {
                            moreMenu = false; sumOpen = true; askSum(tr("لخّص هذه الصفحة بإيجاز."))
                        })
                        // تصدير نتيجة المحادثة: نص الصفحة الحالية عبر مشاركة أندرويد
                        // (احفظه كملف نصي أو أرسله لأي تطبيق) — يقرأ فقط، ولا ينفّذ شيئاً.
                        DropdownMenuItem(text = { Text(tr("تصدير نتيجة المحادثة")) }, onClick = {
                            moreMenu = false
                            scope.launch {
                                val snap = tab.snapshot()
                                if (snap == null) {
                                    Toast.makeText(ctx, tr("تعذّر قراءة الصفحة. انتظر اكتمال التحميل ثم أعد المحاولة (الجسر لا يعمل على صفحات about: وبعض الصفحات المحمية)."), Toast.LENGTH_LONG).show()
                                } else {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, tab.title.ifBlank { site.name })
                                        putExtra(Intent.EXTRA_TEXT, snapshotToPrompt(snap))
                                    }
                                    runCatching { ctx.startActivity(Intent.createChooser(send, tr("تصدير نتيجة المحادثة")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(tr("فتح في المتصفح الخارجي")) }, onClick = {
                            moreMenu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                        DropdownMenuItem(text = { Text(if (tab.pinned) tr("فك تثبيت هذا التبويب") else tr("تثبيت هذا التبويب")) }, onClick = {
                            moreMenu = false; BrowserHub.togglePin(tab)
                        })
                        DropdownMenuItem(text = { Text(trf("مسح بيانات البيئة %s", tab.env)) }, onClick = {
                            moreMenu = false; BrowserHub.clearEnvironment(tab)
                        })
                    }
                }
            }
            TabProgressBar(tab)

            var ready by remember(tab.id) { mutableStateOf(tab.session.isOpen) }
            LaunchedEffect(tab.id) {
                while (!tab.session.isOpen) delay(50)
                ready = true
            }
            // تفعيل جلسة الصفحة الظاهرة فقط: التبويب المختار في النموذج
            // الظاهر يُفعَّل، وكل الجلسات الأخرى في النموذج (التبويبات
            // الأخرى) والنماذج غير الظاهرة تُعلَّق، فيتوقف فيديوها
            // ومؤقتاتها ويخفّ المتصفح.
            LaunchedEffect(active, tab.id, ready) {
                if (ready) spaceTabs.forEach { t -> t.session.setActive(active && t.id == tab.id) }
            }
            if (ready) {
                // عرض واحد يُعاد استخدامه عند تبديل التبويبات (تبديل الجلسة
                // فقط) بدل إنشاء GeckoView جديد في كل مرة — أخف وأسلس.
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { c -> GeckoView(c) },
                    update = { view -> if (view.session != tab.session) view.setSession(tab.session) },
                    onRelease = { it.releaseSession() }
                )
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { BrowserHub.newTabForSpace(ctx, space, site.url) }) { Text(tr("تبويب جديد")) }
            }
        }
    }
    // نافذة التلخيص: تفتح من قائمة ⋮ وتلخّص الصفحة فوراً، ويمكن سؤال الوكيل عنها.
    if (sumOpen && tab != null) {
        AlertDialog(
            onDismissRequest = { sumOpen = false },
            title = { Text(tr("تلخيص هذه الجلسة")) },
            confirmButton = { TextButton(onClick = { sumOpen = false }) { Text(tr("تم")) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("يقرأ الوكيل نص الصفحة الحالية ويعطيك ملخصاً لها. يُرسَل نص الصفحة وأسماء حقولها وقيمها (بلا كلمات مرور) إلى الوكيل الرئيسي المحدد في «سير العمل». يقرأ فقط ولا ينفّذ شيئاً في الصفحة."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { askSum(tr("لخّص هذه الصفحة بإيجاز.")) }, label = { Text(tr("لخّص")) }, enabled = !sumBusy)
                        AssistChip(onClick = { askSum(tr("ما حقول الإدخال في هذه الصفحة وما المطلوب تعبئته؟")) }, label = { Text(tr("اقرأ الحقول")) }, enabled = !sumBusy)
                    }
                    OutlinedTextField(sumQuestion, { sumQuestion = it }, label = { Text(tr("سؤالك عن الصفحة")) }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !sumBusy && sumQuestion.isNotBlank(), onClick = { askSum(sumQuestion) }) { Text(tr("اسأل")) }
                    if (sumBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (sumAnswer.isNotEmpty()) {
                        Text(sumAnswer, style = MaterialTheme.typography.bodyMedium)
                        AssistChip(onClick = { speakSummary() }, label = { Text(tr("استمع للملخص")) }, enabled = !sumBusy)
                    }
                }
            }
        )
    }
}

/** عرض النماذج المختارة بتبويبات علوية: كل نموذج في صفحة مستقلة كاملة
 * (لا نموذج يغطي نموذجاً آخر ولا نصفان جنب بعض)، والتبديل بينها من
 * شريط التبويبات العلوي — الضغطة على اسم النموذج تفتح صفحته كاملة،
 * والسحب يميناً ويساراً يبدّل الصفحة أيضاً ويُحدّث التبويب المختار. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickedModelsView(sites: List<FreeSite>, picked: List<Int>, vm: RelayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessionsMenu by remember { mutableStateOf(false) }
    // مفتاح الجلسة المفتوحة: يتبدّل فقط عند التبديل بين الجلسات المحفوظة،
    // فيُعاد إنشاء شريط التبويبات والمتصفح من الصفحة الأولى.
    var sessionKey by remember { mutableStateOf(PickedModelsStore.currentId.value ?: "none") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("رجوع")) }
            Text(tr("النماذج المختارة"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // حفظ الجلسة المفتوحة كجلسة جديدة مسماة (يمكن حفظ عدة جلسات).
            TextButton(onClick = {
                val s = PickedModelsStore.saveCurrent(ctx, picked.map { sites[it].id })
                Toast.makeText(ctx, tr("تم حفظ الجلسة") + ": " + s.name, Toast.LENGTH_SHORT).show()
            }) { Text(tr("حفظ الجلسة")) }
            // التبديل بين الجلسات المحفوظة من داخل الصفحة.
            Box {
                TextButton(onClick = { sessionsMenu = true }) { Text(tr("الجلسات")) }
                DropdownMenu(expanded = sessionsMenu, onDismissRequest = { sessionsMenu = false }) {
                    if (PickedModelsStore.sessions.isEmpty()) {
                        DropdownMenuItem(text = { Text(tr("لا توجد جلسات محفوظة بعد")) }, onClick = { sessionsMenu = false })
                    }
                    PickedModelsStore.sessions.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name + " — " + s.modelIds.mapNotNull { id -> sites.firstOrNull { it.id == id }?.name }.joinToString("، ")) }, onClick = {
                            sessionsMenu = false
                            // تأكد من وجود تبويب لكل نموذج في الجلسة قبل التبديل إليها.
                            s.modelIds.forEach { id ->
                                val st = sites.firstOrNull { it.id == id } ?: return@forEach
                                val sp = "free:${st.id}"
                                BrowserHub.init(ctx, st.url, sp)
                                if (BrowserHub.tabs.none { it.space == sp }) BrowserHub.newTabForSpace(ctx, sp, st.url)
                            }
                            PickedModelsStore.openSession(ctx, s)
                            sessionKey = s.id
                        })
                    }
                }
            }
        }
        key(sessionKey) {
            val pagerState = rememberPagerState { picked.size }
            Column(Modifier.fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 0.dp) {
                    picked.forEachIndexed { i, idx ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.scrollToPage(i) } },
                            text = { Text(sites[idx].name) }
                        )
                    }
                }
                Text(
                    tr("بدّل من التبويب العلوي أو اسحب يميناً ويساراً."),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    FreeBrowserPanel(sites[picked[page]], vm, active = pagerState.currentPage == page)
                }
            }
        }
    }
}
