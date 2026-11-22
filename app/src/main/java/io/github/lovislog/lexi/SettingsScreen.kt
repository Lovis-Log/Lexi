package io.github.lovislog.lexi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPage(modifier: Modifier, model: LexiModel, openAi: Boolean) {
    val config by model.provider.collectAsStateWithLifecycle()
    val form by model.providerForm.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val appearance = LocalAppearance.current
    val secret by model.providerSecret.collectAsStateWithLifecycle()
    val keyDraft by model.providerKeyDraft.collectAsStateWithLifecycle()
    var base by rememberSaveable(config?.baseUrl) { mutableStateOf(config?.baseUrl.orEmpty()) }
    var modelId by rememberSaveable(config?.model) { mutableStateOf(config?.model.orEmpty()) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(model::export)
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        it?.let(model::exportPdf)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::restore)
    }
    LaunchedEffect(Unit) { model.loadProvider() }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(tr("设置", "Settings"))
        FoldingCard(tr("AI 接口", "AI endpoint"), initiallyExpanded = openAi) {
            config?.let { saved ->
                val draftEndpoint = endpoint(base)
                val savedKey = secret.takeIf { draftEndpoint != null && draftEndpoint == endpoint(saved.baseUrl) }.orEmpty()
                ProviderForm(
                    saved, form, model, base, modelId, keyDraft ?: savedKey,
                    onBase = {
                        base = it
                        model.clearProviderFeedback(clearModels = true)
                    },
                    onModel = {
                        modelId = it
                        model.clearProviderFeedback()
                    },
                )
            } ?: OutlinedButton(
                onClick = { model.loadProvider() }, enabled = !working,
            ) { Text(tr("读取配置", "Load configuration")) }
        }
        FoldingCard(tr("外观设置", "Appearance")) {
            SectionTitle(tr("系统语言", "Language"))
            ChoiceRow {
                ChoicePill("简体中文", !appearance.english) { model.updateAppearance { it.copy(english = false) } }
                ChoicePill("English", appearance.english) { model.updateAppearance { it.copy(english = true) } }
            }
            SectionTitle(tr("阅读字号", "Reading text size"))
            ChoiceRow {
                listOf(ReadingSize.SMALL to tr("小", "Small"),
                    ReadingSize.MEDIUM to tr("中", "Medium"), ReadingSize.LARGE to tr("大", "Large")).forEach { (size, label) ->
                    ChoicePill(label, appearance.readingSize == size) {
                        model.updateAppearance { it.copy(readingSize = size) }
                    }
                }
            }
            Text(tr("适用于查词结果和词库详情中的释义、搭配、例句及其他阅读内容。",
                "Applies to definitions, collocations, examples, and other content in lookup and library details."),
                style = MaterialTheme.typography.bodySmall)
            SectionTitle(tr("背景动态氛围", "Background ambience"))
            ChoiceRow {
                val options = listOf(
                    Atmosphere.CLOUDS to tr("柔光流云", "Soft clouds"),
                    Atmosphere.GRID to tr("神经网格", "Neural grid"),
                    Atmosphere.STATIC to tr("纯净静态", "Static"),
                )
                options.forEach { (value, title) ->
                    ChoicePill(title, appearance.atmosphere == value) { model.updateAppearance { it.copy(atmosphere = value) } }
                }
            }
            SectionTitle(tr("轻快主题配色", "Accent color"))
            ChoiceRow {
                val options = listOf(
                    Accent.BLUE to tr("晴空蓝", "Sky blue"),
                    Accent.VIOLET to tr("星云紫", "Nebula violet"),
                    Accent.MINT to tr("薄荷绿", "Mint green"),
                )
                options.forEach { (value, title) ->
                    ChoicePill(title, appearance.accent == value) { model.updateAppearance { it.copy(accent = value) } }
                }
            }
        }
        FoldingCard(tr("词库导出与备份", "Vocabulary export and backup")) {
            Text(tr("JSON 备份包含词库和复习进度。PDF 可直接打印。",
                "JSON backs up vocabulary and review progress. PDF is ready to print."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !working, onClick = { saveFile.launch("Lexi-backup.json") },
                    modifier = Modifier.weight(1f)) { Text(tr("导出 JSON", "Export JSON")) }
                OutlinedButton(enabled = !working, onClick = { savePdf.launch("Lexi-vocabulary.pdf") },
                    modifier = Modifier.weight(1f)) { Text(tr("导出 PDF", "Export PDF")) }
            }
            TextButton(enabled = !working, onClick = { openFile.launch(arrayOf("application/json", "text/plain")) }) {
                Text(tr("导入 JSON", "Import JSON"))
            }
        }
        var limit by remember(appearance.dailyLimit) { mutableFloatStateOf(appearance.dailyLimit.toFloat()) }
        FoldingCard(tr("每日复习词数上限", "Daily review limit"), trailing = limit.roundToInt().toString()) {
            Text(tr("每天可复习的不同词句总数，包含新卡和旧卡。同一词句的多个语境卡和当天的重复复习只计一次。",
                "Counts distinct words and phrases across new and existing cards. Different contexts and repeats count once per day."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = limit,
                onValueChange = { limit = it },
                onValueChangeFinished = { model.updateAppearance { it.copy(dailyLimit = limit.roundToInt()) } },
                valueRange = 5f..50f, steps = 8,
            )
        }
        FoldingCard(tr("关于 Lexi", "About Lexi")) { AboutLexi() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

@Composable
private fun ProviderForm(
    config: ProviderInfo,
    form: ProviderFormState,
    app: LexiModel,
    base: String,
    model: String,
    apiKey: String,
    onBase: (String) -> Unit,
    onModel: (String) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var visionModel by rememberSaveable(config.visionModel) { mutableStateOf(config.visionModel) }
    val focus = LocalFocusManager.current
    val canAuthenticate = base.isNotBlank() && apiKey.isNotBlank()
    if (config.hasKey) {
        Text(
            tr("当前配置已保存 · ${config.model}", "Configuration saved · ${config.model}"),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    OutlinedTextField(
        value = base,
        onValueChange = onBase,
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = app::editProviderKey,
        label = { Text("API Key") },
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }, enabled = apiKey.isNotEmpty()) {
                Icon(
                    painterResource(if (keyVisible) R.drawable.lexi_visibility_off else R.drawable.lexi_visibility),
                    contentDescription = if (keyVisible) tr("隐藏 API Key", "Hide API key") else tr("查看 API Key", "Show API key"),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
    )
    OutlinedButton(
        enabled = !form.busy && canAuthenticate,
        onClick = {
            focus.clearFocus()
            app.fetchModels(base, apiKey)
        },
    ) { Text(tr("获取模型", "Fetch models")) }
    OutlinedTextField(
        value = model,
        onValueChange = onModel,
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        supportingText = { Text(tr("从接口获取后选择，也可手动填写模型 ID。", "Choose a fetched model or enter its ID.")) },
    )
    TextButton(enabled = !form.busy && form.models.isNotEmpty(), onClick = { app.setModelPicker(true) }) {
        Text(tr("选取模型", "Choose model"))
    }

    OutlinedTextField(
        value = visionModel,
        onValueChange = { visionModel = it },
        label = { Text(tr("识图模型 ID（选填）", "Vision model ID (optional)")) },
        supportingText = { Text(tr("照片查词使用当前接口。留空时，DeepSeek 官方接口用 deepseek-flash，其他接口用主模型。", "Photo lookup uses this endpoint. If blank, the official DeepSeek endpoint uses deepseek-flash and other endpoints use the main model.")) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
    )
    form.feedback?.let { feedback ->
        Text(
            feedback.text.display(),
            color = if (feedback.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(
        enabled = !form.busy && canAuthenticate && model.isNotBlank(),
        onClick = {
            focus.clearFocus()
            app.saveProvider(base, model, apiKey, visionModel)
        },
    ) { Text(tr("保存并测试", "Save and test")) }

    if (form.showPicker && form.models.isNotEmpty()) {
        ModelPicker(form.models, onDismiss = { app.setModelPicker(false) }) { selected ->
            onModel(selected)
            app.setModelPicker(false)
        }
    }
}

@Composable
private fun ModelPicker(models: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val visible = remember(models, filter) { models.filter { it.contains(filter.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择模型", "Choose model")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (models.size > 8) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(tr("搜索模型", "Search models")) },
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(visible, key = { it }) { id ->
                        TextButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(id) }
                    }
                    if (visible.isEmpty()) item { Text(tr("没有匹配的模型。", "No matching models.")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}
