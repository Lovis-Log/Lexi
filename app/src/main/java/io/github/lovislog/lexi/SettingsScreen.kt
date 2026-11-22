package io.github.lovislog.lexi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPage(modifier: Modifier, model: LexiModel) {
    val config by model.provider.collectAsStateWithLifecycle()
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
            ProviderForm(it, working, model::saveProvider)
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
    working: Boolean,
    onSave: (String, String, String, () -> Unit) -> Unit,
) {
    var base by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var apiKey by remember { mutableStateOf("") }
    var clipboardMessage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = base,
        onValueChange = { base = it },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !working,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !working,
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = {
            apiKey = it
            clipboardMessage = null
        },
        label = { Text("API Key") },
        supportingText = {
            Text(clipboardMessage ?: if (config.hasKey) {
                "接口地址相同时，留空会保留原密钥。"
            } else {
                "填写供应商发给你的密钥。"
            })
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !working,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = !working,
            onClick = {
                scope.launch {
                    val key = clipboard.getClip()?.clipData?.getItemAt(0)?.text
                        ?.toString()?.trim().orEmpty()
                    if (key.isEmpty()) {
                        clipboardMessage = "剪贴板里没有文本，请先复制 API Key。"
                    } else {
                        apiKey = key
                        clipboardMessage = null
                    }
                }
            },
        ) { Text("粘贴 API Key") }
        Button(
            enabled = !working && base.isNotBlank() && model.isNotBlank(),
            onClick = {
                onSave(base, model, apiKey) {
                    apiKey = ""
                    clipboardMessage = null
                }
            },
        ) { Text("保存接口") }
    }
}
