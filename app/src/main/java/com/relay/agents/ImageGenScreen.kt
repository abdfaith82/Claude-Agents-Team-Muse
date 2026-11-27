package com.relay.agents

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private data class GenImage(
    val file: File,
    val prompt: String,
    val model: String,
    val seed: Int,
    val fromCamera: Boolean = false
)

/** تعليمات كتابة وصف الصورة بالإنجليزية — نص توجيه، لا واجهة. */
private const val IMAGE_PROMPT_SYSTEM =
    "Turn the following description into ONE vivid image-generation prompt in English (1-2 sentences): " +
        "describe the subject, setting, lighting, style and mood in detail. " +
        "Reply with only the prompt, no explanation."

/** أبعاد الصورة لكل نسبة (سقف Pollinations: مساحة 768×768 كحد أقصى). */
private fun imageAspectSize(aspect: String): Pair<Int, Int> = when (aspect) {
    "16:9" -> 768 to 432
    "9:16" -> 432 to 768
    "4:3" -> 768 to 576
    "3:4" -> 576 to 768
    else -> 768 to 768
}

/** شاشة توليد الصور (24): نماذج Pollinations المجانية (flux وturbo
 * بلا مفتاح في العادة، والباقي بمفتاح Pollinations المحفوظ في شاشة
 * الفيديو) + زر كاميرا احترافي: إذن وقت التشغيل + FileProvider
 * + حفظ في cacheDir، والصورة تصلح مرجعاً مثبّتاً في شاشة الفيديو
 * (إطار أول للمقطع) أو تُحفظ في التنزيلات. الصور لا تُرفع لأي مكان. */
@Composable
fun ImageGenTab(vm: RelayViewModel, onOpenVideo: () -> Unit = {}) {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    val cfg = remember { VideoGenConfig(storage) }
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("flux") }
    var aspect by remember { mutableStateOf("1:1") }
    var images by remember { mutableStateOf<List<GenImage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<File?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    // الكاميرا: TakePicture أولاً (يستخدمه launchCamera)، ثم طلب الإذن.
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        pendingPhoto = null
        if (ok && f != null && f.exists() && f.length() > 1024) {
            images = listOf(GenImage(f, "", "", 0, fromCamera = true)) + images
            msg = tr("التُقطت الصورة — أرسلها إلى الفيديو أو احفظها في التنزيلات")
        } else if (ok) {
            msg = tr("تعذّر حفظ الصورة")
        }
    }

    /** يجهّز ملفاً في cacheDir ويفتح الكاميرا عليه عبر FileProvider. */
    fun launchCamera() {
        val file = File(ctx.cacheDir, "camera-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingPhoto = file
        takePicture.launch(uri)
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(ctx, tr("الكاميرا تحتاج إذناً — اسمح بالوصول من إعدادات التطبيق"), Toast.LENGTH_LONG).show()
    }

    /** يفتح الكاميرا إن كان الإذن ممنوحاً، وإلا يطلبه أولاً. */
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = runCatching {
                val target = File(ctx.cacheDir, "picked-${UUID.randomUUID()}.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                target
            }.getOrNull()
            if (file != null && file.length() > 1024) {
                images = listOf(GenImage(file, "", "", 0, fromCamera = true)) + images
                msg = tr("أُضيفت الصورة إلى المعرض")
            } else Toast.makeText(ctx, tr("تعذّر قراءة الصورة"), Toast.LENGTH_SHORT).show()
        }
    }

    /** يولّد صورة من الوصف بالنموذج والنسبة المختارين. */
    fun generate() {
        if (prompt.isBlank()) {
            msg = tr("اكتب وصف الصورة أولاً")
            return
        }
        val (w, h) = imageAspectSize(aspect)
        val seed = (0..999999).random()
        val useModel = model
        val usePrompt = prompt.trim()
        busy = true
        msg = ""
        scope.launch {
            PollinationsImage.generateImage(usePrompt, ctx.cacheDir, cfg.apiKey, w, h, useModel, seed)
                .onSuccess { file ->
                    images = listOf(GenImage(file, usePrompt, useModel, seed)) + images
                    msg = tr("تم توليد الصورة")
                }
                .onFailure { err ->
                    msg = trf("تعذّر توليد الصورة: %s", err.message?.take(140) ?: "")
                }
            busy = false
        }
    }

    /** وكيل يعيد كتابة الوصف بالإنجليزية بصيغة مناسبة لنماذج الصور. */
    fun enhancePrompt() {
        if (prompt.isBlank()) {
            msg = tr("اكتب وصف الصورة أولاً")
            return
        }
        val agents = vm.agents.filter { it.enabled && !it.provider.manual }
        if (agents.isEmpty()) {
            msg = tr("أضف وكيلاً مفعّلاً أولاً لتحسين الوصف")
            return
        }
        scope.launch {
            busy = true
            when (val r = ClaudeClient.send(agents.first(), IMAGE_PROMPT_SYSTEM, prompt, maxTokens = 512)) {
                is ApiResult.Ok -> {
                    prompt = r.text.trim().take(600)
                    msg = tr("تم تحسين الوصف")
                }
                is ApiResult.Fail -> msg = trf("تعذّر التحسين: %s", r.message.take(120))
            }
            busy = false
        }
    }

    /** يرسل الصورة إلى شاشة الفيديو كمرجع مثبّت (إطار أول للمقطع). */
    fun sendToVideo(img: GenImage) {
        vm.pendingImagePath = img.file.absolutePath
        Toast.makeText(ctx, tr("ستُستخدم الصورة كمرجع مثبّت في شاشة الفيديو"), Toast.LENGTH_LONG).show()
        onOpenVideo()
    }

    /** يحفظ الصورة في مجلد التنزيلات العام. */
    fun saveImage(img: GenImage) {
        val name = "muse-image-${System.currentTimeMillis()}.jpg"
        val ok = saveImageToDownloads(ctx, img.file, name)
        Toast.makeText(ctx, if (ok) tr("حُفظت الصورة في التنزيلات") else tr("تعذّر حفظ الصورة"), Toast.LENGTH_LONG).show()
    }

    /** يحذف الصورة من المعرض ومن cacheDir. */
    fun deleteImage(img: GenImage) {
        img.file.delete()
        images = images - img
        if (preview?.absolutePath == img.file.absolutePath) preview = null
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                tr("ولّد صوراً مجانية بنماذج Pollinations، أو التقط صورة بالكاميرا واستخدمها مرجعاً في شاشة الفيديو."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            OutlinedTextField(
                prompt, { prompt = it },
                label = { Text(tr("وصف الصورة (بالعربية أو الإنجليزية)")) },
                modifier = Modifier.fillMaxWidth().height(110.dp), minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { enhancePrompt() }, enabled = !busy && prompt.isNotBlank()) {
                    Text(tr("✨ تحسين الوصف بالإنجليزية"))
                }
                Button(onClick = { generate() }, enabled = !busy && prompt.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(tr("🖼 توليد الصورة"))
                }
            }
        }
        item {
            Text(tr("نموذج الصورة"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PollinationsImage.FREE_MODELS.forEach { (id, desc) ->
                    FilterChip(selected = model == id, onClick = { model = id }, label = { Text(desc) })
                }
            }
            if (model != "flux" && model != "turbo" && cfg.apiKey.isBlank()) {
                Text(
                    tr("هذا النموذج قد يحتاج مفتاح Pollinations — أدخله من شاشة الفيديو (زر 🔑 جلب المفاتيح) أو اختر flux أو turbo."),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Text(tr("نسبة الأبعاد"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1:1", "16:9", "9:16", "4:3", "3:4").forEach { ar ->
                    FilterChip(selected = aspect == ar, onClick = { aspect = ar }, label = { Text(ar) })
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("📷 الكاميرا"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("التقط صورة بهاتفك واحفظها أو أرسلها إلى شاشة الفيديو كمرجع مثبّت (إطار أول للمقطع). الصورة تُحفظ داخل التطبيق فقط ولا تُرفع لأي مكان."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openCamera() }) { Text(tr("📷 فتح الكاميرا")) }
                        OutlinedButton(onClick = { pickImage.launch("image/*") }) { Text(tr("🖼 اختيار من المعرض")) }
                    }
                }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        }
        val previewFile = preview
        if (previewFile != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val bmp = remember(previewFile.absolutePath) {
                            BitmapFactory.decodeFile(previewFile.absolutePath)
                        }
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(320.dp)
                            )
                        }
                        OutlinedButton(onClick = { preview = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("إغلاق"))
                        }
                    }
                }
            }
        }
        if (images.isEmpty()) {
            item {
                Text(
                    tr("لا صور بعد — ولّد أول صورة أو التقط واحدة بالكاميرا"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(images, key = { it.file.absolutePath }) { img ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val bmp = remember(img.file.absolutePath) {
                        BitmapFactory.decodeFile(img.file.absolutePath)
                    }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(220.dp).clickable { preview = img.file }
                        )
                    }
                    if (img.prompt.isNotBlank()) Text(img.prompt, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (img.fromCamera) tr("صورة من الكاميرا")
                        else trf("النموذج: %s • البذرة: %s", img.model, img.seed),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sendToVideo(img) }) { Text(tr("🎬 إلى الفيديو")) }
                        OutlinedButton(onClick = { saveImage(img) }) { Text(tr("💾 حفظ")) }
                        OutlinedButton(onClick = { deleteImage(img) }) { Text(tr("🗑 حذف")) }
                    }
                }
            }
        }
    }
}
