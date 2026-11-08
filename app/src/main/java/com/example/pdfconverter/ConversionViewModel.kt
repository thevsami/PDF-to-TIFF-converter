package com.example.pdfconverter

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConverterUiState(
    val selectedUri: Uri? = null,
    val fileName: String = "",
    val pageCount: Int = 0,
    val format: OutputFormat = OutputFormat.TIFF,
    val alsoJpegPages: Boolean = true,
    val dpi: Int = 150,
    val jpegQuality: Int = 90,
    val pageFrom: String = "1",
    val pageTo: String = "",
    val isInspecting: Boolean = false,
    val isConverting: Boolean = false,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val status: String = "Drop in a PDF to convert it to TIFF, JPEG, or JPG.",
    val error: String? = null,
    val result: ConversionResult? = null,
    val preview: Bitmap? = null
)

class ConversionViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ConverterUiState())
    val state: StateFlow<ConverterUiState> = _state
    private var convertJob: Job? = null
    private var inspectJob: Job? = null

    fun onFormatSelected(format: OutputFormat) {
        _state.update { it.copy(format = format, error = null) }
    }

    fun onAlsoJpegChanged(enabled: Boolean) {
        _state.update { it.copy(alsoJpegPages = enabled) }
    }

    fun onDpiSelected(dpi: Int) {
        _state.update { it.copy(dpi = dpi) }
    }

    fun onQualityChanged(quality: Int) {
        _state.update { it.copy(jpegQuality = quality) }
    }

    fun onPageFromChanged(value: String) {
        _state.update { it.copy(pageFrom = value.filter { char -> char.isDigit() }.take(4)) }
    }

    fun onPageToChanged(value: String) {
        _state.update { it.copy(pageTo = value.filter { char -> char.isDigit() }.take(4)) }
    }

    fun consumeIncomingUri(uri: Uri?) {
        if (uri == null) return
        selectPdf(uri)
    }

    fun selectPdf(uri: Uri) {
        inspectJob?.cancel()
        convertJob?.cancel()
        _state.update {
            it.copy(
                selectedUri = uri,
                isInspecting = true,
                isConverting = false,
                result = null,
                error = null,
                preview = null,
                status = "Reading PDF…"
            )
        }
        inspectJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    PdfConverter.inspect(getApplication(), uri)
                }
                val preview = withContext(Dispatchers.IO) {
                    runCatching { PdfConverter.renderPreview(getApplication(), uri) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        fileName = info.displayName,
                        pageCount = info.pageCount,
                        pageFrom = "1",
                        pageTo = info.pageCount.toString(),
                        isInspecting = false,
                        preview = preview,
                        status = "${info.pageCount} page${if (info.pageCount == 1) "" else "s"} ready to convert"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        selectedUri = null,
                        fileName = "",
                        pageCount = 0,
                        isInspecting = false,
                        preview = null,
                        error = error.message ?: "Unable to open this PDF.",
                        status = "Choose a valid PDF to continue."
                    )
                }
            }
        }
    }

    fun convert() {
        val current = _state.value
        val uri = current.selectedUri ?: return
        if (current.isConverting || current.isInspecting) return

        val pageFrom = current.pageFrom.toIntOrNull() ?: 1
        val pageTo = current.pageTo.toIntOrNull() ?: current.pageCount
        if (current.pageCount > 0 && (pageFrom < 1 || pageTo > current.pageCount || pageFrom > pageTo)) {
            _state.update { it.copy(error = "Enter a valid page range between 1 and ${current.pageCount}.") }
            return
        }

        convertJob?.cancel()
        convertJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isConverting = true,
                    progressCurrent = 0,
                    progressTotal = 0,
                    error = null,
                    result = null,
                    status = "Converting…"
                )
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    PdfConverter.convert(
                        context = getApplication(),
                        inputUri = uri,
                        outputName = current.fileName.ifBlank { "converted" },
                        options = ConversionOptions(
                            format = current.format,
                            alsoJpegPages = current.format == OutputFormat.TIFF && current.alsoJpegPages,
                            dpi = current.dpi,
                            jpegQuality = current.jpegQuality,
                            pageFrom = pageFrom,
                            pageTo = pageTo
                        ),
                        onProgress = { currentPage, total ->
                            ensureActive()
                            _state.update {
                                it.copy(
                                    progressCurrent = currentPage,
                                    progressTotal = total,
                                    status = "Rendering page $currentPage of $total"
                                )
                            }
                        }
                    )
                }
                _state.update {
                    it.copy(
                        isConverting = false,
                        result = result,
                        status = "Created ${result.createdFiles.size} file${if (result.createdFiles.size == 1) "" else "s"} from ${result.pageCount} page${if (result.pageCount == 1) "" else "s"}."
                    )
                }
            } catch (error: CancellationException) {
                _state.update {
                    it.copy(isConverting = false, status = "Conversion cancelled.")
                }
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isConverting = false,
                        error = error.message ?: "Conversion failed.",
                        status = "Conversion failed."
                    )
                }
            }
        }
    }

    fun cancelConversion() {
        convertJob?.cancel()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

}
