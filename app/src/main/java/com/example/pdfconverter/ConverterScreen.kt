package com.example.pdfconverter

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun ConverterScreen(
    incomingUri: Uri?,
    viewModel: ConversionViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var pendingGalleryResult by remember { mutableStateOf<ConversionResult?>(null) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.selectPdf(uri)
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = FileActions.CreateNamedDocument()
    ) { uri ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        runCatching { FileActions.copyToUri(context, source, uri) }
            .onSuccess { Toast.makeText(context, "Saved ${source.name}", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, it.message ?: "Save failed", Toast.LENGTH_LONG).show() }
    }

    val writeStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val result = pendingGalleryResult
        pendingGalleryResult = null
        if (granted && result != null) {
            saveJpegsToGallery(context, result)
        } else if (!granted) {
            Toast.makeText(context, "Storage permission is required to save to Gallery", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(incomingUri) {
        viewModel.consumeIncomingUri(incomingUri)
    }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearError()
    }

    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.background
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { HeroHeader() }
            item {
                PdfPickerCard(
                    state = state,
                    onPick = { pickPdfLauncher.launch(arrayOf("application/pdf")) }
                )
            }
            item { FormatCard(state = state, viewModel = viewModel) }
            item { OptionsCard(state = state, viewModel = viewModel) }
            item {
                ConvertActions(
                    state = state,
                    onConvert = viewModel::convert,
                    onCancel = viewModel::cancelConversion
                )
            }
            state.result?.let { result ->
                item {
                    ResultHeader(
                        result = result,
                        onShareAll = { FileActions.share(context, result.createdFiles) },
                        onSaveGallery = {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                pendingGalleryResult = result
                                writeStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                saveJpegsToGallery(context, result)
                            }
                        }
                    )
                }
                items(result.createdFiles, key = { it.absolutePath }) { file ->
                    ResultFileRow(
                        file = file,
                        onShare = { FileActions.share(context, listOf(file)) },
                        onOpen = { FileActions.open(context, file) },
                        onSave = {
                            pendingSaveFile = file
                            createDocumentLauncher.launch(FileActions.mimeType(file) to file.name)
                        }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun HeroHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "PDF Convert",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Turn any PDF into crisp TIFF, JPEG, or JPG files. Built for clean scans, sharing, and printing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PdfPickerCard(
    state: ConverterUiState,
    onPick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            .clickable(enabled = !state.isConverting, onClick = onPick)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val preview = state.preview
                if (preview != null && !preview.isRecycled) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "PDF preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.selectedUri == null) "Choose a PDF" else state.fileName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onPick, enabled = !state.isConverting) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.selectedUri == null) "Browse files" else "Replace PDF")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatCard(
    state: ConverterUiState,
    viewModel: ConversionViewModel
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelRow(icon = Icons.Outlined.Tune, title = "Output format")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputFormat.entries.forEach { format ->
                    val selected = state.format == format
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onFormatSelected(format) },
                        label = { Text(format.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            AnimatedVisibility(visible = state.format == OutputFormat.TIFF) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Also export JPG pages", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Creates a JPG for every TIFF page so you can share easily.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.alsoJpegPages,
                        onCheckedChange = viewModel::onAlsoJpegChanged
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(
    state: ConverterUiState,
    viewModel: ConversionViewModel
) {
    val showJpegQuality = state.format != OutputFormat.TIFF || state.alsoJpegPages
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LabelRow(icon = Icons.Outlined.Description, title = "Quality and pages")
            Text("Resolution", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(72, 150, 200, 300).forEach { dpi ->
                    FilterChip(
                        selected = state.dpi == dpi,
                        onClick = { viewModel.onDpiSelected(dpi) },
                        label = { Text("$dpi DPI") }
                    )
                }
            }
            AnimatedVisibility(visible = showJpegQuality) {
                Column {
                    Text("JPEG quality  ${state.jpegQuality}%", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = state.jpegQuality.toFloat(),
                        onValueChange = { viewModel.onQualityChanged(it.toInt()) },
                        valueRange = 50f..100f
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.pageFrom,
                    onValueChange = viewModel::onPageFromChanged,
                    label = { Text("From page") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.pageTo,
                    onValueChange = viewModel::onPageToChanged,
                    label = { Text("To page") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConvertActions(
    state: ConverterUiState,
    onConvert: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onConvert,
            enabled = state.selectedUri != null && !state.isConverting && !state.isInspecting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                if (state.isConverting) "Converting…" else "Convert to ${state.format.label}",
                style = MaterialTheme.typography.titleMedium
            )
        }
        AnimatedVisibility(visible = state.isConverting) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val progress = if (state.progressTotal == 0) {
                    0f
                } else {
                    state.progressCurrent / state.progressTotal.toFloat()
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(
    result: ConversionResult,
    onShareAll: () -> Unit,
    onSaveGallery: () -> Unit
) {
    val hasJpeg = result.createdFiles.any { it.extension.lowercase() in setOf("jpg", "jpeg") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Conversion complete", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShareAll, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share all")
            }
            if (hasJpeg) {
                OutlinedButton(onClick = onSaveGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gallery")
                }
            }
        }
    }
}

@Composable
private fun ResultFileRow(
    file: File,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                FileActions.formatSize(file.length()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
                TextButton(onClick = onOpen) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open")
                }
                TextButton(onClick = onSave) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun LabelRow(
    icon: ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private fun saveJpegsToGallery(
    context: android.content.Context,
    result: ConversionResult
) {
    try {
        val saved = FileActions.saveJpegsToGallery(context, result.createdFiles)
        if (saved == 0) {
            Toast.makeText(context, "No JPEG/JPG files to save", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Saved $saved image(s) to Gallery", Toast.LENGTH_SHORT).show()
        }
    } catch (error: Exception) {
        Toast.makeText(context, error.message ?: "Gallery save failed", Toast.LENGTH_LONG).show()
    }
}
