package com.example.unicitywallet.ui.settings.nametags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.unicitywallet.services.MintResult
import com.example.unicitywallet.services.NametagService
import com.example.unicitywallet.services.NametagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NametagItemUi(
    val name: String,
    val isActive: Boolean,
    val status: NametagStatus = NametagStatus.UNKNOWN
)

data class NametagUiState(
    val nametags: List<NametagItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

class NametagViewModel(
    private val nametagService: NametagService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NametagUiState())
    val uiState: StateFlow<NametagUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val allTags = nametagService.listAllNametags()
            val activeTag = nametagService.getActiveNametag()

            val items = allTags.map { tag ->
                NametagItemUi(
                    name = tag,
                    isActive = tag == activeTag,
                    status = NametagStatus.VERIFIED
                )
            }

            _uiState.value = _uiState.value.copy(
                nametags = items,
                isLoading = false
            )
        }
    }

    fun mintNametag(input: String) {
        if (input.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, successMessage = null)

            val result = nametagService.mintNameTagAndPublish(input)

            when (result) {
                is MintResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "Nametag created and active!"
                    )
                    loadData()
                }
                is MintResult.Warning -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "Created with warning: ${result.message}"
                    )
                    loadData()
                }
                is MintResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
            }
        }
    }

    fun selectNametag(nametag: String) {
        nametagService.setActiveNametag(nametag)
        loadData()
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}