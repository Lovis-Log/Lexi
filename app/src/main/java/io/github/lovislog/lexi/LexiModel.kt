package io.github.lovislog.lexi

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
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
    data class Failed(val message: LocalizedText, val opensSettings: Boolean) : LookupState
}

internal data class ProviderFeedback(val text: LocalizedText, val isError: Boolean = false)

internal data class ProviderFormState(
    val models: List<String> = emptyList(),
    val feedback: ProviderFeedback? = null,
    val busy: Boolean = false,
    val showPicker: Boolean = false,
)

internal class LexiModel(app: Application) : AndroidViewModel(app) {
    val backStack = mutableStateListOf<AppLocation>()
    var photoOrigin: AppLocation? = null

    private val application = app as LexiApplication
    private val settings = application.settings
    private val repository = application.repository
    val appearance = application.appearance.state
    fun updateAppearance(transform: (Appearance) -> Appearance) = application.appearance.update(transform)
    private var lookupJob: Job? = null
    private val mutableReviewSession = MutableStateFlow(ReviewSession())
    val reviewSession = mutableReviewSession.asStateFlow()

    private val mutableLookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookup = mutableLookup.asStateFlow()
    private val mutableProvider = MutableStateFlow<ProviderInfo?>(null)
    val provider = mutableProvider.asStateFlow()
    private val mutableProviderSecret = MutableStateFlow("")
    val providerSecret = mutableProviderSecret.asStateFlow()
    private val mutableProviderKeyDraft = MutableStateFlow<String?>(null)
    val providerKeyDraft = mutableProviderKeyDraft.asStateFlow()

    private val mutableProviderForm = MutableStateFlow(ProviderFormState())
    val providerForm = mutableProviderForm.asStateFlow()
    private val mutableNotice = MutableStateFlow<LocalizedText?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()

    val cards = repository.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteHistory(id: String) = action { repository.deleteHistory(id) }
    fun clearHistory() = action { repository.clearHistory() }

    fun loadProvider() = action {
        mutableProvider.value = settings.info()
        mutableProviderSecret.value = settings.config()?.apiKey.orEmpty()
    }

    fun editProviderKey(value: String) {
        mutableProviderKeyDraft.value = value
        clearProviderFeedback(clearModels = true)
    }

    fun setModelPicker(visible: Boolean) {
        mutableProviderForm.value = mutableProviderForm.value.copy(showPicker = visible)
    }

    fun search(text: String, context: String) = performLookup { repository.lookup(LookupInput(text, context)) }

    fun searchImage(base64Jpeg: String, context: String) = performLookup { repository.lookupImage(base64Jpeg, context) }

    private fun performLookup(request: suspend () -> LookupResult) {
        lookupJob?.cancel()
        mutableLookup.value = LookupState.Loading
        lookupJob = viewModelScope.launch {
            mutableLookup.value = try {
                LookupState.Ready(request())
            } catch (failure: Exception) {
                LookupState.Failed(explain(failure), (failure as? LookupFailure)?.opensSettings == true)
            }
        }
    }

    fun clearLookup() = restoreLookup(LookupState.Idle)

    fun restoreLookup(state: LookupState) {
        lookupJob?.cancel()
        mutableLookup.value = state.takeUnless { it is LookupState.Loading } ?: LookupState.Idle
    }

    fun save(result: LookupResult) = action {
        val updated = repository.save(result)
        mutableNotice.value = if (updated) LocalizedText("已更新词条解析", "Entry updated")
        else LocalizedText("已加入词库", "Added to library")
    }

fun syncReviewSession(day: ReviewDay, active: Set<String>, due: List<SavedCard>) {
        mutableReviewSession.value = mutableReviewSession.value.sync(day, active, due)
    }

    fun focusReviewCard(id: String) {
        mutableReviewSession.value = mutableReviewSession.value.focus(id)
    }

    fun rate(card: SavedCard, rating: Rating) = action {
        val session = mutableReviewSession.value
        val entry = session.sessionQueue.firstOrNull { it.id == card.id } ?: return@action
        val updated = repository.rate(card, rating, appearance.value.dailyLimit, entry.requeueCount > 0)
            ?: return@action
        val current = mutableReviewSession.value
        if (current.dayStart == session.dayStart) {
            mutableReviewSession.value = current.rated(card.id, updated.schedule.reviews, rating)
        }
    }

    fun archive(card: SavedCard) = action {
        repository.setArchived(card, !card.archived)
        mutableNotice.value = if (card.archived) LocalizedText("已恢复复习", "Review resumed")
        else LocalizedText("已暂停复习", "Review paused")
    }

    fun fetchModels(base: String, key: String) = providerAction(LocalizedText("正在获取模型…", "Fetching models…")) {
        val models = application.lookupService.listModels(settings.draft(base, "", key))
        mutableProviderForm.value = ProviderFormState(
            models = models,
            feedback = ProviderFeedback(LocalizedText("已获取 ${models.size} 个模型，请选择。", "Found ${models.size} models. Choose one to continue.")),
            busy = true,
            showPicker = true,
        )
    }

    fun saveProvider(base: String, model: String, key: String, vision: String) =
        providerAction(LocalizedText("正在测试模型的 JSON 响应…", "Testing the model’s JSON response…")) {
            val candidate = settings.draft(base, model, key, vision)
            if (candidate.model.isBlank()) throw LookupFailure("请先选择或填写模型。", "Choose or enter a model ID.")
            application.lookupService.verifyProvider(candidate)
            mutableProvider.value = settings.save(candidate)
            mutableProviderSecret.value = candidate.apiKey
            mutableProviderKeyDraft.value = null
            reportProvider(LocalizedText("模型连接成功，配置已保存。", "The model responded successfully. Settings saved."))
        }

    fun clearProviderFeedback(clearModels: Boolean = false) {
        val form = mutableProviderForm.value
        if (form.busy) return
        mutableProviderForm.value = form.copy(
            models = if (clearModels) emptyList() else form.models,
            showPicker = form.showPicker && !clearModels,
            feedback = null,
        )
    }

    private fun reportProvider(text: LocalizedText, isError: Boolean = false) {
        mutableProviderForm.value = mutableProviderForm.value.copy(feedback = ProviderFeedback(text, isError))
    }

    private fun providerAction(start: LocalizedText, block: suspend () -> Unit) {
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

    fun export(uri: Uri) = action {
        val count = repository.export(uri)
        mutableNotice.value = LocalizedText("已导出 $count 张卡片", "Exported $count cards")
    }

    fun exportPdf(uri: Uri) = action {
        val count = repository.exportPdf(uri, appearance.value.english)
        mutableNotice.value = LocalizedText("已导出 $count 张卡片至 PDF", "Exported $count cards to PDF")
    }

    fun restore(uri: Uri) = action {
        val count = repository.restore(uri)
        mutableNotice.value = LocalizedText("已新增 $count 张卡片", "Imported $count cards")
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    fun showNotice(message: LocalizedText) {
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

    private fun explain(failure: Exception): LocalizedText = when (failure) {
        is CancellationException -> throw failure
        is LookupFailure -> LocalizedText(failure.message ?: "操作未完成，请重试。", failure.english)
        else -> LocalizedText("操作未完成，请重试。", "The operation could not be completed. Please try again.")
    }
}
