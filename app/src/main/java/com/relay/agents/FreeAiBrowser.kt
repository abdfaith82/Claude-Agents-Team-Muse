package com.relay.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * المتصفح المجاني: كل زر يفتح ذكاءً اصطناعياً مجانياً في تبويب Gecko جديد
 * ببيئة (contextId) منفصلة تماماً — كوكيز وتسجيل دخول وتخزين مستقل عن باقي
 * التبويبات. المستخدم يسجّل دخوله بنفسه في كل موقع ويكتب بنفسه؛ التطبيق
 * لا يرسل شيئاً تلقائياً داخل هذه المواقع ولا يقرأ إجاباتها، ولا يحقن نصاً.
 * الحدود المجانية (عدد الرسائل اليومي) تتغير مع الوقت حسب كل شركة.
 */
@Composable
fun FreeAiTab(onOpenBrowser: () -> Unit) {
    val ctx = LocalContext.current
    val sites = listOf(
        Triple("Claude", "https://claude.ai", tr("كتابة وبرمجة وملفات طويلة — مجاني بحد يومي، ثم يتوقف حتى يتجدد.")),
        Triple("ChatGPT", "https://chatgpt.com", tr("الأشهر والأوسع استخداماً — مجاني بحد للرسائل، مع بحث وتوليد صور.")),
        Triple("Gemini", "https://gemini.google.com", tr("من جوجل — مجاني، بحث وملفات طويلة، ويتكامل مع حساب جوجل.")),
        Triple("DeepSeek", "https://chat.deepseek.com", tr("الأقوى مجاناً للبرمجة والتفكير العميق — دردشة مجانية بلا خطة مدفوعة وبلا حد معلن.")),
        Triple("Qwen", "https://chat.qwen.ai", tr("من علي بابا — مجاني بلا حد معلن، يكتب ويبرمج ويولّد صوراً وفيديو.")),
        Triple("Copilot", "https://copilot.microsoft.com", tr("من مايكروسوفت — مجاني، بحث ويب لحظي، ويعمل بحساب مايكروسوفت.")),
        Triple("Perplexity", "https://perplexity.ai", tr("بحث بإجابات موثقة بالمصادر — مجاني، مع 5 أسئلة Pro يومياً.")),
        Triple("Meta AI", "https://meta.ai", tr("من ميتا — مجاني بلا خطة مدفوعة، ويعمل بحساب ميتا.")),
        Triple("Mistral", "https://chat.mistral.ai", tr("أوروبي (Le Chat) — مجاني بحد يومي، وبياناته في أوروبا.")),
        Triple("Grok", "https://grok.com", tr("من xAI — أخبار لحظية من منصة X، مجاني بحد محدود.")),
        Triple("Muse", "https://muse.ai", tr("حسابك مسجل فيه — مجاني، لكن توفره حسب بلدك وقد يطلب انتظاراً."))
    )
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("كل زر يفتح ذكاءً اصطناعياً في تبويب مستقل ببيئة تسجيل دخول منفصلة — سجّل دخولك بنفسك في كل موقع واكتب بنفسك. التطبيق لا يرسل شيئاً تلقائياً ولا يقرأ إجاباتك، والحدود المجانية تتغير مع الوقت."),
            style = MaterialTheme.typography.bodySmall
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sites, key = { it.first }) { (name, url, desc) ->
                Card(Modifier.fillMaxWidth().clickable {
                    BrowserHub.newTab(ctx, url, null)
                    onOpenBrowser()
                }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                        Text(tr("فتح"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
