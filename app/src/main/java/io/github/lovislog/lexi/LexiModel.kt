package io.github.lovislog.lexi

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Ready(val result: LookupResult) : LookupState
    data class Failed(val message: String) : LookupState
}

internal data class ProviderFeedback(val text: String, val isError: Boolean = false)

internal data class ProviderFormState(
    val models: List<String> = emptyList(),
    val feedback: ProviderFeedback? = null,
    val busy: Boolean = false,
)

internal class LexiModel(app: Application) : AndroidViewModel(app) {
    private val application = app as LexiApplication
    private val settings = application.settings
    private val repository = application.repository
    private var lookupJob: Job? = null

    private val mutableLookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookup = mutableLookup.asStateFlow()
    private val mutableProvider = MutableStateFlow<ProviderInfo?>(null)
    val provider = mutableProvider.asStateFlow()
    private val mutableProviderForm = MutableStateFlow(ProviderFormState())
    val providerForm = mutableProviderForm.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadProvider() = action { mutableProvider.value = settings.info() }

    fun search(text: String, context: String) {
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            mutableLookup.value = LookupState.Loading
            mutableLookup.value = try {
                LookupState.Ready(repository.lookup(LookupInput(text, context)))
            } catch (failure: Exception) {
                LookupState.Failed(explain(failure))
            }
        }
    }

    fun clearLookup() {
        lookupJob?.cancel()
        mutableLookup.value = LookupState.Idle
    }

    fun save(result: LookupResult) = action {
        repository.save(result)
        mutableNotice.value = "已收藏"
    }

    fun rate(card: SavedCard, rating: Rating) = action { repository.rate(card, rating) }

    fun archive(card: SavedCard) = action {
        repository.setArchived(card, !card.archived)
        mutableNotice.value = if (card.archived) "已恢复复习" else "已暂停复习"
    }

    fun fetchModels(base: String, key: String) = providerAction("正在获取模型…") {
        val models = application.lookupService.listModels(settings.draft(base, "", key))
        mutableProviderForm.value = ProviderFormState(
            models = models,
            feedback = ProviderFeedback("已获取 ${models.size} 个模型，请选择。"),
            busy = true,
        )
    }

    fun saveProvider(base: String, model: String, key: String, onSaved: () -> Unit) =
        providerAction("正在用 apple 测试查词…") {
            val candidate = settings.draft(base, model, key)
            if (candidate.model.isBlank()) throw LookupFailure("请先选择或填写模型。")
            application.lookupService.explain(LookupInput("apple"), candidate)
            mutableProvider.value = settings.save(candidate)
            reportProvider("测试查词成功，接口和密钥已保存。")
            onSaved()
        }

    fun clearProviderFeedback(clearModels: Boolean = false) {
        val form = mutableProviderForm.value
        if (form.busy) return
        mutableProviderForm.value = form.copy(
            models = if (clearModels) emptyList() else form.models,
            feedback = null,
        )
    }

    private fun reportProvider(text: String, isError: Boolean = false) {
        mutableProviderForm.value = mutableProviderForm.value.copy(feedback = ProviderFeedback(text, isError))
    }

    private fun providerAction(start: String, block: suspend () -> Unit) {
        if (mutableProviderForm.value.busy) return
        mutableProviderForm.value = mutableProviderForm.value.copy(busy = true, feedback = ProviderFeedback(start))
        viewModelScope.launch {
            try {
                block()
            } catch (failure: Exception) {
                reportProvider(explain(failure), isError = true)
            } finally {
                mutableProviderForm.value = mutableProviderForm.value.copy(busy = false)
            }
        }
    }

    fun export(uri: Uri) = action { mutableNotice.value = "已导出 ${repository.export(uri)} 张卡片" }
    fun restore(uri: Uri) = action { mutableNotice.value = "已新增 ${repository.restore(uri)} 张卡片" }

    fun clearNotice() {
        mutableNotice.value = null
    }

    fun showNotice(message: String) {
        mutableNotice.value = message
    }

    private fun action(block: suspend () -> Unit) {
        if (mutableWorking.value) return
        mutableWorking.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (failure: Exception) {
                mutableNotice.value = explain(failure)
            } finally {
                mutableWorking.value = false
            }
        }
    }

    private fun explain(failure: Exception): String = when (failure) {
        is CancellationException -> throw failure
        is LookupFailure -> failure.message ?: "操作未完成，请重试。"
        else -> "操作未完成，请重试。"
    }
}
