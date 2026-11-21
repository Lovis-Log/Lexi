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

internal class LexiModel(app: Application) : AndroidViewModel(app) {
    private val application = app as LexiApplication
    private val settings = application.settings
    private val repository = application.repository
    private var lookupJob: Job? = null

    private val mutableLookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookup = mutableLookup.asStateFlow()
    private val mutableProvider = MutableStateFlow<ProviderInfo?>(null)
    val provider = mutableProvider.asStateFlow()
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

    fun saveProvider(base: String, model: String, key: String, onSaved: () -> Unit) = action {
        settings.save(base, model, key)
        mutableProvider.value = settings.info()
        onSaved()
        mutableNotice.value = "设置已保存"
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
