package com.relay.agents

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** شاشة المحادثة: دردشة مع فريق النماذج، وتحكم بأسلوب التنسيق بينها بتعليمات حرة.
 * v28: بحث نصي في المحادثة، تفرّع من أي رسالة، «أكمل مع الأفضل» بعد
 * المقارنة، مكتبة أوامر بمتغيرات وسلاسل، وإرفاق ملفات معرفة. */
@Composable
fun ChatTab(vm: RelayViewModel, onOpenVideo: () -> Unit = {}, onOpenRun: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var libOpen by remember { mutableStateOf(false) }
    var chainFirst by remember { mutableStateOf<PromptTemplate?>(null) }
    var fillTarget by remember { mutableStateOf<Pair<PromptTemplate, PromptTemplate?>?>(null) }
    val listState = rememberLazyListState()
    val msgs = vm.chat
    val processor = remember { AttachmentProcessor(ctx) }
    val knowledgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val att = runCatching { processor.process(uri) }.getOrNull()
        if (att != null && att.text.isNotBlank()) {
            vm.addKnowledgeDoc(att.name, att.text)
            Toast.makeText(ctx, trf("أُضيف ملف المعرفة: %s", att.name), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, tr("الملف ليس نصاً مقروءاً — جرّب ملف txt أو md"), Toast.LENGTH_LONG).show()
        }
    }

    // البحث: يعرض الرسائل المطابقة فقط مع الاحتفاظ بفهرسها الأصلي للتفرّع.
    val displayed: List<Pair<Int, ChatMsg>> = remember(msgs, search) {
        val indexed = msgs.withIndex().map { it.index to it.value }
        if (search.isBlank()) indexed else indexed.filter { (_, m) ->
            m.text.contains(search, ignoreCase = true) ||
                m.extras.any { (_, t) -> t.contains(search, ignoreCase = true) }
        }
    }

    LaunchedEffect(msgs.size, vm.chatBusy) {
        val last = msgs.size + (if (vm.chatBusy) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    if (libOpen) PromptLibraryDialog(
        vm = vm,
        chainFirst = chainFirst,
        onPickChain = { chainFirst = it },
        onUse = { t ->
            val first = chainFirst
            chainFirst = null
            if (first != null && first.id != t.id) fillTarget = first to t
            else fillTarget = t to null
            libOpen = false
        },
        onDismiss = { libOpen = false; chainFirst = null }
    )

    fillTarget?.let { (first, second) ->
        FillVarsDialog(
            first = first, second = second,
            onConfirm = { values ->
                fillTarget = null
                if (second != null) vm.runPromptChain(first, second, values)
                else input = first.fill(values)
            },
            onDismiss = { fillTarget = null }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var picker by remember { mutableStateOf(false) }
            AssistChip(
                onClick = { picker = true },
                leadingIcon = { StatusDot(Brand.Green, 8) },
                label = {
                    Text(
                        when (vm.chatInclude.size) {
                            0 -> tr("تلقائي")
                            1 -> vm.agents.firstOrNull { it.id == vm.chatInclude.first() }?.name ?: tr("تلقائي")
                            else -> trf("%s وكيل", vm.chatInclude.size)
                        } + " ⌄",
                        maxLines = 1
                    )
                }
            )
            Text(modeLabel(vm.chatMode), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
            if (picker) ModelPicker(vm) { picker = false }
            TextButton(onClick = { showSearch = !showSearch }) { Text("🔍") }
            TextButton(onClick = { libOpen = true }) { Text("📚") }
            TextButton(onClick = { panel = !panel }) { Text(if (panel) tr("إخفاء التنسيق") else tr("🎛️ التنسيق")) }
            TextButton(enabled = msgs.isNotEmpty(), onClick = { vm.clearChat() }) { Text(tr("مسح")) }
        }

        if (showSearch) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    search, { search = it },
                    placeholder = { Text(tr("ابحث في المحادثة…")) },
                    modifier = Modifier.weight(1f), singleLine = true
                )
                if (search.isNotBlank()) Text(
                    trf("%s نتيجة", displayed.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (vm.branches.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("الفروع:"), style = MaterialTheme.typography.labelSmall)
                vm.branches.forEach { b ->
                    AssistChip(onClick = { vm.openBranch(b.id) }, label = { Text(b.name.take(24), maxLines = 1) })
                    TextButton(onClick = { vm.removeBranch(b.id) }) { Text("✕") }
                }
            }
        }

        if (panel) CoordinationPanel(vm)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && !vm.chatBusy) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✳️", style = MaterialTheme.typography.displaySmall)
                    Text(tr("ماذا يدور في ذهنك؟"), style = MaterialTheme.typography.headlineSmall)
                    Text(trf("%s نموذج جاهز", vm.agents.count { it.enabled }), style = MaterialTheme.typography.labelLarge, color = Brand.GreenDark)
                    Text(tr("اسأل أو اطلب. يجيبك فريق النماذج ويتبدّل تلقائياً عند توقف أحدها."),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(tr("اكتب /task ثم الهدف لبدء مهمة تنسيق كاملة بنقاط استئناف."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(displayed, key = { (i, _) -> i }) { (i, m) -> Bubble(m, i, vm, onOpenVideo) }
                if (vm.chatBusy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(vm.chatStatus.ifEmpty { tr("⏳ جارٍ التفكير…") }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (vm.knowledgeDocs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("📚 معرفة:"), style = MaterialTheme.typography.labelSmall)
                vm.knowledgeDocs.forEach { d ->
                    AssistChip(onClick = {}, label = { Text(d.name.take(20), maxLines = 1) })
                    TextButton(onClick = { vm.removeKnowledgeDoc(d.id) }) { Text("✕") }
                }
            }
            Text(
                tr("تُستخدم المقاطع ذات الصلة من هذه الملفات في كل سؤال."),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = { knowledgePicker.launch(arrayOf("text/*")) }) { Text("📎") }
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text(tr("اكتب رسالة…")) },
                modifier = Modifier.weight(1f), maxLines = 5
            )
            if (vm.chatBusy) {
                Button(onClick = { vm.stopChat() }) { Text(tr("إيقاف")) }
            } else {
                Button(enabled = input.isNotBlank(), onClick = {
                    val toRun = vm.sendChat(input)
                    input = ""
                    if (toRun) onOpenRun()
                }) { Text(tr("إرسال")) }
            }
        }
    }
}

@Composable
private fun modeLabel(m: ChatMode): String = when (m) {
    ChatMode.AUTO -> tr("تلقائي")
    ChatMode.COMPARE -> tr("مقارنة")
    ChatMode.PIPELINE -> tr("مسودة ← نقد ← تحسين")
    ChatMode.CONSULT -> tr("تشاور")
}

/** مكتبة الأوامر: قوالب جاهزة وقوالبك، زر «استخدام» يملأ المتغيرات،
 * وزر «🔗» يختار أول السلسلة ثم «استخدام» على قالب ثانٍ يشغّلها. */
@Composable
private fun PromptLibraryDialog(
    vm: RelayViewModel,
    chainFirst: PromptTemplate?,
    onPickChain: (PromptTemplate) -> Unit,
    onUse: (PromptTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("📚 مكتبة الأوامر")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("إغلاق")) } },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chainFirst != null) {
                    item { Text(trf("أول السلسلة: %s — اختر القالب الثاني.", chainFirst.name), style = MaterialTheme.typography.bodySmall) }
                }
                item { Text(tr("قوالب جاهزة"), style = MaterialTheme.typography.titleSmall) }
                items(vm.builtInTemplates, key = { it.id }) { t ->
                    TemplateRow(t, chainFirst, onPickChain, onUse)
                }
                if (vm.promptTemplates.isNotEmpty()) {
                    item { Text(tr("قوالبي"), style = MaterialTheme.typography.titleSmall) }
                    items(vm.promptTemplates, key = { it.id }) { t ->
                        TemplateRow(t, chainFirst, onPickChain, onUse)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { vm.removePromptTemplate(t.id) }) { Text(tr("حذف")) }
                        }
                    }
                }
                item {
                    Text(tr("حفظ قالب جديد"), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(name, { name = it }, label = { Text(tr("اسم القالب")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        text, { text = it },
                        label = { Text(tr("النص — استخدم {{متغير}} لأماكن التعبئة")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                    Button(
                        onClick = { vm.addPromptTemplate(name, text); name = ""; text = "" },
                        enabled = name.isNotBlank() && text.isNotBlank()
                    ) { Text(tr("حفظ القالب")) }
                }
            }
        }
    )
}

@Composable
private fun TemplateRow(
    t: PromptTemplate,
    chainFirst: PromptTemplate?,
    onPickChain: (PromptTemplate) -> Unit,
    onUse: (PromptTemplate) -> Unit
) {
    Column {
        Text(t.name, style = MaterialTheme.typography.bodyMedium)
        Text(t.text.take(120), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onUse(t) }) { Text(tr("استخدام")) }
            TextButton(onClick = { onPickChain(t) }) {
                Text(if (chainFirst?.id == t.id) tr("🔗 أول السلسلة ✓") else tr("🔗 سلسلة"))
            }
        }
    }
}

/** تعبئة متغيرات {{…}} لقالب (أو قالبين في سلسلة) قبل الإرسال. */
@Composable
private fun FillVarsDialog(
    first: PromptTemplate,
    second: PromptTemplate?,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val vars = remember(first, second) { (first.vars() + (second?.vars() ?: emptyList())).distinct() }
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (second != null) tr("سلسلة أوامر") else tr("تعبئة القالب")) },
        confirmButton = {
            TextButton(
                enabled = vars.all { (values[it] ?: "").isNotBlank() },
                onClick = { onConfirm(values.toMap()) }
            ) { Text(if (second != null) tr("تشغيل السلسلة") else tr("إدراج")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("إلغاء")) } },
        text = {
            if (vars.isEmpty()) {
                Text(tr("القالب بلا متغيرات — سيُدرج كما هو."))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vars) { v ->
                        OutlinedTextField(
                            values[v] ?: "", { values[v] = it },
                            label = { Text(v) }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    if (second != null) {
                        item { Text(trf("الأول: %s — الثاني: %s", first.name, second.name), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    )
}

@Composable
private fun CoordinationPanel(vm: RelayViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("أسلوب التنسيق بين النماذج"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatMode.values().forEach { m ->
                    FilterChip(selected = vm.chatMode == m, onClick = { vm.selectChatMode(m) }, label = { Text(modeLabel(m)) })
                }
            }
            Text(
                when (vm.chatMode) {
                    ChatMode.AUTO -> tr("وكيل واحد يجيب، وعند توقفه يكمل التالي تلقائياً.")
                    ChatMode.COMPARE -> tr("حتى 4 وكلاء يجيبون بالتوازي ثم تُدمج إجاباتهم في رد واحد. يستهلك حصة أكبر.")
                    ChatMode.PIPELINE -> tr("وكيل يكتب مسودة، وآخر ينقدها، ثم يُحسَّن الرد. 3 طلبات لكل رسالة.")
                    ChatMode.CONSULT -> tr("حتى 3 وكلاء يجيبون، ثم يراجع كلٌّ منهم إجابات زملائه ويحسّن إجابته، ثم تُدمج في أفضل نتيجة. حتى 7 طلبات لكل رسالة ويحتاج وكيلين مفعّلين على الأقل.")
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(tr("الوكلاء المشاركون (بلا تحديد = الكل)"), style = MaterialTheme.typography.titleSmall)
            val usable = vm.agents.filter { it.enabled && !it.provider.manual }
            if (usable.isEmpty()) {
                Text(tr("لا يوجد وكلاء API مفعّلون. أضفهم من تبويب الوكلاء."), style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    usable.forEach { a ->
                        FilterChip(selected = a.id in vm.chatInclude, onClick = { vm.toggleChatAgent(a.id) }, label = { Text(a.name, maxLines = 1) })
                    }
                }
            }

            OutlinedTextField(
                vm.chatInstructions, { vm.editChatInstructions(it) },
                label = { Text(tr("تعليماتك للتنسيق (اختياري)")) },
                placeholder = { Text(tr("مثال: اجعل الإجابات مختصرة، وراجع الأرقام قبل الرد")) },
                modifier = Modifier.fillMaxWidth(), maxLines = 4
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(vm.chatMemory, { vm.switchChatMemory(it) })
                Spacer(Modifier.width(8.dp))
                Text(tr("استخدام ذاكرة المشاريع المحفوظة"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, index: Int, vm: RelayViewModel, onOpenVideo: () -> Unit) {
    val mine = m.role == "user"
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!mine && m.by.isNotEmpty()) Text(m.by, style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                // ربط النماذج النصية بنماذج الفيديو: رد النموذج النصي
                // (مثل وصف مشهد كتبه Grok) يُرسل كما هو إلى شاشة توليد
                // الفيديو، فتتحول مشاهده إلى مقاطع بنماذج الفيديو.
                if (!mine && m.by != "system" && m.text.isNotBlank()) {
                    TextButton(onClick = { vm.pendingVideoText = m.text; onOpenVideo() }) {
                        Text(tr("🎬 إرسال إلى توليد الفيديو"))
                    }
                }
                // التفرّع: من أي رسالة يبدأ مسار جديد مع حفظ الكاملة فرعاً.
                TextButton(onClick = { vm.forkChat(index) }) {
                    Text(tr("⎇ تفرّع من هنا"))
                }
                if (m.extras.isNotEmpty()) {
                    TextButton(onClick = { open = !open }) {
                        Text(if (open) tr("إخفاء الإجابات الفردية") else trf("عرض الإجابات الفردية (%s)", m.extras.size))
                    }
                    if (open) m.extras.forEachIndexed { j, (n, t) ->
                        Text(n, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(t, style = MaterialTheme.typography.bodySmall) }
                        // أكمل مع الأفضل: يثبّت المحادثة على صاحب هذه الإجابة.
                        val bestId = m.extraIds.getOrNull(j)
                            ?: vm.agents.firstOrNull { it.name == n }?.id.orEmpty()
                        if (bestId.isNotEmpty()) {
                            TextButton(onClick = { vm.continueWithBest(bestId) }) {
                                Text(trf("↪️ أكمل مع %s", n))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** منتقي النموذج: «تلقائي» أو وكيل واحد بعينه. */
@Composable
private fun ModelPicker(vm: RelayViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("اختر نموذجاً")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("تم")) } },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(tr("تلقائي")) },
                        supportingContent = { Text(tr("أذكى وكيل جاهز، مع تبديل تلقائي")) },
                        trailingContent = { if (vm.chatInclude.isEmpty()) Text("✓") },
                        modifier = Modifier.clickable { vm.pickChatAgent(null); onDismiss() }
                    )
                }
                items(vm.agents.filter { it.enabled && !it.provider.manual }, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = { Text(a.name, maxLines = 1) },
                        supportingContent = { Text(a.provider.label + " · " + a.model.take(30), maxLines = 1) },
                        leadingContent = { ProviderBadge(a.provider.label, 36) },
                        trailingContent = { if (a.id in vm.chatInclude) Text("✓") },
                        modifier = Modifier.clickable { vm.pickChatAgent(a.id); onDismiss() }
                    )
                }
            }
        }
    )
}
