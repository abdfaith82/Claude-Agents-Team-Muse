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
 * - الفيديو والصور في Pollinations يحتاجان مفتاحاً (401 بلا مفتاح)؛
 *   المجهول فقط هو واجهة النص البسيطة. المفتاح نفسه مجاني من
 *   enter.pollinations.ai، والاستخدام يُحسب برصيد pollen، ويمكن
 *   زيادة الرصيد مجاناً عبر Quests في الحساب (بلا رقم ثابت أُثبت هنا).
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
        cacheDir: File
    ): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val p = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val m = URLEncoder.encode(model, "UTF-8").replace("+", "%20")
            val url = "$BASE/video/$p?model=$m&duration=$durationSec&aspectRatio=16:9"
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

/** Magic Hour — مزوّد فيديو ثانٍ بمفتاح مجاني بلا بطاقة:
 * POST https://api.magichour.ai/v1/text-to-video (Bearer، جسم JSON فيه
 * model وstyle.prompt وend_seconds وaspect_ratio وresolution)، فيرد
 * id وcredits_charged، ثم GET /v1/video-projects/{id} حتى complete
 * (أو error/canceled)، ثم تنزيل downloads[0].url — رابط موقّع يُنزَّل
 * بلا ترويسة Authorization، وينتهي خلال ~24 ساعة.
 * الرصيد المجاني محدود (400 عند التسجيل + 100 يومياً حتى 7 مرات،
 * أي نحو 15 مقطعاً قصيراً 480p إجمالاً) — يُعرض ذلك في الواجهة.
 * نماذج الباقة المجانية: wan-2.2 و ltx-2.5 و minimax-h3 (480p). */
object MagicHourClient {
    const val BASE = "https://api.magichour.ai"
    val FREE_MODELS = listOf("wan-2.2", "ltx-2.5", "minimax-h3")

    /** يرسل توليد مقطع ويعيد معرّف المشروع (أو خطأ، ومنه 402 عند نفاد الرصيد). */
    suspend fun submitTextToVideo(prompt: String, model: String, durationSec: Int, key: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("model", model)
                    .put("style", JSONObject().put("prompt", prompt))
                    .put("end_seconds", durationSec)
                    .put("aspect_ratio", "16:9")
                    .put("resolution", "480p")
                val conn = (URL("$BASE/v1/text-to-video").openConnection() as HttpURLConnection).apply {
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

/** مفتاح Pollinations للفيديو: يُحفظ مشفّراً بـ Keystore عبر SecretStore. */
class VideoGenConfig(private val st: Storage) {
    var apiKey by mutableStateOf(SecretStore.decrypt(st.setting("videogen_key", "")))
    var magicHourKey by mutableStateOf(SecretStore.decrypt(st.setting("magichour_key", "")))

    fun save() {
        st.saveSetting("videogen_key", SecretStore.encrypt(apiKey.trim()))
        st.saveSetting("magichour_key", SecretStore.encrypt(magicHourKey.trim()))
    }
}
