package com.relay.agents

/**
 * إعداد نموذج محلي مجاني — يعمل على جهاز كمبيوتر المستخدم نفسه، بلا مفتاح
 * وبلا فاتورة API، وبلا إرسال للمحادثة إلى أي شركة بعد تنزيل النموذج.
 *
 * كيف يعمل برمجياً: Ollama وLM Studio يقدّمان نقطة نهاية متوافقة مع OpenAI
 * على الشبكة المحلية (‎/v1/chat/completions)، فيستخدمها عميل ClaudeClient
 * الموجود أصلاً كما يستخدم أي مزوّد OpenAI — لا كود خاص بالنموذج نفسه.
 * الهاتف لا يشغّل النموذج داخله (يحتاج ذاكرة ومعالج كمبيوتر)؛ بل يتصل
 * بجهاز الكمبيوتر على نفس شبكة Wi-Fi، أو بالمضيف عبر 10.0.2.2 في المحاكي.
 */
data class LocalPreset(
    val id: String,
    val provider: Provider,
    val name: String,
    /** عنوان الخادم المحلي؛ بدّل 192.168.1.5 بـ IP جهاز الكمبيوتر فعلاً. */
    val baseUrl: String,
    val model: String,
    val pullCommand: String,
    val steps: String
)

object LocalModels {
    const val OLLAMA_LAN = "http://192.168.1.5:11434/v1"
    const val OLLAMA_EMULATOR = "http://10.0.2.2:11434/v1"
    const val LMSTUDIO_LAN = "http://192.168.1.5:1234/v1"
    const val LMSTUDIO_EMULATOR = "http://10.0.2.2:1234/v1"

    /** نماذج Ollama شائعة وخفيفة نسبياً؛ جرّب واحداً أولاً ثم غيّر إن أردت. */
    val ollamaModels = listOf("llama3.1:8b", "qwen2.5:7b", "mistral:7b", "gemma3:4b", "deepseek-r1:8b")

    val presets = listOf(
        LocalPreset(
            "ollama-lan", Provider.OLLAMA, "Ollama — كمبيوتري (Wi-Fi)",
            OLLAMA_LAN, "llama3.1:8b",
            "ollama pull llama3.1:8b",
            "1) على الكمبيوتر: ثبّت Ollama من ollama.com ثم نفّذ: ollama pull llama3.1:8b\n" +
                "2) اسمح بالوصول من الشبكة: OLLAMA_HOST=0.0.0.0:11434 ثم ollama serve (أو أعد التشغيل)\n" +
                "3) اعرف IP الكمبيوتر (ipconfig / ifconfig) وبدّل 192.168.1.5 به في حقل الخادم أدناه\n" +
                "4) تأكد أن الهاتف والكمبيوتر على نفس Wi-Fi، ثم اضغط «جلب النماذج» للتأكد"
        ),
        LocalPreset(
            "ollama-emu", Provider.OLLAMA, "Ollama — محاكي أندرويد",
            OLLAMA_EMULATOR, "llama3.1:8b",
            "ollama pull llama3.1:8b",
            "للمحاكي فقط: 10.0.2.2 تعني «كمبيوتر المضيف» من داخل المحاكي.\n" +
                "1) على الكمبيوتر: ollama pull llama3.1:8b ثم ollama serve\n" +
                "2) لا تغيّر العنوان 10.0.2.2 — هو الصحيح للمحاكي"
        ),
        LocalPreset(
            "lmstudio-lan", Provider.LMSTUDIO, "LM Studio — كمبيوتري (Wi-Fi)",
            LMSTUDIO_LAN, "local-model",
            "",
            "1) على الكمبيوتر: ثبّت LM Studio، حمّل نموذجاً من تبويب Discover\n" +
                "2) في تبويب Developer: حمّل النموذج (Load Model) ثم شغّل الخادم (Start Server) على المنفذ 1234\n" +
                "3) انسخ اسم النموذج كما يظهر في LM Studio وضعه في حقل «النموذج» أدناه\n" +
                "4) بدّل 192.168.1.5 بـ IP الكمبيوتر، وتأكد أن الهاتف على نفس Wi-Fi"
        ),
        LocalPreset(
            "lmstudio-emu", Provider.LMSTUDIO, "LM Studio — محاكي أندرويد",
            LMSTUDIO_EMULATOR, "local-model",
            "",
            "للمحاكي فقط: شغّل خادم LM Studio على الكمبيوتر (المنفذ 1234) وحمّل نموذجاً،\n" +
                "واستخدم العنوان 10.0.2.2 كما هو، مع اسم النموذج كما يظهر في LM Studio."
        )
    )

    fun presetsFor(provider: Provider): List<LocalPreset> =
        presets.filter { it.provider == provider }

    fun defaultBaseUrl(provider: Provider): String = when (provider) {
        Provider.OLLAMA -> OLLAMA_LAN
        Provider.LMSTUDIO -> LMSTUDIO_LAN
        else -> ""
    }
}
