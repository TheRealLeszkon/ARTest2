package com.example.arsurvey.reconstruct

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.reconstruct.ml.SegmentationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the reconstruction screen. */
data class ReconstructionUiState(
    val loading: Boolean = true,
    val progressText: String? = null,
    val result: ReconstructionResult? = null,
    val error: String? = null,
)

/**
 * Runs [ObjectReconstructor] off the main thread and exposes its state. Owns the single
 * [SegmentationPipeline] (YOLOE + EfficientSAM) used for the 3 inferences — loaded lazily, closed in
 * [onCleared].
 */
class ReconstructionViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReconstructionUiState())
    val state: StateFlow<ReconstructionUiState> = _state.asStateFlow()

    private var pipeline: SegmentationPipeline? = null
    private var started = false

    /** Idempotent: starts reconstruction once for the given photos. */
    fun start(photos: List<CapturedPhoto>) {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _state.update { it.copy(loading = true, progressText = "Loading models…") }
                val p = pipeline ?: SegmentationPipeline(getApplication()).also { pipeline = it }
                val result = ObjectReconstructor.reconstruct(photos, p) { idx ->
                    _state.update { it.copy(progressText = "Detecting + segmenting photo ${idx + 1} of ${photos.size}…") }
                }
                Log.i(TAG, "Reconstruction done: ${result.totalPoints} points across ${photos.size} photos")
                _state.update { it.copy(loading = false, result = result, progressText = null) }
            } catch (t: Throwable) {
                Log.e(TAG, "Reconstruction failed", t)
                _state.update { it.copy(loading = false, error = t.message ?: "reconstruction failed") }
            }
        }
    }

    override fun onCleared() {
        pipeline?.close()
        pipeline = null
    }

    private companion object {
        const val TAG = "ReconstructionVM"
    }
}
