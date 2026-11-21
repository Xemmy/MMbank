package com.xemmy.mmbank.ui.screens.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.label.LabelRules
import com.xemmy.mmbank.domain.model.Label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LabelRow(val label: Label, val uses: Int)

class LabelsViewModel(private val container: AppContainer) : ViewModel() {

    val rows: StateFlow<List<LabelRow>> = combine(
        container.labelRepository.observeAll(),
        container.labelRepository.observeLinks(),
    ) { labels, links ->
        val counts = LabelRules.useCounts(links)
        labels.map { LabelRow(it, counts[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    fun create(name: String) = viewModelScope.launch {
        if (container.labelRepository.create(name) == null) _message.value = "Enter a name"
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        if (!container.labelRepository.rename(id, name)) _message.value = "That name is empty or already used"
    }

    fun delete(id: Long) = viewModelScope.launch { container.labelRepository.delete(id) }

    fun merge(from: Long, into: Long) = viewModelScope.launch { container.labelRepository.merge(from, into) }
}
