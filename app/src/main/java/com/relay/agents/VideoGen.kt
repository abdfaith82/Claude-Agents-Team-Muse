package com.relay.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * توليد الفيديو من نص طويل عبر Pollinations — ويشارك فيه أكثر من نموذج:
 * وكلاء المحادثة المفعّلون يكتبون وصف كل مشهد بالتناوب، ونماذج الفيديو
 * المختارة تولّد المقاطع بالتناوب.
 *
 * كيف يعمل برمجياً:
 * - قائمة نماذج الفيديو عامة بلا مفتاح: GET /video/models.
 * - التوليد نفسه يحتاج مفتاح Pollinations (sk_) برصيد pollen:
 *   GET /video/{prompt}?model=…&duration=…&aspectRatio=16:9
 *   مع Authorization: Bearer وترويسة Prefer: respond-async، فيرد
 *   202 مع معرّف مهمة، ثم تُستعلم الحالة GET /v1/jobs/{id} حتى
 *   succeeded، ثم يُنزَّل المقطع GET /v1/jobs/{id}/result (MP4).
 *   النتيجة المخزّنة قد ترد 200 مباشرة بلا معرّف مهمة، فيُنزَّل
 *   جسم الرد نفسه كملف. سجلات المهام تنتهي بعد 24 ساعة من اكتمالها.
 *
 * حدود صريحة (تحقّق حيّ في 2026-11-21):
 * - الفيديو في Pollinations يحتاج مفتاحاً (401 بلا مفتاح)؛
 *   المجهول فقط هو واجهة النص البسيطة. المفتاح نفسه مجاني من
 *   enter.pollinations.ai، والاستخدام يُحسب برصيد pollen، ويمكن
 *   زيادة الرصيد مجاناً عبر Quests في الحساب (بلا رقم ثابت أُثبت هنا).
 * - الصور: النقطة القديمة image.pollinations.ai ما زالت ترد صوراً
 *   بلا مفتاح (تحقّق حيّ في 2026-11-24)، أما النقطة الموثقة
 *   gen.pollinations.ai/image فتحتاج المفتاح؛ عميل الصور يجرّب
 *   القديمة أولاً ثم الموثقة بالمفتاح إن رُفض الطلب (401).
 * - كل مقطع بضع ثوانٍ حسب النموذج (بعض النماذج لها مدة ثابتة،
 *   فيرفض الخادم مدة غير مدعومة وتظهر رسالة الخطأ في المشهد).
 * - المقاطع تُحفظ في cacheDir بهواتف المستخدم فقط ولا تُرفع لأي مكان.
 */
object VideoGenClient {
    const val BASE = "https://gen.pollinations.ai"
    const val DEFAULT_MODEL = "bytedance/seedance-2.5-flash"

    data class VideoModel(val id: String, val price: String)

    /** قائمة نماذج الفيديو العامة — بلا مفتاح. */
    suspend fun listVideoModels(): Result<List<VideoModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$BASE/video/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("$code: ${raw.take(200)}")
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val p = m.optJSONObject("pricing")
                val price = p?.let {
                    it.keys().asSequence()
                        .filter { k -> k != "currency" }
                        .joinToString(", ") { k -> "$k ${it.optString(k)}" }
                        .ifBlank { "pollen" }
                } ?: ""
                VideoModel(m.getString("name"), price)
            }.sortedBy { it.id }
        }
    }

    sealed class SubmitResult {
        data class Started(val jobId: String) : SubmitResult()
        data class Ready(val file: File) : SubmitResult()
        data class Failed(val msg: String) : SubmitResult()
    }

    /** يرسل توليد مقطع واحد غير متزامن، أو ينزّل النتيجة المخزّنة مباشرة إن ردّ 200. */
    suspend fun submitVideo(
        prompt: String,
        model: String,
        durationSec: Int,
        key: String,
        cacheDir: File,
        aspectRatio: String = "16:9"
    ): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val p = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val m = URLEncoder.encode(model, "UTF-8").replace("+", "%20")
            val ar = URLEncoder.encode(aspectRatio, "UTF-8")
            val url = "$BASE/video/$p?model=$m&duration=$durationSec&aspectRatio=$ar"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 180_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Prefer", "respond-async")
            }
            val code = conn.responseCode
            if (code == 202) {
                val raw = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
                if (id.isBlank()) SubmitResult.Failed("202 بلا معرّف مهمة") else SubmitResult.Started(id)
            } else if (code in 200..299) {
                val file = File(cacheDir, "videogen-${UUID.randomUUID()}.mp4")
                conn.inputStream?.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
                SubmitResult.Ready(file)
            } else {
                val raw = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                SubmitResult.Failed("$code: ${raw.take(200).ifBlank { conn.responseMessage ?: "" }}")
            }
        } catch (e: Exception) {
            SubmitResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** حالة مهمة: pending / succeeded / failed / cancelled (أو فارغ عند تعذّر القراءة). */
    suspend fun jobStatus(jobId: String, key: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$BASE/v1/jobs/$jobId").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $key")
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("$code: ${raw.take(200)}")
            val j = JSONObject(raw)
            j.optString("status").ifBlank { j.optJSONObject("job")?.optString("status") ?: "" }
        }
    }

    /** ينزّل مقطع مهمة مكتملة إلى target (يعيد 409 ما دامت قيد الانتظار أو فاشلة). */
    suspend fun downloadResult(jobId: String, key: String, target: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$BASE/v1/jobs/$jobId/result").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val raw = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    error("$code: ${raw.take(200)}")
                }
                conn.inputStream?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                target
            }
        }
}

/** توليد صورة إطار المشهد عبر Pollinations — بلا مفتاح في العادة:
 * النقطة القديمة image.pollinations.ai/prompt/{prompt}?model=flux
 * ترد صورة بلا مفتاح (تحقّق حيّ 2026-11-24)؛ إن ردّت 401 يُعاد
 * الطلب على النقطة الموثقة gen.pollinations.ai/image بالمفتاح
 * المحفوظ إن وُجد. الصورة 16:9 لتكون إطاراً أول للمقطع. */
object PollinationsImage {
    const val LEGACY_BASE = "https://image.pollinations.ai"
    const val BASE = "https://gen.pollinations.ai"

    suspend fun generateImage(prompt: String, cacheDir: File, key: String, width: Int = 768, height: Int = 432): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
                val seed = (0..999999).random()
                val file = File(cacheDir, "sceneimg-${UUID.randomUUID()}.jpg")
                fun download(url: String, auth: String?): Int {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 120_000
                        auth?.let { setRequestProperty("Authorization", "Bearer $it") }
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        conn.inputStream?.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
                    }
                    return code
                }
                val code = download("$LEGACY_BASE/prompt/$p?model=flux&width=$width&height=$height&seed=$seed", null)
                if (code == 401 && key.isNotBlank()) {
                    val code2 = download("$BASE/image/$p?model=flux&width=$width&height=$height&seed=$seed", key)
                    if (code2 !in 200..299) error("$code2")
                } else if (code !in 200..299) {
                    error("$code")
                }
                if (!file.exists() || file.length() < 1024) error("صورة فارغة")
                file
            }
        }
}

/** Magic Hour — مزوّد فيديو ثانٍ بمفتاح مجاني بلا بطاقة:
 * POST https://api.magichour.ai/v1/text-to-video (Bearer، جسم JSON فيه
 * model وstyle.prompt وend_seconds وaspect_ratio وresolution)، فيرد
 * id وcredits_charged، ثم GET /v1/video-projects/{id} حتى complete
 * (أو error/canceled)، ثم تنزيل downloads[0].url — رابط موقّع يُنزَّل
 * بلا ترويسة Authorization، وينتهي خلال ~24 ساعة.
 * الرصيد المجاني محدود (400 عند التسجيل + 100 يومياً حتى 7 مرات،
 * أي نحو 15 مقطعاً قصيراً 480p إجمالاً) — يُعرض ذلك في الواجهة.
 * نماذج الباقة المجانية: wan-2.2 و ltx-2.5 و minimax-h3 (480p).
 * السلسلة (صورة ← فيديو): تُرفع صورة الإطار أولاً عبر
 * POST /v1/files/upload-urls ثم PUT على الرابط الموقّع، ثم
 * POST /v1/image-to-video {end_seconds, model, resolution,
 * style.prompt, assets.image_file_path} — تحقّق حيّ من
 * openapi.json الرسمي في 2026-11-24. */
object MagicHourClient {
    const val BASE = "https://api.magichour.ai"
    val FREE_MODELS = listOf("wan-2.2", "ltx-2.5", "minimax-h3")

    /** يرسل طلب إنشاء مشروع فيديو (نص أو صورة) ويعيد معرّف المشروع. */
    private suspend fun postProject(path: String, body: JSONObject, key: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 402) error("انتهى الرصيد المجاني (402)")
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                val id = JSONObject(raw).optString("id")
                if (id.isBlank()) error("رد بلا معرّف مشروع") else id
            }
        }

    /** يرسل توليد مقطع من نص ويعيد معرّف المشروع (أو خطأ، ومنه 402 عند نفاد الرصيد). */
    suspend fun submitTextToVideo(prompt: String, model: String, durationSec: Int, key: String, aspectRatio: String = "16:9"): Result<String> {
        val body = JSONObject()
            .put("model", model)
            .put("style", JSONObject().put("prompt", prompt))
            .put("end_seconds", durationSec)
            .put("aspect_ratio", aspectRatio)
            .put("resolution", "480p")
        return postProject("/v1/text-to-video", body, key)
    }

    /** يرفع صورة محلية ويعيد مسارها (file_path) لاستخدامه في image-to-video:
     * POST /v1/files/upload-urls {items:[{type:"image", extension:"jpg"}]}
     * ثم PUT لبايتات الملف على upload_url بلا ترويسة Authorization. */
    suspend fun uploadImage(file: File, key: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = file.extension.ifBlank { "jpg" }.lowercase()
            val body = JSONObject().put(
                "items",
                JSONArray().put(JSONObject().put("type", "image").put("extension", ext))
            )
            val conn = (URL("$BASE/v1/files/upload-urls").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 402) error("انتهى الرصيد المجاني (402)")
            if (code !in 200..299) error("$code: ${raw.take(200)}")
            val item = JSONObject(raw).optJSONArray("items")?.optJSONObject(0)
                ?: error("رد بلا رابط رفع")
            val uploadUrl = item.optString("upload_url")
            val filePath = item.optString("file_path")
            if (uploadUrl.isBlank() || filePath.isBlank()) error("رد رفع ناقص")
            val put = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "image/jpeg")
                doOutput = true
            }
            put.outputStream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            val putCode = put.responseCode
            if (putCode !in 200..299) error("رفع الصورة: $putCode")
            filePath
        }
    }

    /** صورة ← فيديو: POST /v1/image-to-video {end_seconds, model,
     * resolution:"480p", style:{prompt}, assets:{image_file_path}} —
     * يعيد معرّف المشروع. */
    suspend fun submitImageToVideo(
        prompt: String,
        model: String,
        durationSec: Int,
        imageFilePath: String,
        key: String,
        aspectRatio: String = "16:9"
    ): Result<String> {
        val body = JSONObject()
            .put("name", "Scene image-to-video")
            .put("end_seconds", durationSec)
            .put("model", model)
            .put("resolution", "480p")
            .put("style", JSONObject().put("prompt", prompt))
            .put("aspect_ratio", aspectRatio)
            .put("assets", JSONObject().put("image_file_path", imageFilePath))
        return postProject("/v1/image-to-video", body, key)
    }

    /** حالة مشروع: (الحالة، رابط التنزيل عند الاكتمال أو null).
     * الحالات: draft / queued / rendering / complete / error / canceled. */
    suspend fun projectStatus(projectId: String, key: String): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$BASE/v1/video-projects/$projectId").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                val j = JSONObject(raw)
                val status = j.optString("status")
                val url = j.optJSONArray("downloads")?.let { arr ->
                    if (arr.length() > 0) arr.getJSONObject(0).optString("url") else null
                }
                status to url?.ifBlank { null }
            }
        }

    /** ينزّل رابطاً موقّعاً (بلا ترويسة Authorization) إلى target. */
    suspend fun downloadUrl(url: String, target: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 180_000
            }
            val code = conn.responseCode
            if (code !in 200..299) error("$code")
            conn.inputStream?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            target
        }
    }
}

/** يفسّر حالة مهمة فيديو من رد JSON بأشكال مختلفة دفاعياً:
 * الحالة من status/state/video_status، والرابط من
 * video_url/url/download_url/result_url (أو data المتداخلة).
 * يعيد ("completed"/"failed"/"processing"، رابط أو null). */
private fun parseVideoStatus(raw: String): Pair<String, String?> {
    fun pick(j: JSONObject): Pair<String, String?> {
        val st = j.optString("status").ifBlank { j.optString("state") }.ifBlank { j.optString("video_status") }
        var url = j.optString("video_url").ifBlank { j.optString("url") }
            .ifBlank { j.optString("download_url") }.ifBlank { j.optString("result_url") }
            .ifBlank { j.optString("videoUrl") }
        if (url.isBlank()) {
            val arr = j.optJSONArray("videos")
            if (arr != null && arr.length() > 0) url = arr.getJSONObject(0).optString("url")
        }
        return st to url.ifBlank { null }
    }
    return runCatching {
        val j = JSONObject(raw)
        var (st, url) = pick(j)
        if (st.isBlank() && url == null) {
            val d = j.optJSONObject("data")
            if (d != null) {
                val p = pick(d)
                st = p.first
                url = p.second
            }
        }
        val norm = when (st.lowercase()) {
            "completed", "complete", "success", "succeeded", "finished", "done" -> "completed"
            "failed", "fail", "error", "cancelled", "canceled" -> "failed"
            else -> "processing"
        }
        if (url != null && norm == "processing" && st.isBlank()) "completed" to url else norm to url
    }.getOrDefault("processing" to null)
}

/** Agnes AI — أفضل مزوّد فيديو مجاني موثّق (بحث عميق 2026-11-27):
 * النموذج agnes-video-2.5-flash بسعر $0/ثانية ضمن عرض ترويجي
 * محدود (السعر الأصلي $0.025/ث)، 720p، 4–12 ث، نص→فيديو.
 * بلا رصيد ينفد: الحد هو طلب واحد في الدقيقة، والطابور المجاني
 * قد يمتلئ (429). المفتاح مجاني بلا بطاقة من platform.agnes-ai.com.
 * الصورة→فيديو تحتاج رابط صورة عاماً — غير مدعومة هنا.
 * التوليد نفسه لم يُختبر من الطرف للطرف — شكل الرد يُفسَّر دفاعياً. */
object AgnesClient {
    const val BASE = "https://apihub.agnes-ai.com"
    const val MODEL = "agnes-video-2.5-flash"

    /** يرسل توليد مقطع ويعيد معرّف الفيديو. */
    suspend fun submitVideo(key: String, prompt: String, seconds: Int, aspectRatio: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("model", MODEL)
                    .put("prompt", prompt)
                    .put("seconds", seconds.coerceIn(4, 12).toString())
                    .put("mode", "text")
                    .put("size", "720P")
                    .put("aspect_ratio", aspectRatio)
                val conn = (URL("$BASE/v1/videos").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429) error("429: طابور Agnes ممتلئ — جرّب بعد دقيقة")
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                val j = JSONObject(raw)
                val id = j.optString("video_id").ifBlank { j.optString("id") }
                    .ifBlank { j.optString("task_id") }
                    .ifBlank { j.optJSONObject("data")?.optString("video_id") ?: "" }
                    .ifBlank { j.optJSONObject("data")?.optString("id") ?: "" }
                if (id.isBlank()) error("رد بلا معرّف فيديو") else id
            }
        }

    /** حالة مهمة: (الحالة، رابط التنزيل عند الاكتمال أو null). */
    suspend fun pollStatus(key: String, videoId: String): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val v = URLEncoder.encode(videoId, "UTF-8")
                val m = URLEncoder.encode(MODEL, "UTF-8")
                val conn = (URL("$BASE/agnesapi?video_id=$v&model_name=$m").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                parseVideoStatus(raw)
            }
        }
}

/** NovAI — وسيط يوصل نموذج CogVideoX-Flash مجاناً:
 * 5 توليدات فيديو مجانية يومياً (ضمن 10 مكالمات نماذج مجانية)،
 * 6 ثوانٍ، 480/720p، مفتاح nvai- بتسجيل بريد بلا بطاقة من
 * aiapi-pro.com، والحد يُصفَّر 00:00 بتوقيت UTC+8. مخرجات الباقة
 * المجانية قد تحمل علامة NovAI المائية. جودة أقل من Agnes —
 * بديل احتياطي عند امتلاء طابورها. شكل الاستعلام عن المهمة
 * (GET /v1/video/generations/{id}) غير موثّق رسمياً بالكامل —
 * يُفسَّر الرد دفاعياً، وإن فشل الاستعلام تظهر رسالته في المشهد. */
object NovAiClient {
    const val BASE = "https://aiapi-pro.com"
    const val MODEL = "cogvideox-flash"

    /** يرسل توليد مقطع ويعيد معرّف المهمة. */
    suspend fun submitVideo(key: String, prompt: String, aspectRatio: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("model", MODEL)
                    .put("prompt", prompt)
                    .put("seconds", 6)
                    .put("size", "720p")
                    .put("aspect_ratio", aspectRatio)
                val conn = (URL("$BASE/v1/video/generations").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 429) error("429: وصلت لحد NovAI اليومي (5 مقاطع)")
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                val j = JSONObject(raw)
                val id = j.optString("id").ifBlank { j.optString("task_id") }
                    .ifBlank { j.optString("video_id") }
                    .ifBlank { j.optJSONObject("data")?.optString("id") ?: "" }
                if (id.isBlank()) error("رد بلا معرّف مهمة") else id
            }
        }

    /** حالة مهمة: (الحالة، رابط التنزيل عند الاكتمال أو null). */
    suspend fun taskStatus(key: String, taskId: String): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val t = URLEncoder.encode(taskId, "UTF-8")
                val conn = (URL("$BASE/v1/video/generations/$t").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("$code: ${raw.take(200)}")
                parseVideoStatus(raw)
            }
        }
}

/** مفتاح Pollinations للفيديو: يُحفظ مشفّراً بـ Keystore عبر SecretStore. */
class VideoGenConfig(private val st: Storage) {
    var apiKey by mutableStateOf(SecretStore.decrypt(st.setting("videogen_key", "")))
    var magicHourKey by mutableStateOf(SecretStore.decrypt(st.setting("magichour_key", "")))
    var agnesKey by mutableStateOf(SecretStore.decrypt(st.setting("agnes_key", "")))
    var novaiKey by mutableStateOf(SecretStore.decrypt(st.setting("novai_key", "")))

    fun save() {
        st.saveSetting("videogen_key", SecretStore.encrypt(apiKey.trim()))
        st.saveSetting("magichour_key", SecretStore.encrypt(magicHourKey.trim()))
        st.saveSetting("agnes_key", SecretStore.encrypt(agnesKey.trim()))
        st.saveSetting("novai_key", SecretStore.encrypt(novaiKey.trim()))
    }
}
