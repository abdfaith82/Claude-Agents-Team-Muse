package com.relay.agents

/**
 * مخطط الفيديو الهندسي (v28): حركات كاميرا ونسب أبعاد لكل لقطة،
 * محسّن الوصف بصيغتين (صيغة Flow الخماسية للنص→فيديو، وحركة
 * وكاميرا فقط للصورة→فيديو)، وتقدير تكلفة Magic Hour.
 */
object VideoPlanner {
    /** حركات الكاميرا المتاحة لكل لقطة (تُضمَّن في وصف المشهد). */
    val CAMERA_MOVES = listOf(
        "ثابت", "بان يمين", "بان يسار", "تتبع", "دولي", "زوم للداخل", "زوم للخارج", "لقطة جوية"
    )

    val ASPECTS = listOf("16:9", "9:16", "1:1")

    /** أبعاد صورة الإطار لكل نسبة (لقطة 16:9 أفقية، 9:16 عمودية، 1:1 مربعة). */
    fun aspectSize(aspect: String): Pair<Int, Int> = when (aspect) {
        "9:16" -> 432 to 768
        "1:1" -> 512 to 512
        else -> 768 to 432
    }

    /** يحوّل اسم حركة الكاميرا العربية إلى عبارة إنجليزية تُلحق بوصف المشهد. */
    fun cameraPhrase(move: String): String = when (move) {
        "بان يمين" -> "slow pan right"
        "بان يسار" -> "slow pan left"
        "تتبع" -> "smooth tracking shot following the subject"
        "دولي" -> "slow dolly-in"
        "زوم للداخل" -> "slow zoom in"
        "زوم للخارج" -> "slow zoom out"
        "لقطة جوية" -> "aerial drone shot, slowly descending"
        else -> "static camera"
    }

    /** محسّن الوصف — نص→فيديو: صيغة Flow الخماسية
     * [تصوير]+[موضوع]+[حركة]+[سياق]+[أسلوب وأجواء]. */
    const val ENHANCE_T2V_SYSTEM =
        "You are a video prompt engineer. Rewrite the user's scene description into the 5-part formula: " +
            "[Cinematography] + [Subject] + [Action] + [Context] + [Style & Ambiance]. " +
            "Reply with ONLY the improved prompt, 1-3 sentences, in English. Do not add explanations."

    /** محسّن الوصف — صورة→فيديو: الوصف يصف الحركة والكاميرا فقط،
     * ولا يعيد وصف ما في الصورة أصلاً (إرشاد Runway/Kling). */
    const val ENHANCE_I2V_SYSTEM =
        "You are a video prompt engineer. The user gives an image description for image-to-video. " +
            "Rewrite it so it describes ONLY motion and camera movement — never re-describe what is already in the image. " +
            "Reply with ONLY the improved prompt, 1-2 sentences, in English. Do not add explanations."

    /** رصيد Magic Hour لكل ثانية (480p): مقطع 5 ثوانٍ = 120 رصيداً (تحقّق 2026-11-24). */
    const val MAGIC_CREDITS_PER_SEC = 24

    fun magicCredits(scenes: Int, seconds: Int): Int = scenes * seconds * MAGIC_CREDITS_PER_SEC
}
