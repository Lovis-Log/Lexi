package io.github.lovislog.lexi

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SettingsPage(modifier: Modifier, model: LexiModel) {
    val config by model.provider.collectAsStateWithLifecycle()
    val form by model.providerForm.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let(model::export)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::restore)
    }
    LaunchedEffect(Unit) { model.loadProvider() }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageTitle("设置", "配置查词接口，备份本地词库。")
        Text("AI 接口", style = MaterialTheme.typography.titleLarge)
        Text(
            "新查询会将文字和你填写的上下文发送到这个接口。命中本地缓存时可离线查看。",
            style = MaterialTheme.typography.bodyMedium,
        )
        config?.let {
            ProviderForm(it, form, model)
        } ?: OutlinedButton(onClick = { model.loadProvider() }, enabled = !working) { Text("读取设置") }
        HorizontalDivider()
        Text("备份", style = MaterialTheme.typography.titleLarge)
        Text("JSON 包含收藏和复习进度。导入只添加本地没有的卡片。", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !working, onClick = { saveFile.launch("Lexi-backup.json") }) { Text("导出 JSON") }
            OutlinedButton(enabled = !working, onClick = { openFile.launch(arrayOf("application/json", "text/plain")) }) {
                Text("导入 JSON")
            }
        }
    }
}

@Composable
private fun ProviderForm(
    config: ProviderInfo,
    form: ProviderFormState,
    app: LexiModel,
) {
    var base by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var apiKey by remember { mutableStateOf("") }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val focus = LocalFocusManager.current
    val usesSavedKey = config.hasKey && endpoint(base) != null && endpoint(base) == endpoint(config.baseUrl)
    val canAuthenticate = base.isNotBlank() && (apiKey.isNotBlank() || usesSavedKey)
    LaunchedEffect(form.models) {
        showPicker = form.models.isNotEmpty()
    }
    if (config.hasKey) {
        Text(
            "当前配置已保存 · ${config.model}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    OutlinedTextField(
        value = base,
        onValueChange = {
            base = it
            app.clearProviderFeedback(clearModels = true)
        },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = {
            apiKey = it
            clipboardMessage = null
            app.clearProviderFeedback(clearModels = true)
        },
        label = { Text("API Key") },
        supportingText = {
            Text(clipboardMessage ?: if (usesSavedKey) {
                "API Key 已加密保存。留空即可继续使用。"
            } else {
                "填写供应商发给你的密钥。"
            })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = !form.busy,
            onClick = {
                val key = clipboard?.primaryClip?.getItemAt(0)?.text
                    ?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    clipboardMessage = "剪贴板里没有文本，请先复制 API Key。"
                } else {
                    apiKey = key
                    clipboardMessage = null
                    app.clearProviderFeedback(clearModels = true)
                }
            },
        ) { Text("粘贴 API Key") }
        OutlinedButton(
            enabled = !form.busy && canAuthenticate,
            onClick = {
                focus.clearFocus()
                app.fetchModels(base, apiKey)
            },
        ) { Text("获取模型") }
    }
    OutlinedTextField(
        value = model,
        onValueChange = {
            model = it
            app.clearProviderFeedback()
        },
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.busy,
        supportingText = { Text("从接口获取后选择，也可手动填写模型 ID。") },
    )
    if (form.models.isNotEmpty()) {
        TextButton(enabled = !form.busy, onClick = { showPicker = true }) { Text("选择模型") }
    }
    form.feedback?.let { feedback ->
        Text(
            feedback.text,
            color = if (feedback.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(
        enabled = !form.busy && canAuthenticate && model.isNotBlank(),
        onClick = {
            focus.clearFocus()
            app.saveProvider(base, model, apiKey) {
                apiKey = ""
                clipboardMessage = null
            }
        },
    ) { Text("保存并测试") }

    if (showPicker && form.models.isNotEmpty()) {
        ModelPicker(form.models, onDismiss = { showPicker = false }) { selected ->
            model = selected
            showPicker = false
            app.clearProviderFeedback()
        }
    }
}

@Composable
private fun ModelPicker(models: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val visible = remember(models, filter) { models.filter { it.contains(filter.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (models.size > 8) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索模型") },
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(visible, key = { it }) { id ->
                        TextButton(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) { Text(id) }
                    }
                    if (visible.isEmpty()) item { Text("没有匹配的模型。") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
