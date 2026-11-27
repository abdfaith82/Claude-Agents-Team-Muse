package com.relay.agents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * ترقيات الفريق الهندسية (v28): مكتبة أوامر بمتغيرات وسلاسل، شخصيات
 * محفوظة، جدول أسعار وتتبع تكلفة حقيقية مع سقف إنفاق يومي، قاعدة
 * معرفة من ملفات المستخدم (استرجاع بالكلمات المفتاحية)، وتشغيل
 * مجدول للمهام. كل مخزن ملف JSON محلي؛ لا شيء يغادر الهاتف.
 */

// ---------- 5) مكتبة الأوامر ----------
data class PromptTemplate(val id: String, val name: String, val text: String) {
    /** متغيرات {{اسم}} بالترتيب. */
    fun vars(): List<String> =
        Regex("\\{\\{([^}]+)\\}\\}").findAll(text).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinct().toList()

    fun fill(values: Map<String, String>): String {
        var out = text
        values.forEach { (k, v) -> out = out.replace("{{${k}}}", v) }
        return out
    }

    fun toJson() = JSONObject().put("id", id).put("name", name).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = PromptTemplate(o.getString("id"), o.getString("name"), o.getString("text"))
    }
}

class PromptLibrary(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "prompt_library.json")

    /** قوالب جاهزة مدمجة (تظهر دائماً فوق قوالب المستخدم). */
    val builtIns: List<PromptTemplate> = listOf(
        PromptTemplate("bi-explain", "شرح مبسّط",
            "اشرح {{الموضوع}} بأسلوب مبسط مع مثال عملي، في {{عدد الأسطر}} أسطر تقريباً."),
        PromptTemplate("bi-review", "مراجعة كود",
            "راجع الكود التالي بدقة واكتب: الأخطاء، الثغرات الأمنية، والحالات الحدّية المفقودة، مع إصلاح مقترح لكل نقطة:\n{{الكود}}"),
        PromptTemplate("bi-plan", "خطة مشروع",
            "ضع خطة تنفيذ هندسية لمشروع {{المشروع}} في {{عدد الخطوات}} خطوات متسلسلة، مع قائمة الملفات والمكتبات لكل خطوة."),
        PromptTemplate("bi-translate", "ترجمة احترافية",
            "ترجم النص التالي إلى {{اللغة}} بأسلوب احترافي طبيعي، مع الحفاظ على المصطلحات التقنية:\n{{النص}}"),
        PromptTemplate("bi-summary", "تلخيص دقيق",
            "لخّص النص التالي في {{عدد النقاط}} نقاط رئيسية، ثم اذكر أهم نقطة خلافية أو غير مؤكدة إن وجدت:\n{{النص}}")
    )

    fun load(): List<PromptTemplate> = runCatching {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { PromptTemplate.fromJson(arr.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())

    fun save(list: List<PromptTemplate>) {
        runCatching {
            val tmp = File(ctx.filesDir, "prompt_library.json.tmp")
            tmp.writeText(JSONArray(list.take(100).map { it.toJson() }).toString())
            tmp.renameTo(file)
        }
    }

    fun add(name: String, text: String): PromptTemplate {
        val t = PromptTemplate(UUID.randomUUID().toString(), name.trim().take(60), text.trim().take(4000))
        save(load() + t)
        return t
    }

    fun remove(id: String) = save(load().filterNot { it.id == id })
}

// ---------- 9) الشخصيات المحفوظة ----------
data class Persona(val id: String, val name: String, val system: String, val model: String, val provider: Provider) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("system", system)
        .put("model", model).put("provider", provider.name)

    companion object {
        fun fromJson(o: JSONObject) = Persona(
            o.getString("id"), o.getString("name"), o.getString("system"),
            o.optString("model", Provider.GEMINI.defaultModel),
            runCatching { Provider.valueOf(o.optString("provider", "GEMINI")) }.getOrDefault(Provider.GEMINI)
        )
    }
}

object PersonaPresets {
    /** شخصيات جاهزة: اسم + تعليمات نظام + نموذج. تطبيقها يضبط
     * تعليمات المحادثة ويختار الوكيل المطابق للنموذج إن وُجد. */
    val BUILT_IN: List<Persona> = listOf(
        Persona("p-reviewer", "مراجع دقيق",
            "You are a strict technical reviewer. Check every claim, number and step for errors, unverified statements and missing edge cases. List problems numbered with a fix each. Be concise.",
            "gemini-flash-latest", Provider.GEMINI),
        Persona("p-coder", "مبرمج خبير",
            "You are a principal software engineer. Write complete, runnable code with no shortcuts and no placeholder comments. For every file use ```file:path then the code then ```. Explain briefly outside file blocks.",
            "claude-sonnet-5-5", Provider.CLAUDE),
        Persona("p-writer", "كاتب محتوى",
            "You are a professional Arabic copywriter. Write clear, vivid, well-structured text with a strong opening and a concrete ending. Avoid filler and clichés.",
            "gpt-4o", Provider.OPENAI),
        Persona("p-researcher", "باحث",
            "You are a careful researcher. Answer with evidence: state what is known, what is uncertain, and what would confirm it. Separate facts from opinions and never invent a source.",
            "gemini-flash-latest", Provider.GEMINI),
        Persona("p-teacher", "معلّم مبسّط",
            "You are a patient teacher. Explain step by step with one concrete example per idea, check understanding with a short question, and never skip the basics.",
            "llama-3.3-70b-versatile", Provider.GROQ)
    )
}

class PersonaStore(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "personas.json")

    fun load(): List<Persona> = runCatching {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { Persona.fromJson(arr.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())

    fun save(list: List<Persona>) {
        runCatching {
            val tmp = File(ctx.filesDir, "personas.json.tmp")
            tmp.writeText(JSONArray(list.take(50).map { it.toJson() }).toString())
            tmp.renameTo(file)
        }
    }

    fun add(name: String, system: String, model: String, provider: Provider): Persona {
        val p = Persona(UUID.randomUUID().toString(), name.trim().take(40), system.trim().take(4000),
            model.trim().take(120), provider)
        save(load() + p)
        return p
    }

    fun remove(id: String) = save(load().filterNot { it.id == id })
}

// ---------- 4) جدول الأسعار والتكلفة والسقف ----------
object CostTable {
    /** سعر الدولار لكل مليون توكن (دخل/خرج) للنماذج المعروفة فقط.
     * النماذج غير المدرجة تعرض التوكنز بلا سعر (لا نخترع سعراً). */
    fun priceFor(model: String): Pair<Double, Double>? {
        val m = model.lowercase()
        return when {
            m.startsWith("claude-sonnet-5") -> 3.0 to 15.0
            m.startsWith("claude-opus") -> 15.0 to 75.0
            m.startsWith("claude-haiku") -> 0.8 to 4.0
            m.startsWith("gpt-4o-mini") -> 0.15 to 0.6
            m.startsWith("gpt-4o") -> 2.5 to 10.0
            m.startsWith("gpt-5") -> 1.25 to 10.0
            m.contains("gemini-2.5-flash") || m.contains("gemini-flash") -> 0.3 to 2.5
            m.contains("gemini-2.5-pro") -> 1.25 to 10.0
            m.contains("llama-3.3-70b") -> 0.0 to 0.0
            m.contains(":free") -> 0.0 to 0.0
            m == "openai-fast" -> 0.0 to 0.0
            else -> null
        }
    }

    /** تكلفة الطلب بالدولار، أو null إن كان السعر غير معروف. */
    fun costUsd(model: String, inTok: Long, outTok: Long): Double? {
        val (pi, po) = priceFor(model) ?: return null
        return (inTok * pi + outTok * po) / 1_000_000.0
    }
}

/** سقف الإنفاق اليومي: عند بلوغه تُستخدم المزوّدات المجانية فقط
 * (free/noKey/manual/local) ويُرفض اختيار المدفوع بهدوء مع سطر في السجل. */
object BudgetGate {
    /** 0 = بلا سقف. */
    var capUsdPerDay: Double = 0.0

    fun spentTodayUsd(): Double {
        val dayStart = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L
        return Telemetry.stats(dayStart).sumOf { it.costUsd }
    }

    fun capReached(): Boolean = capUsdPerDay > 0 && spentTodayUsd() >= capUsdPerDay

    fun allows(a: AgentConfig): Boolean {
        if (!capReached()) return true
        return a.provider.free || a.provider.noKey || a.provider.manual || a.provider.custom
    }
}

// ---------- 13) قاعدة المعرفة من ملفات المستخدم ----------
data class KnowledgeDoc(val id: String, val name: String, val text: String) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = KnowledgeDoc(o.getString("id"), o.getString("name"), o.getString("text"))
    }
}

class KnowledgeStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "knowledge").also { it.mkdirs() }

    fun addDoc(name: String, text: String): KnowledgeDoc? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val d = KnowledgeDoc(UUID.randomUUID().toString(), name.trim().take(80), clean.take(MAX_DOC_CHARS))
        runCatching { File(dir, d.id + ".json").writeText(d.toJson().toString()) }
        return d
    }

    fun list(): List<KnowledgeDoc> = runCatching {
        dir.listFiles()?.mapNotNull {
            runCatching { KnowledgeDoc.fromJson(JSONObject(it.readText())) }.getOrNull()
        }?.sortedBy { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    fun removeDoc(id: String) {
        runCatching { File(dir, "$id.json").delete() }
    }

    /** يقطّع المستندات إلى فقرات (≤700 حرف) ويعيد أفضل 3 مقاطع
     * بتداخل الكلمات المفتاحية مع السؤال — استرجاع محلي بلا تضمينات. */
    fun retrieve(question: String, top: Int = 3): List<Pair<KnowledgeDoc, String>> {
        val words = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return emptyList()
        val scored = ArrayList<Triple<Int, KnowledgeDoc, String>>()
        list().forEach { doc ->
            doc.text.split(Regex("\\n\\s*\\n")).flatMap { para ->
                if (para.length <= 700) listOf(para.trim()) else para.chunked(700)
            }.filter { it.isNotBlank() }.forEach { chunk ->
                val low = chunk.lowercase()
                val score = words.count { low.contains(it) }
                if (score > 0) scored.add(Triple(score, doc, chunk.trim()))
            }
        }
        return scored.sortedByDescending { it.first }.take(top).map { it.second to it.third }
    }

    fun contextFor(question: String): String {
        val hits = retrieve(question)
        if (hits.isEmpty()) return ""
        return buildString {
            append("--- Knowledge from the user's own files (use when relevant, quote the file name) ---\n")
            hits.forEach { (doc, chunk) ->
                append("[${doc.name}]\n").append(chunk.take(700)).append("\n\n")
            }
        }
    }

    companion object {
        const val MAX_DOC_CHARS = 200_000
    }
}

// ---------- 6) فروع المحادثة ----------
data class ChatBranch(val id: String, val name: String, val createdAt: Long, val messages: List<ChatMsg>) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("createdAt", createdAt)
        .put("messages", JSONArray(messages.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): ChatBranch {
            val arr = o.optJSONArray("messages") ?: JSONArray()
            return ChatBranch(
                o.getString("id"), o.getString("name"), o.optLong("createdAt"),
                (0 until arr.length()).map { ChatMsg.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}

class BranchStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "branches").also { it.mkdirs() }

    fun save(name: String, messages: List<ChatMsg>): ChatBranch {
        val b = ChatBranch(UUID.randomUUID().toString(), name.trim().take(80), System.currentTimeMillis(), messages.takeLast(100))
        runCatching { File(dir, b.id + ".json").writeText(b.toJson().toString()) }
        return b
    }

    fun list(): List<ChatBranch> = runCatching {
        dir.listFiles()?.mapNotNull {
            runCatching { ChatBranch.fromJson(JSONObject(it.readText())) }.getOrNull()
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }.getOrDefault(emptyList())

    fun load(id: String): ChatBranch? = runCatching {
        ChatBranch.fromJson(JSONObject(File(dir, "$id.json").readText()))
    }.getOrNull()

    fun remove(id: String) {
        runCatching { File(dir, "$id.json").delete() }
    }
}

// ---------- 15) التشغيل المجدول ----------
data class ScheduledRun(
    val id: String,
    val goal: String,
    val delayMin: Int,
    val repeatDaily: Boolean,
    val coding: Boolean,
    val thinking: Boolean,
    val createdAt: Long,
    val status: String = "pending", // pending | running | done | failed
    val resultProjectId: String = ""
) {
    fun toJson() = JSONObject().put("id", id).put("goal", goal).put("delayMin", delayMin)
        .put("repeatDaily", repeatDaily).put("coding", coding).put("thinking", thinking)
        .put("createdAt", createdAt).put("status", status).put("resultProjectId", resultProjectId)

    companion object {
        fun fromJson(o: JSONObject) = ScheduledRun(
            o.getString("id"), o.getString("goal"), o.optInt("delayMin", 60),
            o.optBoolean("repeatDaily", false), o.optBoolean("coding", false),
            o.optBoolean("thinking", false), o.optLong("createdAt"),
            o.optString("status", "pending"), o.optString("resultProjectId", "")
        )
    }
}

class ScheduleStore(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "scheduled_runs.json")

    fun load(): List<ScheduledRun> = runCatching {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { ScheduledRun.fromJson(arr.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())

    fun save(list: List<ScheduledRun>) {
        runCatching {
            val tmp = File(ctx.filesDir, "scheduled_runs.json.tmp")
            tmp.writeText(JSONArray(list.take(20).map { it.toJson() }).toString())
            tmp.renameTo(file)
        }
    }

    fun add(goal: String, delayMin: Int, repeatDaily: Boolean, coding: Boolean, thinking: Boolean): ScheduledRun {
        val r = ScheduledRun(UUID.randomUUID().toString(), goal.trim().take(500), delayMin.coerceIn(15, 60 * 24 * 7),
            repeatDaily, coding, thinking, System.currentTimeMillis())
        save(load() + r)
        return r
    }

    fun update(id: String, change: (ScheduledRun) -> ScheduledRun) =
        save(load().map { if (it.id == id) change(it) else it })

    fun remove(id: String) = save(load().filterNot { it.id == id })
}
