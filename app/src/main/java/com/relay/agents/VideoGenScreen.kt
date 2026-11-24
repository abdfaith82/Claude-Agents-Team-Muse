package com.relay.agents

import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class SceneStatus { IDLE, WRITING, VIDEO, READY, FAILED }

private data class SceneState(
    val index: Int,
    val source: String,
    val agentName: String = "",
    val visualPrompt: String = "",
    val videoModel: String = "",
    val provider: String = "pollinations",
    val jobId: String = "",
    val videoFile: File? = null,
    val imageFile: File? = null,
    val imageStatus: String = "",
    val imageError: String = "",
    val status: SceneStatus = SceneStatus.IDLE,
    val error: String = ""
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
fun VideoGenTab(vm: RelayViewModel) {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    val cfg = remember { VideoGenConfig(storage) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var magicKey by remember { mutableStateOf(cfg.magicHourKey) }
    var provider by remember { mutableStateOf("pollinations") }
    var chainMode by remember { mutableStateOf(true) }
    var mhSelected by remember { mutableStateOf(setOf("ltx-2.5")) }
    var longText by remember { mutableStateOf("") }
    var durationSec by remember { mutableStateOf(5) }
    var videoModels by remember { mutableStateOf<List<VideoGenClient.VideoModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scenes by remember { mutableStateOf<List<SceneState>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

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

    /** السلسلة — المرحلة الوسطى: يولّد نموذج الصورة إطار المشهد
     * من الوصف نفسه (Pollinations بلا مفتاح في العادة). */
    suspend fun generateImageForScene(i: Int): Boolean {
        val s = scenes.getOrNull(i) ?: return false
        if (s.visualPrompt.isBlank()) return false
        updateScene(i) { it.copy(imageStatus = "loading", imageError = "") }
        return PollinationsImage.generateImage(s.visualPrompt, ctx.cacheDir, cfg.apiKey)
            .onSuccess { file -> updateScene(i) { it.copy(imageFile = file, imageStatus = "done") } }
            .onFailure { err ->
                updateScene(i) { it.copy(imageStatus = "failed", imageError = err.message?.take(120) ?: "") }
            }.isSuccess
    }

    /** يرسل مشهداً واحداً ثم يستعلم حالته (يُستخدم للتوليد الجماعي ولإعادة المحاولة). */
    suspend fun submitScene(i: Int) {
        val s = scenes.getOrNull(i) ?: return
        if (s.visualPrompt.isBlank()) return
        if (provider == "magic") {
            val models = mhSelected.toList()
            if (models.isEmpty()) return
            val model = models[i % models.size]
            updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = model, provider = "magic", error = "", jobId = "") }
            // السلسلة: الصورة إطار أول للمقطع؛ إن تعذّر توليدها أو
            // رفعها، يولَّد الفيديو من النص مباشرة ولا يتعطل المشهد.
            val projectId: Result<String> = if (chainMode) {
                var img = scenes.getOrNull(i)?.imageFile
                if (img == null) {
                    if (generateImageForScene(i)) img = scenes.getOrNull(i)?.imageFile else {
                        updateScene(i) { it.copy(imageError = tr("تعذّر توليد الصورة، سيولَّد الفيديو من النص")) }
                    }
                }
                if (img != null) {
                    MagicHourClient.uploadImage(img, cfg.magicHourKey).fold(
                        onSuccess = { path ->
                            MagicHourClient.submitImageToVideo(s.visualPrompt, model, durationSec, path, cfg.magicHourKey)
                        },
                        onFailure = { err ->
                            updateScene(i) { it.copy(imageError = trf("تعذّر رفع الصورة: %s", err.message?.take(120))) }
                            MagicHourClient.submitTextToVideo(s.visualPrompt, model, durationSec, cfg.magicHourKey)
                        }
                    )
                } else {
                    MagicHourClient.submitTextToVideo(s.visualPrompt, model, durationSec, cfg.magicHourKey)
                }
            } else {
                MagicHourClient.submitTextToVideo(s.visualPrompt, model, durationSec, cfg.magicHourKey)
            }
            projectId
                .onSuccess { id ->
                    updateScene(i) { it.copy(jobId = id) }
                    pollScene(i, 15 * 60 * 1000)
                }
                .onFailure { err -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = err.message?.take(160) ?: "") } }
            return
        }
        val models = selected.toList()
        if (models.isEmpty()) return
        val model = models[i % models.size]
        updateScene(i) { it.copy(status = SceneStatus.VIDEO, videoModel = model, provider = "pollinations", error = "", jobId = "") }
        when (val r = VideoGenClient.submitVideo(s.visualPrompt, model, durationSec, cfg.apiKey, ctx.cacheDir)) {
            is VideoGenClient.SubmitResult.Started -> {
                updateScene(i) { it.copy(jobId = r.jobId) }
                pollScene(i, 15 * 60 * 1000)
            }
            is VideoGenClient.SubmitResult.Ready -> updateScene(i) { it.copy(status = SceneStatus.READY, videoFile = r.file) }
            is VideoGenClient.SubmitResult.Failed -> updateScene(i) { it.copy(status = SceneStatus.FAILED, error = r.msg.take(160)) }
        }
    }

    /** المرحلة 2: كل مقطع يولّده نموذج فيديو مختلف بالتناوب مع غيره. */
    fun generateAll() {
        if (provider == "magic") {
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = provider == "pollinations", onClick = { provider = "pollinations" }, label = { Text("Pollinations") })
                FilterChip(selected = provider == "magic", onClick = { provider = "magic" }, label = { Text("Magic Hour") })
            }
            FilterChip(selected = chainMode, onClick = { chainMode = !chainMode }, label = { Text(tr("سلسلة: صورة ثم فيديو")) })
            Text(
                tr("السلسلة: وكيل يكتب الوصف، نموذج صورة يولّد الإطار بلا مفتاح، ونموذج فيديو يحرّكه — في Magic Hour الصورة هي الإطار الأول للمقطع، وفي Pollinations تظهر كمرجع للمشهد."),
                style = MaterialTheme.typography.bodySmall
            )
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
        items(scenes, key = { it.index }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(trf("المشهد %s", s.index + 1), style = MaterialTheme.typography.titleSmall)
                    Text(s.source.take(200), style = MaterialTheme.typography.bodySmall)
                    if (s.visualPrompt.isNotBlank()) {
                        Text(s.visualPrompt, style = MaterialTheme.typography.bodySmall)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.status == SceneStatus.READY && s.videoFile != null) {
                            OutlinedButton(onClick = { playing = s.videoFile }) { Text(tr("تشغيل")) }
                        }
                        if (s.visualPrompt.isNotBlank() && s.imageFile == null && s.imageStatus != "loading") {
                            OutlinedButton(onClick = { scope.launch { generateImageForScene(s.index) } }, enabled = !busy) {
                                Text(tr("توليد صورة الإطار"))
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
