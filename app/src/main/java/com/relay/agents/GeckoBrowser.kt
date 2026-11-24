package com.relay.agents

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TAG = "GeckoBrowser"
private const val BRIDGE_URI = "resource://android/assets/bridge/"
private const val BRIDGE_ID = "bridge@claudeagents.team.muse"

/** تبويب واحد: جلسة Gecko خاصة به، وبيئة تخزين (ctxId) منفصلة للكوكيز وتسجيل الدخول،
 * وينتمي إلى مساحة (space): "github" لمتصفح GitHub، أو "free:<معرّف النموذج>" للمتصفح المجاني،
 * فتبقى تبويبات كل نموذج منفصلة عن تبويبات GitHub وعن باقي النماذج. */
class BrowserTab(val id: String, val ctxId: String, startUrl: String, val env: String, val space: String) {
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)

    @Volatile var port: WebExtension.Port? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    val session: GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder().contextId(ctxId).build()
    ).also { s ->
        // رفع الملفات من الهاتف: كل جلسة تحتاج مندوب طلبات (PromptDelegate)
        // يعالج `<input type=file>`، وإلا يبقى زر الرفع في الصفحة ميتاً.
        s.promptDelegate = FilePromptDelegate()
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { this@BrowserTab.title = title ?: "" }
            override fun onCloseRequest(session: GeckoSession) { BrowserHub.close(this@BrowserTab) }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { BrowserHub.saveDownload(response) }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean
            ) {
                if (url != null) { this@BrowserTab.url = url; BrowserHub.persist() }
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { canFwd = canGoForward }
            // نوافذ منبثقة (مثل زر «الدخول بحساب Google»): تبويب جديد بنفس البيئة.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val child = BrowserHub.openChild(this@BrowserTab, uri)
                return GeckoResult.fromValue(child.session)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { loading = true }
            override fun onPageStop(session: GeckoSession, success: Boolean) { loading = false; progress = 0 }
            override fun onProgressChange(session: GeckoSession, progress: Int) { this@BrowserTab.progress = progress }
        }
    }

    fun onPortMessage(message: Any) {
        val o = message as? JSONObject ?: return
        pending[o.optString("id")]?.complete(o)
    }

    /** يطلب من الصفحة لقطة (نص + حقول) عبر إضافة الجسر. null إن لم تكن الصفحة جاهزة أو لا تدعم الإضافة. */
    suspend fun snapshot(timeoutMs: Long = 6000): JSONObject? {
        val p = port ?: return null
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        return try {
            p.postMessage(JSONObject().put("id", id).put("cmd", "snapshot"))
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            if (r != null && r.optBoolean("ok")) r.optJSONObject("data") else null
        } finally {
            pending.remove(id)
        }
    }
}

/** رفع الملفات من الهاتف داخل صفحات الويب: GeckoView لا يملك منتقي ملفات
 * خاصاً به — زر `<input type=file>` يصل إلى PromptDelegate، وبدون معالجة
 * يبقى الزر ميتاً. هذا الجسر يربط المندوب (خارج الـ Compose) بمنتقي
 * أندرويد (داخل الـ Compose): عند طلب ملف يُفتح منتقي النظام، وعند
 * الاختيار تُنسَخ الملفات إلى ملفات حقيقية في cacheDir (لأن `content://`
 * لا يعطي مساراً حقيقياً لـ Gecko) ثم تُسلَّم للصفحة. */
object FilePickerBridge {
    var pendingResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    var pendingPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    var launchPicker: ((String, Boolean) -> Unit)? = null
}

private class FilePromptDelegate : GeckoSession.PromptDelegate {
    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        // طلب معلّق سابق لم يُكمل: ألغِه قبل استبداله.
        FilePickerBridge.pendingResult?.let { old ->
            FilePickerBridge.pendingPrompt?.let { p ->
                if (!p.isComplete) runCatching { old.complete(p.dismiss()) }
            }
        }
        FilePickerBridge.pendingResult = result
        FilePickerBridge.pendingPrompt = prompt
        val mime = prompt.mimeTypes?.firstOrNull { it.contains("/") } ?: "*/*"
        val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
        val launcher = FilePickerBridge.launchPicker
        if (launcher == null) {
            runCatching { result.complete(prompt.dismiss()) }
            FilePickerBridge.pendingResult = null
            FilePickerBridge.pendingPrompt = null
        } else {
            launcher(mime, multiple)
        }
        return result
    }
}

/** تسليم الملفات المختارة إلى الصفحة: نسخها إلى cacheDir على خيط خلفية
 * (لأن `content://` لا يعطي مساراً)، ثم تأكيدها على الخيط الرئيسي.
 * الإلغاء أو الفشل يلغي الطلب بهدوء دون تعطيل الصفحة. */
fun deliverPickedFiles(ctx: Context, uris: List<Uri>?) {
    val result = FilePickerBridge.pendingResult ?: return
    val prompt = FilePickerBridge.pendingPrompt ?: return
    FilePickerBridge.pendingResult = null
    FilePickerBridge.pendingPrompt = null
    if (uris.isNullOrEmpty() || prompt.isComplete) {
        runCatching { result.complete(prompt.dismiss()) }
        return
    }
    Thread {
        val files = uris.mapNotNull { uri ->
            runCatching {
                val safe = (uri.lastPathSegment?.takeLast(20)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "file")
                val out = File(ctx.cacheDir, "upload_" + System.currentTimeMillis() + "_" + safe)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                Uri.fromFile(out)
            }.getOrNull()
        }
        Handler(Looper.getMainLooper()).post {
            if (files.isEmpty() || prompt.isComplete) {
                runCatching { result.complete(prompt.dismiss()) }
            } else if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE) {
                runCatching { result.complete(prompt.confirm(ctx, files.first())) }
            } else {
                runCatching { result.complete(prompt.confirm(ctx, files.toTypedArray())) }
            }
        }
    }.start()
}

/** مركز المتصفح: محرك Gecko واحد للعملية، وقائمة التبويبات، وحفظها بين مرات التشغيل. */
object BrowserHub {
    val tabs = mutableStateListOf<BrowserTab>()

    /** التبويب المختار في كل مساحة على حدة، فيبقى اختيار متصفح GitHub مستقلاً عن اختيار كل نموذج. */
    private val selectedBySpace = mutableStateMapOf<String, String>()

    /** التبويب المختار في هذه المساحة، أو أول تبويب فيها إن لم يُختر واحد بعد. */
    fun selectedTab(space: String): BrowserTab? =
        tabs.firstOrNull { it.space == space && it.id == selectedBySpace[space] }
            ?: tabs.firstOrNull { it.space == space }

    private var appCtx: Context? = null
    private var runtime: GeckoRuntime? = null
    private var bridge: WebExtension? = null
    private var storage: Storage? = null
    private var counter = 0

    fun runtime(ctx: Context): GeckoRuntime {
        appCtx = ctx.applicationContext
        runtime?.let { return it }
        val rt = GeckoRuntime.create(ctx.applicationContext)
        runtime = rt
        rt.webExtensionController.ensureBuiltIn(BRIDGE_URI, BRIDGE_ID).accept(
            { ext ->
                if (ext != null) {
                    bridge = ext
                    tabs.forEach { attachBridge(it) }
                }
            },
            { e -> Log.e(TAG, "bridge install failed", e) }
        )
        return rt
    }

    private fun attachBridge(tab: BrowserTab) {
        val ext = bridge ?: return
        val delegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                tab.port = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) { tab.onPortMessage(message) }
                    override fun onDisconnect(port: WebExtension.Port) { if (tab.port == port) tab.port = null }
                })
            }
        }
        tab.session.webExtensionController.setMessageDelegate(ext, delegate, "browser")
    }

    private fun register(ctx: Context, tab: BrowserTab, open: Boolean) {
        tabs.add(tab)
        if (open) tab.session.open(runtime(ctx))
        attachBridge(tab)
    }

    /** يستعيد التبويبات المحفوظة أو يفتح واحداً جديداً في المساحة المطلوبة. */
    fun init(ctx: Context, homeUrl: String, space: String = "github") {
        if (tabs.isNotEmpty()) return
        val st = Storage(ctx.applicationContext).also { storage = it }
        runtime(ctx)
        runCatching {
            val arr = JSONArray(st.setting("gecko_tabs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = BrowserTab(o.getString("id"), o.getString("ctx"), o.optString("url", homeUrl), o.optString("env", "A"), o.optString("space", "github"))
                register(ctx, t, true)
                t.session.loadUri(t.url)
            }
        }
        runCatching {
            val sel = JSONObject(st.setting("gecko_selected_map", "{}"))
            sel.keys().forEach { k -> selectedBySpace[k] = sel.getString(k) }
        }
        // توافق قديم: اختيار واحد محفوظ قبل المساحات يُنسب لمتصفح GitHub.
        if (!selectedBySpace.containsKey("github")) {
            st.setting("gecko_selected", "").takeIf { id -> tabs.any { it.id == id && it.space == "github" } }
                ?.let { selectedBySpace["github"] = it }
        }
        if (tabs.isEmpty()) newTab(ctx, homeUrl, null, space)
    }

    /** تبويب جديد في مساحة معينة. ctxId = null → بيئة جديدة منفصلة؛ وإلا يشارك بيئة تبويب موجود. */
    fun newTab(ctx: Context, url: String, ctxId: String?, space: String = "github"): BrowserTab {
        val id = UUID.randomUUID().toString().take(8)
        val envCtx = ctxId ?: ("env" + UUID.randomUUID().toString().take(8))
        val envName = tabs.firstOrNull { it.ctxId == envCtx }?.env ?: ('A' + (counter++ % 26)).toString() + (if (counter > 26) "${counter / 26}" else "")
        val t = BrowserTab(id, envCtx, url, envName, space)
        register(ctx, t, true)
        t.session.loadUri(url)
        select(t)
        persist()
        return t
    }

    /** تبويب جديد لنموذج مجاني: يشارك بيئة النموذج نفسها (فيبقى مسجّلاً دخوله)،
     * أو ينشئ بيئة جديدة للنموذج إن لم يكن له تبويب بعد. */
    fun newTabForSpace(ctx: Context, space: String, url: String): BrowserTab {
        val existing = tabs.firstOrNull { it.space == space }
        return newTab(ctx, url, existing?.ctxId, space)
    }

    internal fun openChild(parent: BrowserTab, uri: String): BrowserTab {
        val ctx = appCtx!!
        val t = BrowserTab(UUID.randomUUID().toString().take(8), parent.ctxId, uri, parent.env, parent.space)
        register(ctx, t, false) // يفتحها Gecko بنفسه بعد إرجاعها
        selectedBySpace[t.space] = t.id
        persist()
        return t
    }

    fun select(tab: BrowserTab) {
        selectedBySpace[tab.space] = tab.id
        // نفعّل تبويبات هذه المساحة فقط، فتبقى جلسة المساحة الأخرى مفعّلة —
        // وهذا ما يجعل عرض التقسيم يعمل: صفحتان جنب بعض في نفس الوقت.
        tabs.forEach { if (it.session.isOpen && it.space == tab.space) it.session.setActive(it === tab) }
        persistSelected()
    }

    private fun persistSelected() {
        val st = storage ?: return
        runCatching {
            val o = JSONObject()
            selectedBySpace.forEach { (k, v) -> o.put(k, v) }
            st.saveSetting("gecko_selected_map", o.toString())
            // توافق قديم: نحفظ اختيار GitHub في المفتاح القديم أيضاً.
            selectedBySpace["github"]?.let { st.saveSetting("gecko_selected", it) }
        }
    }

    fun close(tab: BrowserTab) {
        val spaceTabs = tabs.filter { it.space == tab.space }
        val pos = spaceTabs.indexOf(tab)
        if (pos < 0) return
        runCatching { tab.session.close() }
        tabs.remove(tab)
        val remaining = tabs.filter { it.space == tab.space }
        if (remaining.isEmpty()) {
            selectedBySpace.remove(tab.space)
            // إن أُغلق آخر تبويب في التطبيق كله وكان من GitHub، نفتح تبويب GitHub جديد كما قبل.
            if (tab.space == "github" && tabs.isEmpty()) {
                appCtx?.let { newTab(it, "https://github.com", null) }
            }
        } else if (selectedBySpace[tab.space] == tab.id) {
            select(remaining[minOf(pos, remaining.size - 1)])
        }
        persist()
    }

    /** يمسح كوكيز وتخزين بيئة هذا التبويب فقط (فيخرج من الحسابات المسجّلة فيها). */
    fun clearEnvironment(tab: BrowserTab) {
        runtime?.storageController?.clearDataForSessionContext(tab.ctxId)
        tab.session.reload()
    }

    fun persist() {
        val st = storage ?: return
        runCatching {
            val arr = JSONArray()
            tabs.forEach { arr.put(JSONObject().put("id", it.id).put("ctx", it.ctxId).put("url", it.url).put("env", it.env).put("space", it.space)) }
            st.saveSetting("gecko_tabs", arr.toString())
        }
        persistSelected()
    }

    // ---- التنزيلات ----
    fun saveDownload(r: WebResponse) {
        val ctx = appCtx ?: return
        val body = r.body ?: return
        val hdr = { k: String -> r.headers.entries.firstOrNull { it.key.equals(k, true) }?.value }
        val cd = hdr("content-disposition")
        val mime = hdr("content-type")?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(r.uri, cd, mime)
        val ui = Handler(Looper.getMainLooper())
        ui.post { Toast.makeText(ctx, trf("بدأ التنزيل: %s", name), Toast.LENGTH_LONG).show() }
        thread {
            runCatching {
                body.use { input ->
                    if (Build.VERSION.SDK_INT >= 29) {
                        val v = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("insert failed")
                        ctx.contentResolver.openOutputStream(uri)!!.use { out -> input.copyTo(out) }
                    } else {
                        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                        java.io.File(dir, name).outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }.onSuccess {
                ui.post { Toast.makeText(ctx, trf("اكتمل التنزيل: %s", name), Toast.LENGTH_LONG).show() }
            }.onFailure {
                Log.e(TAG, "download failed", it)
                ui.post { Toast.makeText(ctx, tr("تعذّر بدء التنزيل"), Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** يحوّل لقطة الصفحة إلى نص مضغوط للوكيل (كلمات المرور محجوبة أصلاً من الجسر). */
internal fun snapshotToPrompt(s: JSONObject): String {
    val sb = StringBuilder()
    sb.append("URL: ").append(s.optString("url")).append('\n')
    sb.append("Title: ").append(s.optString("title")).append('\n')
    if (s.optBoolean("hasPassword")) sb.append("(الصفحة فيها حقل كلمة مرور؛ قيمته غير مقروءة)\n")
    sb.append("\nFields:\n")
    val f = s.optJSONArray("fields") ?: JSONArray()
    for (i in 0 until f.length()) {
        val o = f.getJSONObject(i)
        sb.append("- ").append(o.optString("tag"))
        if (o.optString("type").isNotEmpty()) sb.append('[').append(o.optString("type")).append(']')
        val label = o.optString("label").ifEmpty { o.optString("name").ifEmpty { o.optString("id") } }
        if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
        if (o.has("text")) sb.append(" text=").append(o.optString("text"))
        if (o.has("value") && o.optString("value").isNotEmpty()) sb.append(" value=").append(o.optString("value"))
        if (o.optBoolean("secret")) sb.append(" (secret)")
        if (o.optBoolean("required")) sb.append(" required")
        if (o.has("checked")) sb.append(" checked=").append(o.optBoolean("checked"))
        sb.append('\n')
    }
    sb.append("\nPage text:\n").append(s.optString("text"))
    return sb.toString()
}

/** متصفح Gecko متعدد التبويبات: كل تبويب ببيئة تسجيل دخول منفصلة، ومربوط بالوكيل الرئيسي لقراءة الصفحة وحقولها. */
@Composable
fun GeckoBrowserTab(vm: RelayViewModel, gh: GhConfig) {
    val ctx = LocalContext.current
    // رفع الملفات من الهاتف: منتقي أندرويد يسلّم الملفات المختارة إلى
    // جسر Gecko (FilePickerBridge) فيُنسخ إلى cacheDir وتُسلَّم للصفحة.
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
    val scope = rememberCoroutineScope()
    val base = "https://github.com/${gh.owner}/${gh.repo}"
    remember { BrowserHub.init(ctx, base); true }
    // متصفح GitHub يعرض تبويبات مساحة github فقط، لا تبويبات المتصفح المجاني.
    val spaceTabs = BrowserHub.tabs.filter { it.space == "github" }
    val tab = BrowserHub.selectedTab("github")

    var addr by remember(tab?.id) { mutableStateOf(tab?.url ?: "") }
    LaunchedEffect(tab?.url) { tab?.url?.let { addr = it } }
    var plusMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var agentOpen by remember { mutableStateOf(false) }

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
        // شريط التبويبات
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spaceTabs.forEach { t ->
                FilterChip(
                    selected = t.id == tab?.id,
                    onClick = { BrowserHub.select(t) },
                    label = { Text("🔒${t.env} · " + t.title.ifBlank { Uri.parse(t.url).host ?: tr("تبويب") }.take(16), maxLines = 1) },
                    trailingIcon = { Text("✕", Modifier.clickable { BrowserHub.close(t) }.padding(2.dp)) }
                )
            }
            Box {
                AssistChip(onClick = { plusMenu = true }, label = { Text("＋") })
                DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                    DropdownMenuItem(text = { Text(tr("تبويب جديد (بيئة منفصلة)")) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, "https://github.com", null) })
                    if (tab != null) DropdownMenuItem(text = { Text(trf("تبويب بنفس بيئة %s", tab.env)) },
                        onClick = { plusMenu = false; BrowserHub.newTab(ctx, "https://github.com", tab.ctxId) })
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
                TextButton(onClick = { agentOpen = true }) { Text("🤖") }
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
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    tr("الكود") to base, "Actions" to "$base/actions", tr("الإصدارات") to "$base/releases",
                    "Issues" to "$base/issues", "Pull requests" to "$base/pulls", tr("الإعدادات") to "$base/settings",
                    "Google" to "https://accounts.google.com"
                ).forEach { (label, url) -> AssistChip(onClick = { tab.session.loadUri(url) }, label = { Text(label) }) }
            }
            if (tab.loading && tab.progress in 1..99) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())

            // لا نربط العرض إلا بعد فتح الجلسة (تبويبات النوافذ المنبثقة يفتحها Gecko بنفسه).
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
        }
    }

    if (agentOpen && tab != null) {
        var question by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var answer by remember { mutableStateOf("") }
        fun ask(q: String) {
            busy = true; answer = ""
            scope.launch {
                val snap = tab.snapshot()
                answer = if (snap == null) tr("تعذّر قراءة الصفحة. انتظر اكتمال التحميل ثم أعد المحاولة (الجسر لا يعمل على صفحات about: وبعض الصفحات المحمية).")
                else vm.askAboutPage(q, snapshotToPrompt(snap))
                busy = false
            }
        }
        AlertDialog(
            onDismissRequest = { agentOpen = false },
            title = { Text(tr("اسأل الوكيل عن هذه الصفحة")) },
            confirmButton = { TextButton(onClick = { agentOpen = false }) { Text(tr("تم")) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("عند الضغط يُرسَل نص الصفحة وأسماء حقولها وقيمها (بلا كلمات مرور ولا بيانات بطاقات) إلى الوكيل الرئيسي المحدد في «سير العمل». يقرأ فقط ولا ينفّذ شيئاً في الصفحة."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { ask(tr("لخّص هذه الصفحة بإيجاز.")) }, label = { Text(tr("لخّص")) }, enabled = !busy)
                        AssistChip(onClick = { ask(tr("ما حقول الإدخال في هذه الصفحة وما المطلوب تعبئته؟")) }, label = { Text(tr("اقرأ الحقول")) }, enabled = !busy)
                    }
                    OutlinedTextField(question, { question = it }, label = { Text(tr("سؤالك عن الصفحة")) }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && question.isNotBlank(), onClick = { ask(question) }) { Text(tr("اسأل")) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (answer.isNotEmpty()) Text(answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        )
    }
}
