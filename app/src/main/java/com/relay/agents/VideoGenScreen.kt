package com.relay.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private enum class SceneStatus { IDLE, WRITING, VIDEO, READY, FAILED }

/** مواقع الفيديو في المتصفح المجاني التي يمكن إرسال وصف المشهد إليها
 * (نسخ الوصف إلى الحافظة + فتح تبويب الموقع — فلا API مجانياً ولا
 * معامل رابط يعبّئ الوصف تلقائياً في أي منها، حسب بحث 2026-11-24). */
private val VIDEO_SITE_IDS = setOf("flow", "dreamina", "pixverse", "kling", "haiper", "hailuo", "pika")

/** يحفظ صورة إطار المشهد في مجلد التنزيلات العام حتى يستطيع منتقي
 * الملفات في المتصفح (المبني أصلاً لرفع الملفات من الهاتف) رؤيتها
 * ورفعها إلى موقع الفيديو — فالحافظة لا توصل صورة إلى صندوق رفع
 * في صفحة ويب، والتنزيلات هي الجسر الشرعي الوحيد. (internal لتستخدمها
 * شاشة توليد الصور أيضاً لحفظ صورها.) */
internal fun saveImageToDownloads(ctx: Context, file: File, name: String): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            file.copyTo(File(dir, name), overwrite = true)
            true
        }
    }.getOrDefault(false)
}

private data class SceneState(
    val index: Int,
    val source: String,
    val agentName: String = "",
    val visualPrompt: String = "",
    val originalPrompt: String = "",
    val videoModel: String = "",
    val provider: String = "pollinations",
    val jobId: String = "",
    val videoFile: File? = null,
    val altVideoFile: File? = null,
    val altVideoModel: String = "",
    val imageFile: File? = null,
    val imageStatus: String = "",
    val imageError: String = "",
    val status: SceneStatus = SceneStatus.IDLE,
    val error: String = "",
    // مخطط اللقطة (7+14): حركة كاميرا + مدة + نسبة أبعاد + نموذج لكل لقطة.
    val camera: String = "ثابت",
    val shotDuration: Int = 0, // 0 = المدة العامة
    val aspect: String = "16:9",
    val modelOverride: String = ""
)

/** تعليمات كتابة وصف المشهد بالإنجليزية (لنماذج الفيديو) — نص توجيه، لا واجهة. */
private const val SCENE_SYSTEM =
    "Turn the following scene into ONE vivid video-generation prompt in English (1-2 sentences): " +
        "describe the visuals, setting, action, camera movement and mood. " +
        "Reply with only the prompt, no explanation."

/** يقسّم النص الطويل إلى مشاهد: فقرات أولاً، ثم جمل مجمّعة، بحد أقصى 6 مشاهد. */
private fun splitScenes(text: String, maxScenes: Int = 6): List<String> {
    val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val parts = if (paras.size >= 2) {
        paras
    } else {
        val sentences = text.split(Regex("(?<=[.!؟])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size <= 1) listOf(text.trim()) else sentences.chunked(2).map { it.joinToString(" ") }
    }
    if (parts.size <= maxScenes) return parts.map { it.take(600) }
    val head = parts.take(maxScenes - 1)
    val tail = parts.drop(maxScenes - 1).joinToString(" ")
    return (head + tail).map { it.take(600) }
}

@Composable
fun VideoGenTab(vm: RelayViewModel, onOpenSite: () -> Unit = {}) {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    val cfg = remember { VideoGenConfig(storage) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var magicKey by remember { mutableStateOf(cfg.magicHourKey) }
    var agnesKey by remember { mutableStateOf(cfg.agnesKey) }
    var novaiKey by remember { mutableStateOf(cfg.novaiKey) }
    var provider by remember { mutableStateOf("agnes") }
    var lastAgnesAt by remember { mutableStateOf(0L) }
    var chainMode by remember { mutableStateOf(true) }
    var mhSelected by remember { mutableStateOf(setOf("ltx-2.5")) }
    var longText by remember { mutableStateOf("") }
    var durationSec by remember { mutableStateOf(5) }
    var videoModels by remember { mutableStateOf<List<VideoGenClient.VideoModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scenes by remember { mutableStateOf<List<SceneState>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var busy by mutableStateOf(false)
    var playing by remember { mutableStateOf<File?>(null) }
    // المراجع المثبّتة (10): حتى 3 صور تُستخدم كإطار أول عند غياب إطار المشهد.
    var refs by remember { mutableStateOf<List<File>>(emptyList()) }
    // قفل الإطار (10): كل اللقطات تعيد استخدام إطار اللقطة الأولى.
    var lockFrame by remember { mutableStateOf(false) }
    // بدائل (11): بديل ثانٍ بنموذج مختلف لكل لقطة.
    var variants by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val videoSites = remember { freeSitesList().filter { it.id in VIDEO_SITE_IDS } }

    /** يفتح صفحة إنشاء مفتاح المزوّد في متصفح الهاتف الخارجي —
     * ينشئ المستخدم المفتاح المجاني هناك وينسخه ثم يلصقه هنا. */
    fun openKeyPage(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(ctx, tr("تعذّر فتح المتصفح"), Toast.LENGTH_SHORT).show()
        }
    }

    /** يلصق مفتاحاً من الحافظة في خانة المفتاح. */
    fun pasteKey(set: (String) -> Unit) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (t.isBlank()) Toast.makeText(ctx, tr("الحافظة فارغة"), Toast.LENGTH_SHORT).show()
        else {
            set(t)
            Toast.makeText(ctx, tr("تم لصق المفتاح"), Toast.LENGTH_SHORT).show()
        }
    }
    val refPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || refs.size >= 3) return@rememberLauncherForActivityResult
        val file = runCatching {
            val target = File(ctx.cacheDir, "ref-${UUID.randomUUID()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            target
        }.getOrNull()
        if (file != null && file.length() > 1024) refs = refs + file
        else Toast.makeText(ctx, tr("تعذّر قراءة الصورة"), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        VideoGenClient.listVideoModels()
            .onSuccess { list ->
                videoModels = list
                val defaults = listOf("bytedance/seedance-2.5-flash", "alibaba/wan-2.7")
                    .filter { d -> list.any { it.id == d } }
                selected = (defaults.ifEmpty { list.take(2).map { it.id } }).toSet()
            }
            .onFailure { msg = trf("تعذّر الجلب: %s", it.message?.take(140)) }
    }

    fun updateScene(i: Int, f: (SceneState) -> SceneState) {
        scenes = scenes.mapIndexed { idx, s -> if (idx == i) f(s) else s }
    }

    /** يستعلم مهمة مشهد حتى تنتهي (مهلة 15 دقيقة). */
    suspend fun pollScene(i: Int, timeoutMs: Long) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            delay(5000)
            val s = scenes.getOrNull(i) ?: return
            if (s.status != SceneStatus.VIDEO || s.jobId.isBlank()) return
            if (s.provider == "magic") {
                MagicHourClient.projectStatus(s.jobId, cfg.magicHourKey)
                    .onSuccess { (st, url) ->
                        when (st) {
                            "complete" -> {
                                if (url.isNullOrBlank()) {
                                    updateScene(i) { it.copy(status = SceneStatus.FAILED, error = "complete بلا رابط تنزيل") }
                                } else {
                                    val target = File(ctx.cacheDir, "videogen-${s.index}-${System.currentTimeMillis()}.mp4")
                                    MagicHourClient.downloadUrl(url, target)
                                        .onSuccess { updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = target) } }
                                        .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
                                }
                            }
                            "error", "canceled" -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = st) }
                            else -> Unit
                        }
                    }
                    .onFailure { /* عابر — نكمل الاستعلام */ }
            } else {
                VideoGenClient.jobStatus(s.jobId, cfg.apiKey)
                    .onSuccess { st ->
                        when (st) {
                            "succeeded" -> {
                                val target = File(ctx.cacheDir, "videogen-${s.index}-${System.currentTimeMillis()}.mp4")
                                VideoGenClient.downloadResult(s.jobId, cfg.apiKey, target)
                                    .onSuccess { updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = target) } }
                                    .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
                            }
                            "failed", "cancelled" -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = st) }
                            else -> Unit
                        }
                    }
                    .onFailure { /* عابر — نكمل الاستعلام */ }
            }
        }
        if (scenes.getOrNull(i)?.status == SceneStatus.VIDEO) {
            updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("انتهت مهلة الانتظار")) }
        }
    }

    /** المرحلة 1: كل وكيل مفعّل يكتب وصف مشهد بالتناوب مع غيره. */
    fun writeScenes() {
        val agents = vm.agents.filter { it.enabled && !it.provider.manual }
        if (longText.isBlank()) {
            msg = tr("اكتب النص الطويل أولاً")
            return
        }
        if (agents.isEmpty()) {
            msg = tr("أضف وكيلاً مفعّلاً أولاً لكتابة وصف المشاهد")
            return
        }
        val parts = splitScenes(longText)
        scenes = parts.mapIndexed { i, p -> SceneState(index = i, source = p) }
        playing = null
        msg = ""
        busy = true
        scope.launch {
            parts.forEachIndexed { i, part ->
                val agent = agents[i % agents.size]
                updateScene(i) { it.copy(status = SceneStatus.WRITING, agentName = agent.name) }
                when (val r = ClaudeClient.send(agent, SCENE_SYSTEM, part, maxTokens = 512)) {
                    is ApiResult.Ok -> updateScene(i) {
                        it.copy(status = SceneStatus.IDLE, visualPrompt = r.text.trim().take(600))
                    }
                    is ApiResult.Fail -> updateScene(i) {
                        it.copy(status = SceneStatus.IDLE, visualPrompt = part, error = r.message.take(120))
                    }
                }
            }
            busy = false
        }
    }

    // ربط المحادثة بالفيديو: نص أُرسل من رد نموذج نصي في المحادثة
    // يصل هنا فيتحول تلقائياً إلى مشاهد جاهزة للتوليد.
    LaunchedEffect(vm.pendingVideoText) {
        val t = vm.pendingVideoText
        if (t.isNotBlank()) {
            vm.pendingVideoText = ""
            longText = t
            writeScenes()
        }
    }

    // ربط الصور بالفيديو: صورة من شاشة توليد الصور أو الكاميرا تصل
    // هنا فتُضاف مرجعاً مثبّتاً (إطار أول للمقطع).
    LaunchedEffect(vm.pendingImagePath) {
        val p = vm.pendingImagePath
        if (p.isNotBlank()) {
            vm.pendingImagePath = ""
            val f = File(p)
            if (f.exists() && f.length() > 1024 && refs.size < 3) {
                refs = refs + f
                msg = tr("أُضيفت الصورة كمرجع مثبّت — ستُستخدم كإطار أول للمقطع")
            }
        }
    }

    /** السلسلة — المرحلة الوسطى: يولّد نموذج الصورة إطار المشهد
     * من الوصف نفسه (Pollinations بلا مفتاح في العادة). */
    suspend fun generateImageForScene(i: Int): Boolean {
        val s = scenes.getOrNull(i) ?: return false
        if (s.visualPrompt.isBlank()) return false
        // قفل الإطار (10): اللقطات التالية تعيد استخدام إطار اللقطة الأولى.
        if (lockFrame && i > 0) {
            val first = scenes.getOrNull(0)?.imageFile
            if (first != null) {
                updateScene(i) { it.copy(imageFile = first, imageStatus = "done") }
                return true
            }
        }
        updateScene(i) { it.copy(imageStatus = "loading", imageError = "") }
        val (w, h) = VideoPlanner.aspectSize(s.aspect)
        return PollinationsImage.generateImage(s.visualPrompt, ctx.cacheDir, cfg.apiKey, w, h)
            .onSuccess { file -> updateScene(i) { it.copy(imageFile = file, imageStatus = "done") } }
            .onFailure { err ->
                updateScene(i) { it.copy(imageStatus = "failed", imageError = err.message?.take(120) ?: "") }
            }.isSuccess
    }

    /** محسّن الوصف (8): وكيل يعيد كتابة الوصف بصيغة Flow الخماسية
     * (نص→فيديو) أو حركة وكاميرا فقط (صورة→فيديو). */
    fun enhanceScene(i: Int) {
        val s = scenes.getOrNull(i) ?: return
        if (s.visualPrompt.isBlank()) return
        val agents = vm.agents.filter { it.enabled && !it.provider.manual }
        if (agents.isEmpty()) {
            msg = tr("أضف وكيلاً مفعّلاً أولاً لتحسين الوصف")
            return
        }
        scope.launch {
            busy = true
            val sys = if (chainMode) VideoPlanner.ENHANCE_I2V_SYSTEM else VideoPlanner.ENHANCE_T2V_SYSTEM
            when (val r = ClaudeClient.send(agents.first(), sys, s.visualPrompt, maxTokens = 512)) {
                is ApiResult.Ok -> {
                    updateScene(i) {
                        it.copy(
                            originalPrompt = if (it.originalPrompt.isEmpty()) it.visualPrompt else it.originalPrompt,
                            visualPrompt = r.text.trim().take(600)
                        )
                    }
                    msg = tr("تم تحسين الوصف")
                }
                is ApiResult.Fail -> msg = trf("تعذّر التحسين: %s", r.message.take(120))
            }
            busy = false
        }
    }

    /** يستعلم مهمة البديل حتى تنتهي؛ لا يمسّ حالة اللقطة الرئيسية. */
    suspend fun pollAlt(i: Int, jobId: String, altModel: String, magic: Boolean) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15 * 60 * 1000) {
            delay(5000)
            if (scenes.getOrNull(i)?.videoFile == null) return
            if (magic) {
                val res = MagicHourClient.projectStatus(jobId, cfg.magicHourKey).getOrNull() ?: continue
                val (st, url) = res
                if (st == "complete" && !url.isNullOrBlank()) {
                    val target = File(ctx.cacheDir, "videogen-alt-${i}-${System.currentTimeMillis()}.mp4")
                    MagicHourClient.downloadUrl(url, target)
                        .onSuccess { updateScene(i) { it.copy(altVideoFile = target, altVideoModel = altModel) } }
                    return
                }
                if (st == "error" || st == "canceled") return
            } else {
                val st = VideoGenClient.jobStatus(jobId, cfg.apiKey).getOrNull() ?: continue
                if (st == "succeeded") {
                    val target = File(ctx.cacheDir, "videogen-alt-${i}-${System.currentTimeMillis()}.mp4")
                    VideoGenClient.downloadResult(jobId, cfg.apiKey, target)
                        .onSuccess { updateScene(i) { it.copy(altVideoFile = target, altVideoModel = altModel) } }
                    return
                }
                if (st == "failed" || st == "cancelled") return
            }
        }
    }

    suspend fun submitAlt(i: Int, altModel: String) {
        val s = scenes.getOrNull(i) ?: return
        val dur = if (s.shotDuration > 0) s.shotDuration else durationSec
        val prompt = s.visualPrompt + " — " + VideoPlanner.cameraPhrase(s.camera)
        if (provider == "magic") {
            val imgPath = s.imageFile?.let { MagicHourClient.uploadImage(it, cfg.magicHourKey).getOrNull() }
            val res = if (chainMode && imgPath != null)
                MagicHourClient.submitImageToVideo(prompt, altModel, dur, imgPath, cfg.magicHourKey, s.aspect)
            else MagicHourClient.submitTextToVideo(prompt, altModel, dur, cfg.magicHourKey, s.aspect)
            res.onSuccess { id -> pollAlt(i, id, altModel, true) }
        } else {
            when (val r = VideoGenClient.submitVideo(prompt, altModel, dur, cfg.apiKey, ctx.cacheDir, s.aspect)) {
                is VideoGenClient.SubmitResult.Started -> pollAlt(i, r.jobId, altModel, false)
                is VideoGenClient.SubmitResult.Ready -> updateScene(i) { it.copy(altVideoFile = r.file, altVideoModel = altModel) }
                else -> Unit
            }
        }
    }

    /** بديل ثانٍ (11): إن فُعّلت البدائل ووجد نموذج ثانٍ مختلف،
     * يولَّد نفس المشهد به ويُحفظ بديلاً يختار المستخدم الاحتفاظ به. */
    suspend fun maybeAlt(i: Int, models: List<String>, mainModel: String) {
        if (!variants) return
        // البدائل لمزوّدي Pollinations وMagic Hour فقط — Agnes وNovAI نموذج واحد لكل منهما.
        if (provider != "magic" && provider != "pollinations") return
        val s = scenes.getOrNull(i) ?: return
        if (s.status != SceneStatus.READY) return
        val alt = models.firstOrNull { it != mainModel } ?: return
        submitAlt(i, alt)
    }


    /** يستعلم مهمة Agnes حتى تنتهي (مهلة 15 دقيقة). */
    suspend fun pollAgnes(i: Int, videoId: String, timeoutMs: Long) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            delay(5000)
            val s = scenes.getOrNull(i) ?: return
            if (s.status != SceneStatus.VIDEO || s.jobId.isBlank()) return
            AgnesClient.pollStatus(cfg.agnesKey, videoId)
                .onSuccess { (st, url) ->
                    when (st) {
                        "completed" -> {
                            if (url.isNullOrBlank()) {
                                updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("اكتمل بلا رابط تنزيل")) }
                            } else {
                                val target = File(ctx.cacheDir, "videogen-${s.index}-${System.currentTimeMillis()}.mp4")
                                MagicHourClient.downloadUrl(url, target)
                                    .onSuccess { updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = target) } }
                                    .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
                            }
                        }
                        "failed" -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("فشل توليد Agnes")) }
                        else -> Unit
                    }
                }
                .onFailure { /* عابر — نكمل الاستعلام */ }
        }
        if (scenes.getOrNull(i)?.status == SceneStatus.VIDEO) {
            updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("انتهت مهلة الانتظار")) }
        }
    }

    /** يستعلم مهمة NovAI حتى تنتهي (مهلة 15 دقيقة). */
    suspend fun pollNovAi(i: Int, taskId: String, timeoutMs: Long) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            delay(5000)
            val s = scenes.getOrNull(i) ?: return
            if (s.status != SceneStatus.VIDEO || s.jobId.isBlank()) return
            NovAiClient.taskStatus(cfg.novaiKey, taskId)
                .onSuccess { (st, url) ->
                    when (st) {
                        "completed" -> {
                            if (url.isNullOrBlank()) {
                                updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("اكتمل بلا رابط تنزيل")) }
                            } else {
                                val target = File(ctx.cacheDir, "videogen-${s.index}-${System.currentTimeMillis()}.mp4")
                                MagicHourClient.downloadUrl(url, target)
                                    .onSuccess { updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = target) } }
                                    .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
                            }
                        }
                        "failed" -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("فشل توليد NovAI")) }
                        else -> Unit
                    }
                }
                .onFailure { /* عابر — نكمل الاستعلام */ }
        }
        if (scenes.getOrNull(i)?.status == SceneStatus.VIDEO) {
            updateScene(i) { it.copy(status = SceneStatus.FAILED, error = tr("انتهت مهلة الانتظار")) }
        }
    }

    /** يرسل مشهداً واحداً إلى NovAI (البديل الاحتياطي عند امتلاء طابور Agnes). */
    suspend fun submitNovAiScene(i: Int, prompt: String) {
        val s = scenes.getOrNull(i) ?: return
        updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = NovAiClient.MODEL, provider = "novai", error = "", jobId = "") }
        NovAiClient.submitVideo(cfg.novaiKey, prompt, s.aspect)
            .onSuccess { id ->
                updateScene(i) { it.copy(jobId = id) }
                pollNovAi(i, id, 15 * 60 * 1000)
            }
            .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
    }

    /** يرسل مشهداً واحداً ثم يستعلم حالته (يُستخدم للتوليد الجماعي ولإعادة المحاولة). */
    suspend fun submitScene(i: Int) {
        val s = scenes.getOrNull(i) ?: return
        if (s.visualPrompt.isBlank()) return
        // وصف اللقطة النهائي: الوصف + حركة الكاميرا المختارة في مخطط اللقطة.
        val prompt = s.visualPrompt + " — " + VideoPlanner.cameraPhrase(s.camera)
        // Agnes (المزوّد الافتراضي — مجاني $0): حدّه طلب واحد في الدقيقة.
        if (provider == "agnes") {
            val dur = (if (s.shotDuration > 0) s.shotDuration else durationSec).coerceIn(4, 12)
            val now = System.currentTimeMillis()
            val wait = 60_000 - (now - lastAgnesAt)
            if (wait > 0) {
                updateScene(i) { it.copy(status = SceneStatus.FAILED, error = trf("انتظر %s ثانية (حد Agnes: طلب واحد في الدقيقة)", wait / 1000 + 1)) }
                return
            }
            lastAgnesAt = now
            updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = AgnesClient.MODEL, provider = "agnes", error = "", jobId = "") }
            AgnesClient.submitVideo(cfg.agnesKey, prompt, dur, s.aspect)
                .onSuccess { id ->
                    updateScene(i) { it.copy(jobId = id) }
                    pollAgnes(i, id, 15 * 60 * 1000)
                }
                .onFailure { err ->
                    val m = err.message ?: ""
                    if ((m.contains("429") || m.contains("ممتلئ")) && cfg.novaiKey.isNotBlank()) {
                        Toast.makeText(ctx, tr("طابور Agnes ممتلئ — التبديل إلى NovAI"), Toast.LENGTH_LONG).show()
                        submitNovAiScene(i, prompt)
                    } else {
                        updateScene(i) { it.copy(status = SceneStatus.FAILED, error = m.take(160)) }
                    }
                }
            return
        }
        // NovAI (البديل الاحتياطي): 5 مقاطع مجانية يومياً.
        if (provider == "novai") {
            submitNovAiScene(i, prompt)
            return
        }
        val dur = if (s.shotDuration > 0) s.shotDuration else durationSec
        if (provider == "magic") {
            val models = mhSelected.toList()
            if (models.isEmpty()) return
            val model = s.modelOverride.ifBlank { models[i % models.size] }
            updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = model, provider = "magic", error = "", jobId = "") }
            // السلسلة: الصورة إطار أول للمقطع؛ إن تعذّر توليدها أو
            // رفعها، يولَّد الفيديو من النص مباشرة ولا يتعطل المشهد.
            val projectId: Result<String> = if (chainMode) {
                var img = scenes.getOrNull(i)?.imageFile
                // مراجع مثبّتة (10): إن لم توجد صورة إطار، يُستخدم أول
                // مرجع مثبّت كإطار أول للمقطع.
                if (img == null && refs.isNotEmpty()) {
                    img = refs.first()
                    updateScene(i) { it.copy(imageFile = img, imageStatus = "done") }
                }
                if (img == null) {
                    if (generateImageForScene(i)) img = scenes.getOrNull(i)?.imageFile else {
                        updateScene(i) { it.copy(imageError = tr("تعذّر توليد الصورة، سيولَّد الفيديو من النص")) }
                    }
                }
                if (img != null) {
                    MagicHourClient.uploadImage(img, cfg.magicHourKey).fold(
                        onSuccess = { path ->
                            MagicHourClient.submitImageToVideo(prompt, model, dur, path, cfg.magicHourKey, s.aspect)
                        },
                        onFailure = { err ->
                            updateScene(i) { it.copy(imageError = trf("تعذّر رفع الصورة: %s", err.message?.take(120))) }
                            MagicHourClient.submitTextToVideo(prompt, model, dur, cfg.magicHourKey, s.aspect)
                        }
                    )
                } else {
                    MagicHourClient.submitTextToVideo(prompt, model, dur, cfg.magicHourKey, s.aspect)
                }
            } else {
                MagicHourClient.submitTextToVideo(prompt, model, dur, cfg.magicHourKey, s.aspect)
            }
            projectId
                .onSuccess { id ->
                    updateScene(i) { it.copy(jobId = id) }
                    pollScene(i, 15 * 60 * 1000)
                    maybeAlt(i, models, model)
                }
                .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
            return
        }
        val models = selected.toList()
        if (models.isEmpty()) return
        val model = s.modelOverride.ifBlank { models[i % models.size] }
        updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = model, provider = "pollinations", error = "", jobId = "") }
        when (val r = VideoGenClient.submitVideo(prompt, model, dur, cfg.apiKey, ctx.cacheDir, s.aspect)) {
            is VideoGenClient.SubmitResult.Started -> {
                updateScene(i) { it.copy(jobId = r.jobId) }
                pollScene(i, 15 * 60 * 1000)
                maybeAlt(i, models, model)
            }
            is VideoGenClient.SubmitResult.Ready -> {
                updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = r.file) }
                maybeAlt(i, models, model)
            }
            is VideoGenClient.SubmitResult.Failed -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = r.msg.take(160)) }
        }
    }

    /** المرحلة 2: كل مقطع يولّده نموذج فيديو مختلف بالتناوب مع غيره. */
    fun generateAll() {
        if (provider == "agnes") {
            if (cfg.agnesKey.isBlank()) {
                msg = tr("الفيديو يحتاج مفتاح Agnes: أنشئ مفتاحاً مجانياً بلا بطاقة من platform.agnes-ai.com ثم الصقه هنا.")
                return
            }
        } else if (provider == "novai") {
            if (cfg.novaiKey.isBlank()) {
                msg = tr("الفيديو يحتاج مفتاح NovAI: أنشئ مفتاحاً مجانياً بلا بطاقة من aiapi-pro.com ثم الصقه هنا.")
                return
            }
        } else if (provider == "magic") {
            if (cfg.magicHourKey.isBlank()) {
                msg = tr("الفيديو يحتاج مفتاح Magic Hour: أنشئ مفتاحاً مجانياً بلا بطاقة من magichour.ai/developer — رصيده المجاني محدود (نحو 15 مقطعاً قصيراً).")
                return
            }
            if (mhSelected.isEmpty()) {
                msg = tr("اختر نموذج فيديو واحداً على الأقل")
                return
            }
        } else {
            if (cfg.apiKey.isBlank()) {
                msg = tr("الفيديو يحتاج مفتاح Pollinations: أنشئ مفتاحاً مجانياً من enter.pollinations.ai، والاستخدام يُحسب برصيد pollen ويمكن زيادته مجاناً عبر Quests.")
                return
            }
            if (selected.isEmpty()) {
                msg = tr("اختر نموذج فيديو واحداً على الأقل")
                return
            }
        }
        val ready = scenes.mapIndexedNotNull { i, s ->
            if (s.visualPrompt.isNotBlank() && s.status != SceneStatus.READY) i else null
        }
        if (ready.isEmpty()) {
            msg = tr("حوّل النص إلى مشاهد أولاً")
            return
        }
        msg = ""
        busy = true
        scope.launch {
            scenes.forEach { it.videoFile?.delete(); it.imageFile?.delete() }
            scenes = scenes.map { it.copy(imageFile = null, imageStatus = "", imageError = "") }
            ready.forEach { i -> submitScene(i) }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                tr("يشارك أكثر من نموذج: وكلاء المحادثة يكتبون وصف كل مشهد بالتناوب، ونماذج الفيديو المختارة تولّد المقاطع بالتناوب."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    apiKey, { apiKey = it },
                    label = { Text(tr("مفتاح Pollinations")) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedButton(onClick = { pasteKey { apiKey = it } }) { Text(tr("📋 لصق")) }
                Button(onClick = { cfg.apiKey = apiKey; cfg.save(); msg = tr("تم حفظ المفتاح") }) {
                    Text(tr("حفظ"))
                }
            }
            Text(
                tr("الفيديو يحتاج مفتاح Pollinations: أنشئ مفتاحاً مجانياً من enter.pollinations.ai، والاستخدام يُحسب برصيد pollen ويمكن زيادته مجاناً عبر Quests."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    magicKey, { magicKey = it },
                    label = { Text(tr("مفتاح Magic Hour")) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedButton(onClick = { pasteKey { magicKey = it } }) { Text(tr("📋 لصق")) }
                Button(onClick = { cfg.magicHourKey = magicKey; cfg.save(); msg = tr("تم حفظ المفتاح") }) {
                    Text(tr("حفظ"))
                }
            }
            Text(
                tr("مجاني: نحو 15 مقطعاً قصيراً (480p) من رصيد التسجيل، ثم يتوقف حتى تشتري رصيداً. مفتاح مجاني بلا بطاقة من magichour.ai/developer."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    agnesKey, { agnesKey = it },
                    label = { Text(tr("مفتاح Agnes")) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedButton(onClick = { pasteKey { agnesKey = it } }) { Text(tr("📋 لصق")) }
                Button(onClick = { cfg.agnesKey = agnesKey; cfg.save(); msg = tr("تم حفظ المفتاح") }) {
                    Text(tr("حفظ"))
                }
            }
            Text(
                tr("Agnes مجاني بالكامل ($0) — بلا رصيد ينفد، طلب واحد في الدقيقة؛ الصوت غير مؤكد (افترض صامتاً)."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    novaiKey, { novaiKey = it },
                    label = { Text(tr("مفتاح NovAI")) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedButton(onClick = { pasteKey { novaiKey = it } }) { Text(tr("📋 لصق")) }
                Button(onClick = { cfg.novaiKey = novaiKey; cfg.save(); msg = tr("تم حفظ المفتاح") }) {
                    Text(tr("حفظ"))
                }
            }
            Text(
                tr("NovAI: 5 مقاطع مجانية يومياً (6 ثوانٍ، 480/720p) — قد تحمل علامة مائية على الباقة المجانية."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("🔑 جلب المفاتيح المجانية"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("اضغط زر المزوّد فيفتح موقع إنشاء المفتاح في متصفح هاتفك — أنشئ المفتاح المجاني وانسخه، ثم عُد إلى هنا والصقه في خانته بالزر 📋 ثم اضغط حفظ."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { openKeyPage("https://platform.agnes-ai.com") }) { Text(tr("جلب مفتاح Agnes")) }
                        OutlinedButton(onClick = { openKeyPage("https://www.aiapi-pro.com/free/index.html") }) { Text(tr("جلب مفتاح NovAI")) }
                        OutlinedButton(onClick = { openKeyPage("https://enter.pollinations.ai") }) { Text(tr("جلب مفتاح Pollinations")) }
                        OutlinedButton(onClick = { openKeyPage("https://magichour.ai/developer") }) { Text(tr("جلب مفتاح Magic Hour")) }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                longText, { longText = it },
                label = { Text(tr("النص الطويل")) },
                modifier = Modifier.fillMaxWidth().height(160.dp), minLines = 5
            )
            Button(onClick = { writeScenes() }, enabled = !busy && longText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(tr("تحويل النص إلى مشاهد"))
            }
        }
        item {
            Text(tr("مدة كل مقطع (ثوانٍ)"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(4, 5, 8, 10).forEach { d ->
                    FilterChip(selected = durationSec == d, onClick = { durationSec = d }, label = { Text(d.toString()) })
                }
            }
        }
        item {
            Text(tr("مزود الفيديو"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = provider == "agnes", onClick = { provider = "agnes" }, label = { Text("Agnes ($0)") })
                FilterChip(selected = provider == "novai", onClick = { provider = "novai" }, label = { Text("NovAI") })
                FilterChip(selected = provider == "pollinations", onClick = { provider = "pollinations" }, label = { Text("Pollinations") })
                FilterChip(selected = provider == "magic", onClick = { provider = "magic" }, label = { Text("Magic Hour") })
            }
            if (provider == "agnes") {
                Text(
                    tr("Agnes مجاني بالكامل ($0) — بلا رصيد ينفد، طلب واحد في الدقيقة؛ الصوت غير مؤكد (افترض صامتاً). عند امتلاء الطابور يتحول تلقائياً إلى NovAI إن أدخلت مفتاحه."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (provider == "novai") {
                Text(
                    tr("NovAI: 5 مقاطع مجانية يومياً (6 ثوانٍ، 480/720p) — قد تحمل علامة مائية على الباقة المجانية."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            FilterChip(selected = chainMode, onClick = { chainMode = !chainMode }, label = { Text(tr("سلسلة: صورة ثم فيديو")) })
            Text(
                tr("السلسلة: وكيل يكتب الوصف، نموذج صورة يولّد الإطار بلا مفتاح، ونموذج فيديو يحرّكه — في Magic Hour الصورة هي الإطار الأول للمقطع، وفي Pollinations تظهر كمرجع للمشهد."),
                style = MaterialTheme.typography.bodySmall
            )
            // مراجع مثبّتة (10) + قفل الإطار + بدائل (11).
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = lockFrame, onClick = { lockFrame = !lockFrame }, label = { Text(tr("🔒 قفل الإطار")) })
                FilterChip(selected = variants, onClick = { variants = !variants }, label = { Text(tr("بديل ثانٍ لكل لقطة")) })
                OutlinedButton(onClick = { refPicker.launch("image/*") }, enabled = refs.size < 3) {
                    Text(tr("📷 إضافة مرجع مثبّت"))
                }
            }
            if (refs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("مراجع مثبّتة:"), style = MaterialTheme.typography.bodySmall)
                    refs.forEachIndexed { ri, f ->
                        Text(trf("صورة %s", ri + 1), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { refs = refs.filterIndexed { j, _ -> j != ri }; f.delete() }) { Text("✕") }
                    }
                }
                Text(
                    tr("المراجع تُستخدم كإطار أول عند غياب إطار المشهد، فيبقى العنصر أو الشخصية ثابتين بين اللقطات."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (provider == "magic") {
                Text(tr("نماذج Magic Hour المجانية (480p)"), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MagicHourClient.FREE_MODELS.forEach { m ->
                        FilterChip(
                            selected = mhSelected.contains(m),
                            onClick = { mhSelected = if (mhSelected.contains(m)) mhSelected - m else mhSelected + m },
                            label = { Text(m) }
                        )
                    }
                }
            } else {
                Text(tr("نماذج الفيديو (يشارك أكثر من نموذج بالتناوب)"), style = MaterialTheme.typography.bodySmall)
                if (videoModels.isEmpty()) {
                    Text(tr("جارٍ الجلب…"), style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        videoModels.take(30).forEach { m ->
                            FilterChip(
                                selected = selected.contains(m.id),
                                onClick = { selected = if (selected.contains(m.id)) selected - m.id else selected + m.id },
                                label = { Text(if (m.price.isBlank()) m.id else "${m.id} (${m.price})") }
                            )
                        }
                    }
                }
            }
            // تقدير التكلفة قبل التوليد.
            val readyCount = scenes.count { it.visualPrompt.isNotBlank() }
            if (readyCount > 0) {
                Text(
                    when (provider) {
                        "agnes" -> tr("التكلفة: مجاني $0 — بلا رصيد ينفد (طلب واحد في الدقيقة، وقد يمتلئ الطابور)")
                        "novai" -> tr("التكلفة: 5 مقاطع مجانية يومياً من NovAI (6 ثوانٍ) — وقد تحمل علامة مائية.")
                        "magic" -> trf("التكلفة التقديرية: %s رصيد Magic Hour (%s لقطة × %s ث × 24)", VideoPlanner.magicCredits(readyCount, durationSec), readyCount, durationSec)
                        else -> tr("التكلفة: حسب نموذج Pollinations المختار (pollen) — راجع سعر كل نموذج أعلاه.")
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = { generateAll() }, enabled = !busy && scenes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(tr("توليد الفيديو"))
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        }
        val playingFile = playing
        if (playingFile != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        key(playingFile.absolutePath) {
                            AndroidView(
                                factory = { c ->
                                    VideoView(c).apply {
                                        setVideoPath(playingFile.absolutePath)
                                        val mc = MediaController(c)
                                        mc.setAnchorView(this)
                                        setMediaController(mc)
                                        setOnPreparedListener { mp -> mp.start() }
                                        requestFocus()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        }
                        OutlinedButton(onClick = { playing = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("إيقاف"))
                        }
                    }
                }
            }
        }
        // مخطط اللقطات (7): نظرة واحدة على كل لقطة — حركتها ومدتها ونسبتها.
        if (scenes.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(trf("🎬 مخطط اللقطات (%s)", scenes.size), style = MaterialTheme.typography.titleSmall)
                        scenes.forEach { s ->
                            Text(
                                trf("لقطة %s • %s • %s ث • %s", s.index + 1, s.camera, if (s.shotDuration > 0) s.shotDuration else durationSec, s.aspect),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        items(scenes, key = { it.index }) { s ->
            var sendMenu by remember(s.index) { mutableStateOf(false) }
            var sendImageMenu by remember(s.index) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(trf("المشهد %s", s.index + 1), style = MaterialTheme.typography.titleSmall)
                    Text(s.source.take(200), style = MaterialTheme.typography.bodySmall)
                    if (s.visualPrompt.isNotBlank()) {
                        Text(s.visualPrompt, style = MaterialTheme.typography.bodySmall)
                    }
                    // مخطط اللقطة (7+14): حركة كاميرا + نسبة أبعاد + مدة + نموذج.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("🎥 حركة:"), style = MaterialTheme.typography.bodySmall)
                        var camMenu by remember(s.index) { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { camMenu = true }) { Text(s.camera) }
                            DropdownMenu(expanded = camMenu, onDismissRequest = { camMenu = false }) {
                                VideoPlanner.CAMERA_MOVES.forEach { mv ->
                                    DropdownMenuItem(text = { Text(mv) }, onClick = { camMenu = false; updateScene(s.index) { it.copy(camera = mv) } })
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VideoPlanner.ASPECTS.forEach { ar ->
                            FilterChip(selected = s.aspect == ar, onClick = { updateScene(s.index) { it.copy(aspect = ar) } }, label = { Text(ar) })
                        }
                        listOf(4, 5, 8, 10).forEach { d ->
                            FilterChip(
                                selected = (if (s.shotDuration > 0) s.shotDuration else durationSec) == d,
                                onClick = { updateScene(s.index) { it.copy(shotDuration = d) } },
                                label = { Text(trf("%s ث", d)) }
                            )
                        }
                    }
                    // نموذج اللقطة: Agnes وNovAI نموذج واحد ثابت لكل منهما.
                    var modelMenu by remember(s.index) { mutableStateOf(false) }
                    if (provider == "agnes") {
                        Text(tr("النموذج: agnes-video-2.5-flash (720p، 4–12 ث)"), style = MaterialTheme.typography.bodySmall)
                    } else if (provider == "novai") {
                        Text(tr("النموذج: cogvideox-flash (6 ث، 480/720p)"), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Box {
                            OutlinedButton(onClick = { modelMenu = true }) {
                                Text(if (s.modelOverride.isBlank()) tr("نموذج اللقطة: تلقائي") else s.modelOverride)
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                DropdownMenuItem(text = { Text(tr("تلقائي (بالتناوب)")) }, onClick = { modelMenu = false; updateScene(s.index) { it.copy(modelOverride = "") } })
                                (if (provider == "magic") MagicHourClient.FREE_MODELS else videoModels.map { it.id }).forEach { m ->
                                    DropdownMenuItem(text = { Text(m) }, onClick = { modelMenu = false; updateScene(s.index) { it.copy(modelOverride = m) } })
                                }
                            }
                        }
                    }
                    if (s.imageFile != null) {
                        val bmp = remember(s.imageFile?.absolutePath) {
                            s.imageFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        }
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        }
                    }
                    if (s.imageStatus.isNotBlank()) {
                        Text(
                            when (s.imageStatus) {
                                "loading" -> tr("جارٍ توليد صورة الإطار…")
                                "done" -> tr("صورة الإطار جاهزة")
                                else -> tr("تعذّر توليد صورة الإطار")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (s.imageError.isNotBlank()) Text(s.imageError, style = MaterialTheme.typography.bodySmall)
                    val who = listOf(s.agentName, s.videoModel).filter { it.isNotBlank() }.joinToString(" • ")
                    if (who.isNotBlank()) Text(who, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (s.status) {
                            SceneStatus.WRITING -> tr("يكتب الوصف…")
                            SceneStatus.VIDEO -> tr("يولّد الفيديو…")
                            SceneStatus.READY -> tr("جاهز")
                            SceneStatus.FAILED -> tr("فشل")
                            SceneStatus.IDLE -> ""
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (s.error.isNotBlank()) Text(s.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.status == SceneStatus.READY && s.videoFile != null) {
                            OutlinedButton(onClick = { playing = s.videoFile }) { Text(tr("تشغيل")) }
                        }
                        if (s.altVideoFile != null) {
                            OutlinedButton(onClick = { playing = s.altVideoFile }) { Text(trf("▶ تشغيل البديل (%s)", s.altVideoModel)) }
                            OutlinedButton(onClick = {
                                updateScene(s.index) {
                                    it.copy(
                                        videoFile = it.altVideoFile, altVideoFile = it.videoFile,
                                        videoModel = it.altVideoModel, altVideoModel = it.videoModel
                                    )
                                }
                            }) { Text(tr("✓ الاحتفاظ بالبديل")) }
                        }
                        if (s.visualPrompt.isNotBlank()) {
                            // إرسال إلى موقع فيديو: ينسخ وصف المشهد إلى الحافظة
                            // ويفتح تبويب الموقع، فيلصق المستخدم الوصف بضغطة
                            // واحدة — هذا هو الربط المجاني الممكن (لا API مجاني
                            // ولا معامل رابط في أي موقع من السبعة).
                            Box {
                                OutlinedButton(onClick = { sendMenu = true }) { Text(tr("إرسال إلى موقع")) }
                                DropdownMenu(expanded = sendMenu, onDismissRequest = { sendMenu = false }) {
                                    videoSites.forEach { site ->
                                        DropdownMenuItem(text = { Text(site.name) }, onClick = {
                                            sendMenu = false
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("video prompt", s.visualPrompt))
                                            vm.pendingSiteId = site.id
                                            Toast.makeText(ctx, tr("تم نسخ الوصف — الصقه في صندوق الوصف في الموقع"), Toast.LENGTH_LONG).show()
                                            onOpenSite()
                                        })
                                    }
                                }
                            }
                            // إرسال الصورة أيضاً: تُحفظ صورة الإطار في التنزيلات
                            // (ليراها منتقي ملفات المتصفح) ويُنسخ الوصف، ويُفتح
                            // تبويب الموقع — في الموقع يرفع المستخدم الصورة من
                            // التنزيلات ثم يلصق الوصف.
                            if (s.imageFile != null) {
                                Box {
                                    OutlinedButton(onClick = { sendImageMenu = true }) { Text(tr("📷 إرسال الصورة إلى موقع")) }
                                    DropdownMenu(expanded = sendImageMenu, onDismissRequest = { sendImageMenu = false }) {
                                        videoSites.forEach { site ->
                                            DropdownMenuItem(text = { Text(site.name) }, onClick = {
                                                sendImageMenu = false
                                                val img = s.imageFile ?: return@DropdownMenuItem
                                                scope.launch {
                                                    val name = "muse-scene-" + (s.index + 1) + ".jpg"
                                                    val saved = withContext(Dispatchers.IO) { saveImageToDownloads(ctx, img, name) }
                                                    if (!saved) {
                                                        Toast.makeText(ctx, tr("تعذّر حفظ الصورة"), Toast.LENGTH_LONG).show()
                                                        return@launch
                                                    }
                                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cm.setPrimaryClip(ClipData.newPlainText("video prompt", s.visualPrompt))
                                                    vm.pendingSiteId = site.id
                                                    Toast.makeText(ctx, tr("حُفظت الصورة في التنزيلات ونُسخ الوصف — في الموقع اضغط زر الصورة واخترها من التنزيلات ثم الصق الوصف"), Toast.LENGTH_LONG).show()
                                                    onOpenSite()
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        if (s.visualPrompt.isNotBlank() && s.imageFile == null && s.imageStatus != "loading") {
                            OutlinedButton(onClick = { scope.launch { generateImageForScene(s.index) } }, enabled = !busy) {
                                Text(tr("توليد صورة الإطار"))
                            }
                        }
                        if (s.visualPrompt.isNotBlank()) {
                            OutlinedButton(onClick = { enhanceScene(s.index) }, enabled = !busy) { Text(tr("✨ تحسين الوصف")) }
                            if (s.originalPrompt.isNotBlank()) {
                                OutlinedButton(onClick = { updateScene(s.index) { it.copy(visualPrompt = it.originalPrompt, originalPrompt = "") } }) {
                                    Text(tr("↩ استرجاع الأصل"))
                                }
                            }
                            // تمديد (14): إطار اللقطة السابقة أول إطار لهذه اللقطة.
                            if (s.index > 0 && scenes.getOrNull(s.index - 1)?.imageFile != null) {
                                OutlinedButton(onClick = {
                                    val prev = scenes[s.index - 1].imageFile
                                    updateScene(s.index) { it.copy(imageFile = prev, imageStatus = "done") }
                                }) { Text(tr("➕ تمديد من اللقطة السابقة")) }
                            }
                        }
                        if (s.status == SceneStatus.FAILED) {
                            OutlinedButton(onClick = { scope.launch { submitScene(s.index) } }, enabled = !busy) {
                                Text(tr("إعادة التوليد"))
                            }
                        }
                    }
                }
            }
        }
    }
}
