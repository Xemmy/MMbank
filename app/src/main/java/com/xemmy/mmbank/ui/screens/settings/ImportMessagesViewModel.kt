package com.xemmy.mmbank.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.IgnoredKeyword
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.parser.PastedMessageSplitter
import com.xemmy.mmbank.domain.usecase.ImportPastedMessagesUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs both halves of the "Import & ignore messages" screen: pasting a
 * backlog in to be recorded, and pasting an example in to stop capturing
 * messages like it.
 *
 * The single [busy] guard follows [SettingsViewModel]'s convention — one
 * boolean per ViewModel that every mutating action checks on entry, so a
 * double-tap can't run an import twice.
 */
class ImportMessagesViewModel(private val container: AppContainer) : ViewModel() {

    var importInput by mutableStateOf("")
    var ignoreInput by mutableStateOf("")
    var keywordInput by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set

    /** Result of the last import, shown until the next one replaces it. */
    var lastSummary by mutableStateOf<ImportPastedMessagesUseCase.Summary?>(null)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    val ignored: StateFlow<List<IgnoredMessagePattern>> = container.ignoredMessageRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keywords: StateFlow<List<IgnoredKeyword>> = container.ignoredKeywordRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addKeyword() {
        if (busy || keywordInput.isBlank()) return
        viewModelScope.launch {
            busy = true
            runCatching {
                container.ignoredKeywordRepository.add(keywordInput, System.currentTimeMillis())
            }.onSuccess { stored ->
                statusMessage = if (stored) {
                    keywordInput = ""
                    "Messages containing that will be skipped from now on"
                } else {
                    "Keywords need at least 3 characters"
                }
            }.onFailure {
                container.recordError("ImportMessagesViewModel.addKeyword", it)
                statusMessage = "Couldn't save: ${it.message}"
            }
            busy = false
        }
    }

    fun deleteKeyword(id: Long) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            runCatching { container.ignoredKeywordRepository.delete(id) }
                .onFailure {
                    container.recordError("ImportMessagesViewModel.deleteKeyword", it)
                    statusMessage = "Couldn't remove: ${it.message}"
                }
            busy = false
        }
    }

    /** How many messages the current paste would import, so the button can say so. */
    val pendingCount: Int get() = PastedMessageSplitter.split(importInput).size

    fun import() {
        if (busy || importInput.isBlank()) return
        viewModelScope.launch {
            busy = true
            runCatching {
                container.importPastedMessages(importInput, System.currentTimeMillis())
            }.onSuccess { summary ->
                lastSummary = summary
                statusMessage = null
                // Only clear the box on a clean run: if some messages didn't
                // parse, the text is the only copy the user still has of them.
                if (summary.unparsed == 0) importInput = ""
            }.onFailure {
                container.recordError("ImportMessagesViewModel.import", it)
                statusMessage = "Import failed: ${it.message}"
            }
            busy = false
        }
    }

    fun addIgnorePattern() {
        if (busy || ignoreInput.isBlank()) return
        viewModelScope.launch {
            busy = true
            runCatching {
                container.ignoredMessageRepository.add(ignoreInput, System.currentTimeMillis())
            }.onSuccess { stored ->
                statusMessage = if (stored) {
                    ignoreInput = ""
                    "Messages like this one will be skipped from now on"
                } else {
                    "That sample has no words to match on — paste the message text itself"
                }
            }.onFailure {
                container.recordError("ImportMessagesViewModel.addIgnorePattern", it)
                statusMessage = "Couldn't save: ${it.message}"
            }
            busy = false
        }
    }

    fun deleteIgnorePattern(id: Long) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            runCatching { container.ignoredMessageRepository.delete(id) }
                .onSuccess { statusMessage = "Removed — messages like it will be captured again" }
                .onFailure {
                    container.recordError("ImportMessagesViewModel.deleteIgnorePattern", it)
                    statusMessage = "Couldn't remove: ${it.message}"
                }
            busy = false
        }
    }
}
