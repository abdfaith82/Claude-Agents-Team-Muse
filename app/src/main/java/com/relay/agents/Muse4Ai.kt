package com.relay.agents

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Muse4AI — جسر محلي على كمبيوتر Windows يتيح توليد الصور والفيديو
 * والمحادثة عبر جلسة Muse الخاصة بالمستخدم (مشروع مستقل، MIT،
 * github.com/andrew-babo/muse4ai — غير تابع لـ Meta).
 *
 * كيف يعمل برمجياً: تطبيق Windows يخدم REST على المنفذ 47821
 * (http://127.0.0.1:47821 على الكمبيوتر نفسه). هذا العميل يتصل به
 * من الهاتف عبر نفس شبكة Wi-Fi بعنوان IP الكمبيوتر، مثلاً
 * http://192.168.1.5:47821 — أما 127.0.0.1 من الهاتف فتعني الهاتف
 * نفسه لا الكمبيوتر، ومن محاكي أندرويد تعني المضيف عبر 10.0.2.2.
 * المصادقة بمفتاح API يبدأ بـ msk_ يُرسل Bearer، ويُحفظ مشفّراً
 * بـ Keystore عبر SecretStore مثل بقية المفاتيح في هذا التطبيق.
 *
 * حدود صريحة (من README وSKILL الخاصين بالمشروع):
 * - يحتاج كمبيوتر Windows يشغّل Muse4AI وحساب Muse مسجّلاً فيه؛
 *   بلا كمبيوتر لا يعمل هذا التكامل إطلاقاً.
 * - المهام غير متزامنة: تُنشأ المهمة ثم تُستعلم حالتها حتى
 *   succeeded / failed / cancelled / needs_review. الصور نحو
 *   30–90 ثانية، والفيديو عدة دقائق، والفيديو نحو 8–10 ثوانٍ
 *   بدقة 1280×720. روابط التنزيل صالحة 60 دقيقة.
 * - كل توليد يستهلك حد استخدام Muse في ذلك الحساب.
 * - المشروع غير رسمي: أتمتة جلسة ويب قد تتعطل عند تغيّر الموقع
 *   وقد لا تناسب شروط استخدام Meta — اقرأها قبل الاعتماد عليه.
 * - عقد API الكامل عند الجسر نفسه: GET /v1/openapi.json.
 *   هذا العميل يقرأ الردود بتسامح (حقول id/job_id وstatus و
 *   download_url/url في مواضع مختلفة) لأن الحقول الدقيقة قد
 *   تتغير بين إصدارات الجسر؛ وإن ردّ الجسر 404 على مسار،
 *   تُعرض رسالة الخطأ كما هي بدل تخمين نتيجة.
 */
object Muse4AiClient {
    const val DEFAULT_PORT = 47821
    const val LAN_EXAMPLE = "http://192.168.1.5:47821"
    const val EMULATOR_BASE = "http://10.0.2.2:47821"

    fun normalizeBase(raw: String): String =
        raw.trim().trimEnd('/').ifEmpty { LAN_EXAMPLE }

    private suspend fun request(
        base: String,
        apiKey: String,
        method: String,
        path: String,
        body: JSONObject? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeBase(base) + path
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("$code: ${raw.take(300).ifBlank { conn.responseMessage ?: "" }}")
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject().put("raw", raw))
        }
    }

    /** فحص الاتصال: /v1/health ثم /v1/status (الأنواع المفعّلة والحسابات الجاهزة). */
    suspend fun check(base: String, apiKey: String): Result<String> {
        val health = request(base, apiKey, "GET", "/v1/health")
        if (health.isFailure) return Result.failure(health.exceptionOrNull() ?: Exception("health failed"))
        val st = request(base, apiKey, "GET", "/v1/status")
        return st.fold(
            onSuccess = { j -> Result.success(summarizeStatus(j)) },
            onFailure = { Result.success("الجسر يرد (health OK) — تعذّر قراءة /v1/status: ${it.message}") }
        )
    }

    private fun summarizeStatus(j: JSONObject): String {
        val parts = mutableListOf<String>()
        // حقول محتملة حسب إصدار الجسر؛ نعرض ما وُجد فقط.
        j.optJSONObject("capabilities")?.let { parts += "القدرات: ${it.keys().asSequence().toList()}" }
        j.optJSONArray("accounts")?.let { parts += "حسابات جاهزة: ${it.length()}" }
        j.optString("status").takeIf { it.isNotBlank() }?.let { parts += "الحالة: $it" }
        return if (parts.isEmpty()) "الجسر يرد — ${j.toString().take(300)}" else parts.joinToString("\n")
    }

    /** إنشاء مهمة: kind = image | video | chat. يعيد JSON المهمة كما أرسله الجسر. */
    suspend fun createJob(
        base: String,
        apiKey: String,
        kind: String,
        prompt: String,
        conversationId: String = ""
    ): Result<JSONObject> {
        val body = JSONObject()
            .put("kind", kind)
            .put("prompt", prompt)
            .put("request_id", "muse4ai-${UUID.randomUUID()}")
        if (conversationId.isNotBlank()) body.put("conversation_id", conversationId)
        return request(base, apiKey, "POST", "/v1/jobs", body)
    }

    suspend fun getJob(base: String, apiKey: String, id: String): Result<JSONObject> =
        request(base, apiKey, "GET", "/v1/jobs/$id")

    suspend fun listJobs(base: String, apiKey: String): Result<List<JSONObject>> {
        val r = request(base, apiKey, "GET", "/v1/jobs")
        return r.map { j ->
            val arr = j.optJSONArray("jobs") ?: j.optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
        }
    }

    suspend fun cancelJob(base: String, apiKey: String, id: String): Result<JSONObject> =
        request(base, apiKey, "POST", "/v1/jobs/$id/cancel", JSONObject())

    /**
     * تمديد فيديو منتهٍ: الجسر يعرّف مسارين في README —
     * POST /v1/conversations/{id}/extend و POST /v1/assets/{id}/extend.
     * نجرّب المحادثة أولاً (التمديد في نفس المحادثة)، وإن ردّ 404
     * نجرّب الأصل (asset).
     */
    suspend fun extendVideo(base: String, apiKey: String, id: String): Result<JSONObject> {
        val first = request(base, apiKey, "POST", "/v1/conversations/$id/extend", JSONObject())
        if (first.isSuccess) return first
        return request(base, apiKey, "POST", "/v1/assets/$id/extend", JSONObject())
    }

    // ---- قراءة متسامحة لردود المهام ----

    fun jobId(j: JSONObject): String =
        j.optString("id").ifBlank { j.optString("job_id") }
            .ifBlank { j.optJSONObject("job")?.optString("id") ?: "" }

    fun jobStatus(j: JSONObject): String =
        j.optString("status").ifBlank { j.optJSONObject("job")?.optString("status") ?: "" }

    fun jobKind(j: JSONObject): String =
        j.optString("kind").ifBlank { j.optJSONObject("job")?.optString("kind") ?: "" }

    fun jobPrompt(j: JSONObject): String =
        j.optString("prompt").ifBlank { j.optJSONObject("job")?.optString("prompt") ?: "" }

    /** نص رد المحادثة إن وُجد (حقول محتملة حسب الإصدار). */
    fun jobText(j: JSONObject): String {
        val inner = j.optJSONObject("job") ?: j
        return inner.optString("text")
            .ifBlank { inner.optString("answer") }
            .ifBlank { inner.optJSONObject("result")?.optString("text") ?: "" }
            .ifBlank { inner.optJSONObject("result")?.optString("answer") ?: "" }
    }

    fun jobConversationId(j: JSONObject): String {
        val inner = j.optJSONObject("job") ?: j
        return inner.optString("conversation_id")
            .ifBlank { inner.optJSONObject("result")?.optString("conversation_id") ?: "" }
    }

    /** روابط تنزيل المخرجات: نبحث في outputs/assets/files/result عن download_url أو url. */
    fun jobDownloads(j: JSONObject): List<String> {
        val out = mutableListOf<String>()
        fun scan(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val dl = o.optString("download_url").ifBlank { o.optString("url") }
                    if (dl.startsWith("http")) out += dl
                    o.keys().forEach { scan(o.opt(it)) }
                }
                is JSONArray -> (0 until o.length()).forEach { scan(o.opt(it)) }
            }
        }
        scan(j.optJSONObject("job") ?: j)
        return out.distinct()
    }
}

/** إعدادات الجسر: العنوان محفوظ عادياً، والمفتاح مشفّر بـ Keystore. */
class Muse4AiConfig(private val st: Storage) {
    var baseUrl by mutableStateOf(
        st.setting("muse4ai_base", Muse4AiClient.LAN_EXAMPLE)
    )
    var apiKey by mutableStateOf(
        SecretStore.decrypt(st.setting("muse4ai_key", ""))
    )

    fun save() {
        st.saveSetting("muse4ai_base", Muse4AiClient.normalizeBase(baseUrl))
        st.saveSetting("muse4ai_key", SecretStore.encrypt(apiKey.trim()))
    }
}
