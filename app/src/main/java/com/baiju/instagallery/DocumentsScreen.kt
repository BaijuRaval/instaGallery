package com.baiju.instagallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ClipData
import android.media.MediaScannerConnection
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

private data class BrowserDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
    val absolutePath: String = ""
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val displayPath: String get() = if (relativePath.isBlank()) name else "$relativePath/$name"
}

private val supportedDocumentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "tsv", "rtf",
    "md", "log", "html", "htm", "xml", "json", "yaml", "yml", "odt", "ods", "odp"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentsScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onBack: () -> Unit,
    initialViewMode: String = "all",
    onDocumentOpenChanged: (Boolean) -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onSwipeModeChange: (swipeLeft: Boolean) -> Unit = {},
    mediaGridColumns: Int = 3,
    folderGridColumns: Int = 2,
    mediaGridState: LazyGridState,
    folderGridState: LazyGridState,
    onMediaGridColumnsChange: (Int) -> Unit = {},
    onFolderGridColumnsChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    val scope = rememberCoroutineScope()
    var documents by remember { mutableStateOf(emptyList<BrowserDocument>()) }
    var loading by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf(initialViewMode) }
    var currentFolder by remember { mutableStateOf("") }
    var openedDocument by remember { mutableStateOf<BrowserDocument?>(null) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var selectedFolderPath by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameFolderMode by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var isActionRunning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialViewMode) {
        viewMode = initialViewMode
        currentFolder = ""
        selectedUris = emptySet()
        selectedFolderPath = null
    }
    LaunchedEffect(openedDocument) { onDocumentOpenChanged(openedDocument != null) }
    LaunchedEffect(hasAccess, scanRevision) {
        if (!hasAccess) {
            documents = emptyList()
            loading = false
            scanError = null
        } else {
            loading = true
            scanError = null
            val result = withContext(Dispatchers.IO) { scanDocuments(context) }
            documents = result.first
            scanError = result.second
            loading = false
        }
    }

    val allFiles = documents.filter { !it.isDirectory }.sortedBy { it.displayPath.lowercase() }
    val childFolders = documents.filter {
        it.isDirectory && it.relativePath.substringBeforeLast('/', "") == currentFolder
    }.sortedBy { it.name.lowercase() }
    val currentFiles = documents.filter { !it.isDirectory && it.relativePath == currentFolder }
        .sortedBy { it.name.lowercase() }
    val selectableDocuments = if (viewMode == "all") allFiles else currentFiles
    val selectedDocuments = documents.filter { !it.isDirectory && it.uri in selectedUris }
    val allVisibleSelected = selectableDocuments.isNotEmpty() && selectableDocuments.all { it.uri in selectedUris }

    fun fileForDocument(document: BrowserDocument): File {
        if (document.absolutePath.isNotBlank()) {
            val pathFile = File(document.absolutePath)
            if (pathFile.exists()) return pathFile
        }
        val relative = listOf(document.relativePath.trim('/'), document.name)
            .filter { it.isNotBlank() }.joinToString("/")
        return File(storageRoot, relative)
    }
    fun directoryForRelativePath(path: String): File {
        if (path.isBlank()) return storageRoot
        val descendant = documents.firstOrNull {
            !it.isDirectory && (it.relativePath == path || it.relativePath.startsWith("$"+"{path}/"))
        } ?: return File(storageRoot, path)
        var folder = fileForDocument(descendant).parentFile
        var cursor = descendant.relativePath
        while (cursor != path && cursor.isNotBlank()) {
            folder = folder?.parentFile
            cursor = cursor.substringBeforeLast('/', "")
        }
        return folder ?: File(storageRoot, path)
    }
    fun cleanFileName(value: String) = value.trim().replace("/", "_").replace("\\", "_").trimEnd('.', ' ')
    fun scanChangedPaths(vararg paths: String) {
        val validPaths = paths.filter { it.isNotBlank() }.distinct().toTypedArray()
        if (validPaths.isNotEmpty()) MediaScannerConnection.scanFile(context, validPaths, null, null)
    }

    fun shareSelectedDocuments() {
        val targets = selectedDocuments
        if (targets.isEmpty()) return
        try {
            val uris = ArrayList(targets.map { it.uri })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val first = uris.firstOrNull()
                if (first != null) {
                    val sharedItems = ClipData.newUri(context.contentResolver, "Documents", first)
                    uris.drop(1).forEach { sharedItems.addItem(ClipData.Item(it)) }
                    clipData = sharedItems
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share documents"))
        } catch (e: Exception) {
            statusMessage = "Could not share selected documents. " + (e.message ?: "")
        }
    }

    fun moveOrCopyDocuments(destinationPath: String) {
        val mode = transferMode ?: return
        val targets = selectedDocuments
        transferMode = null
        isActionRunning = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var succeeded = 0
                val errors = mutableListOf<String>()
                val destination = directoryForRelativePath(destinationPath)
                if (!destination.exists() && !destination.mkdirs()) {
                    errors.add("The destination folder could not be created.")
                } else {
                    targets.forEach { document ->
                        try {
                            val source = fileForDocument(document)
                            if (!source.isFile) errors.add(document.name + ": original file not found.")
                            else if (source.canonicalFile == destination.canonicalFile) {
                                errors.add(document.name + ": source and destination are the same folder.")
                            } else {
                                var target = File(destination, source.name)
                                if (target.exists()) {
                                    val base = source.name.substringBeforeLast('.', source.name)
                                    val extension = source.name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$"+"{it}" }
                                    var copyIndex = 1
                                    do {
                                        target = File(destination, "$"+"{base} (copy $copyIndex)$"+"{extension}")
                                        copyIndex++
                                    } while (target.exists() && copyIndex < 10000)
                                }
                                source.inputStream().buffered().use { input ->
                                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                                }
                                if (mode == "Move" && !source.delete()) {
                                    target.delete()
                                    errors.add(document.name + ": copied but original could not be removed.")
                                } else {
                                    succeeded++
                                    scanChangedPaths(source.absolutePath, target.absolutePath)
                                }
                            }
                        } catch (e: Exception) {
                            errors.add(document.name + ": " + (e.message ?: "operation failed"))
                        }
                    }
                }
                succeeded to errors
            }
            isActionRunning = false
            selectedUris = emptySet()
            scanRevision++
            statusMessage = buildString {
                append("$"+"{mode} completed for $"+"{outcome.first} of $"+"{targets.size} document(s).")
                if (outcome.second.isNotEmpty()) {
                    append("\n\n")
                    append(outcome.second.take(4).joinToString("\n"))
                    if (outcome.second.size > 4) append("\nAnd $"+"{outcome.second.size - 4} more issue(s).")
                }
            }
        }
    }

    fun renameSelectedItems() {
        val targets = selectedDocuments
        val folderPath = selectedFolderPath
        if (renameFolderMode && folderPath != null) {
            val clean = cleanFileName(renameText)
            if (clean.isBlank()) { statusMessage = "Enter a valid folder name."; return }
            showRename = false
            isActionRunning = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val source = directoryForRelativePath(folderPath)
                        val destination = File(source.parentFile ?: storageRoot, clean)
                        when {
                            !source.isDirectory -> "Source folder could not be found."
                            destination.exists() -> "A folder with that name already exists."
                            !source.renameTo(destination) -> "Android could not rename this folder."
                            else -> {
                                scanChangedPaths(source.absolutePath, destination.absolutePath)
                                "Folder renamed to $clean."
                            }
                        }
                    } catch (e: Exception) { "Could not rename folder: " + (e.message ?: "unknown error") }
                }
                isActionRunning = false
                selectedFolderPath = null
                scanRevision++
                statusMessage = result
            }
            return
        }
        if (targets.isEmpty()) return
        val baseName = cleanFileName(renameText)
        if (baseName.isBlank()) { statusMessage = "Enter a valid file name."; return }
        showRename = false
        isActionRunning = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var renamed = 0
                val errors = mutableListOf<String>()
                targets.forEachIndexed { index, document ->
                    try {
                        val source = fileForDocument(document)
                        val extension = source.name.substringAfterLast('.', "").let {
                            if (source.name.lastIndexOf('.') > 0 && it.isNotBlank()) ".$"+"{it}" else ""
                        }
                        val requestedName = if (targets.size == 1) {
                            if (extension.isNotBlank() && baseName.endsWith(extension, ignoreCase = true)) baseName
                            else baseName + extension
                        } else "$"+"{baseName} ($"+"{index + 1})$"+"{extension}"
                        val destination = File(source.parentFile ?: storageRoot, requestedName)
                        when {
                            !source.isFile -> errors.add(source.name + ": file not found.")
                            source.absolutePath == destination.absolutePath -> renamed++
                            destination.exists() -> errors.add(requestedName + " already exists.")
                            !source.renameTo(destination) -> errors.add(source.name + ": rename failed.")
                            else -> { renamed++; scanChangedPaths(source.absolutePath, destination.absolutePath) }
                        }
                    } catch (e: Exception) { errors.add(document.name + ": " + (e.message ?: "rename failed")) }
                }
                renamed to errors
            }
            isActionRunning = false
            selectedUris = emptySet()
            scanRevision++
            statusMessage = buildString {
                append("Renamed $"+"{outcome.first} of $"+"{targets.size} document(s).")
                if (outcome.second.isNotEmpty()) {
                    append("\n\n")
                    append(outcome.second.take(4).joinToString("\n"))
                    if (outcome.second.size > 4) append("\nAnd $"+"{outcome.second.size - 4} more issue(s).")
                }
            }
        }
    }

    BackHandler(enabled = selectedUris.isNotEmpty() || selectedFolderPath != null ||
        openedDocument != null || currentFolder.isNotBlank()) {
        when {
            selectedUris.isNotEmpty() -> selectedUris = emptySet()
            selectedFolderPath != null -> selectedFolderPath = null
            openedDocument != null -> openedDocument = null
            currentFolder.isNotBlank() -> currentFolder = currentFolder.substringBeforeLast('/', "")
        }
    }

    val opened = openedDocument
    if (opened != null) {
        BuiltInDocumentReader(opened) {
            openedDocument = null
            selectedUris = emptySet()
            selectedFolderPath = null
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding()
            .pointerInput(viewMode, currentFolder, hasAccess, selectedUris, selectedFolderPath) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var deltaX = 0f
                    var deltaY = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.count { it.pressed } >= 2) multiTouch = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        deltaX = change.position.x - down.position.x
                        deltaY = change.position.y - down.position.y
                        if (!change.pressed) {
                            val threshold = maxOf(viewConfiguration.touchSlop * 1.8f, 48.dp.toPx())
                            if (!multiTouch && selectedUris.isEmpty() && selectedFolderPath == null &&
                                kotlin.math.abs(deltaX) >= threshold &&
                                kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.4f &&
                                currentFolder.isBlank() && hasAccess
                            ) onSwipeModeChange(deltaX < 0f)
                            break
                        }
                    }
                }
            }
    ) {
        when {
            selectedUris.isNotEmpty() -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedUris = emptySet() }) { Icon(Icons.Default.Close, "Exit selection", tint = Color.White) }
                    TextButton(
                        onClick = { selectedUris = if (allVisibleSelected) emptySet() else selectableDocuments.map { it.uri }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$"+"{selectedUris.size}/$"+"{selectableDocuments.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(if (allVisibleSelected) "Deselect all" else "Select all", fontSize = 12.sp, color = Color.LightGray)
                        }
                    }
                }
            }
            selectedFolderPath != null -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedFolderPath = null }) { Icon(Icons.Default.Close, "Clear folder selection", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(selectedFolderPath!!.substringAfterLast('/'), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val count = documents.count { !it.isDirectory && (it.relativePath == selectedFolderPath || it.relativePath.startsWith("$"+"{selectedFolderPath}/")) }
                        Text("Folder selected • $count documents", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }
            else -> GalleryHeader(
                title = when {
                    currentFolder.isNotBlank() -> currentFolder.substringAfterLast('/')
                    viewMode == "folders" -> "Folder"
                    else -> "All"
                },
                subtitle = when {
                    !hasAccess -> "Automatic document scan"
                    viewMode == "folders" && currentFolder.isNotBlank() -> "$"+"{currentFiles.size} documents in folder"
                    viewMode == "folders" -> "$"+"{childFolders.size} folders"
                    else -> "$"+"{allFiles.size} documents"
                },
                onOpenMenu = onOpenMenu
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!hasAccess) {
                Box(Modifier.fillMaxSize().padding(26.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(62.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Scan documents automatically", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("No folder picker. Allow instaGallery to access files once in Android Settings; then PDF, Word, Excel, PowerPoint and text documents will be scanned automatically.",
                            color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onRequestAccess) { Text("Allow file access") }
                    }
                }
            } else when {
                loading && documents.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF4D7D))
                }
                scanError != null && documents.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(scanError.orEmpty(), color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scanRevision++ }) { Text("Try again") }
                    }
                }
                else -> {
                    val activeColumns = if (viewMode == "all") mediaGridColumns else folderGridColumns
                    val gridModifier = Modifier.fillMaxSize().pointerInput(viewMode, mediaGridColumns, folderGridColumns) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var accumulatedZoom = 1f
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointers = event.changes.filter { it.pressed }
                                if (pointers.isEmpty()) break
                                if (pointers.size >= 2) {
                                    val previousDistance = (pointers[0].previousPosition - pointers[1].previousPosition).getDistance()
                                    val currentDistance = (pointers[0].position - pointers[1].position).getDistance()
                                    if (previousDistance > 0.5f) {
                                        accumulatedZoom *= currentDistance / previousDistance
                                        if (accumulatedZoom > 1.16f) {
                                            if (viewMode == "all") onMediaGridColumnsChange((mediaGridColumns - 1).coerceAtLeast(2))
                                            else onFolderGridColumnsChange((folderGridColumns - 1).coerceAtLeast(1))
                                            accumulatedZoom = 1f
                                        } else if (accumulatedZoom < 0.86f) {
                                            if (viewMode == "all") onMediaGridColumnsChange((mediaGridColumns + 1).coerceAtMost(6))
                                            else onFolderGridColumnsChange((folderGridColumns + 1).coerceAtMost(4))
                                            accumulatedZoom = 1f
                                        }
                                    }
                                    pointers.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    val entries = if (currentFolder.isBlank()) childFolders else childFolders + currentFiles
                    val showFolders = viewMode == "folders"
                    val empty = if (showFolders) entries.isEmpty() else allFiles.isEmpty()
                    if (empty) {
                        EmptyDocumentsMessage(when {
                            showFolders && currentFolder.isBlank() -> "No document folders found."
                            showFolders -> "This folder has no subfolders or supported documents."
                            else -> "No supported documents found on this device."
                        })
                    } else {
                        PullToRefreshBox(isRefreshing = loading, onRefresh = { scanRevision++ }, modifier = Modifier.fillMaxSize()) {
                            if (showFolders) {
                                LazyVerticalGrid(
                                    state = folderGridState, columns = GridCells.Fixed(activeColumns), modifier = gridModifier,
                                    contentPadding = PaddingValues(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(entries, key = { it.uri.toString() }) { entry ->
                                        val isSelected = if (entry.isDirectory) selectedFolderPath == entry.relativePath else entry.uri in selectedUris
                                        DocumentTile(entry, isSelected, false,
                                            onClick = {
                                                if (entry.isDirectory) {
                                                    if (selectedUris.isEmpty() && selectedFolderPath == null) currentFolder = entry.relativePath
                                                } else when {
                                                    selectedFolderPath != null -> Unit
                                                    selectedUris.isNotEmpty() -> selectedUris = if (entry.uri in selectedUris) selectedUris - entry.uri else selectedUris + entry.uri
                                                    else -> openedDocument = entry
                                                }
                                            },
                                            onLongClick = {
                                                if (entry.isDirectory) {
                                                    selectedUris = emptySet()
                                                    selectedFolderPath = entry.relativePath
                                                } else {
                                                    selectedFolderPath = null
                                                    selectedUris = if (entry.uri in selectedUris) selectedUris - entry.uri else selectedUris + entry.uri
                                                }
                                            }
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    state = mediaGridState, columns = GridCells.Fixed(activeColumns), modifier = gridModifier,
                                    contentPadding = PaddingValues(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(allFiles, key = { it.uri.toString() }) { doc ->
                                        DocumentTile(doc, doc.uri in selectedUris, false,
                                            onClick = {
                                                if (selectedUris.isNotEmpty()) selectedUris = if (doc.uri in selectedUris) selectedUris - doc.uri else selectedUris + doc.uri
                                                else openedDocument = doc
                                            },
                                            onLongClick = {
                                                selectedUris = if (doc.uri in selectedUris) selectedUris - doc.uri else selectedUris + doc.uri
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        when {
            selectedUris.isNotEmpty() -> Row(
                Modifier.fillMaxWidth().background(Color(0xFF171717)).padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                DocumentBottomAction("Details", Icons.Default.Info, Modifier.weight(1f)) { showDetails = true }
                DocumentBottomAction("Rename", Icons.Default.Edit, Modifier.weight(1f)) {
                    renameFolderMode = false
                    renameText = selectedDocuments.singleOrNull()?.name?.substringBeforeLast('.') ?: ""
                    showRename = true
                }
                DocumentBottomAction("Copy", Icons.Default.ContentCopy, Modifier.weight(1f)) { transferMode = "Copy" }
                DocumentBottomAction("Move", Icons.Default.DriveFileMove, Modifier.weight(1f)) { transferMode = "Move" }
                DocumentBottomAction("Share", Icons.Default.Share, Modifier.weight(1f)) { shareSelectedDocuments() }
            }
            selectedFolderPath != null -> Row(
                Modifier.fillMaxWidth().background(Color(0xFF171717)).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                DocumentBottomAction("Details", Icons.Default.Info, Modifier.weight(1f)) { showDetails = true }
                DocumentBottomAction("Rename", Icons.Default.Edit, Modifier.weight(1f)) {
                    renameFolderMode = true
                    renameText = selectedFolderPath!!.substringAfterLast('/')
                    showRename = true
                }
            }
            else -> Spacer(Modifier.windowInsetsBottomHeight(androidx.compose.foundation.layout.WindowInsets.navigationBars))
        }
    }

    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(if (selectedFolderPath != null) "Folder details" else "Document details") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (selectedFolderPath != null) {
                    val folder = selectedFolderPath!!
                    Text("Name: " + folder.substringAfterLast('/'))
                    Text("Documents: " + documents.count { !it.isDirectory && (it.relativePath == folder || it.relativePath.startsWith("$"+"{folder}/")) })
                    Text("Location: $folder")
                } else if (selectedDocuments.size == 1) {
                    val doc = selectedDocuments.first()
                    Text("Name: $"+"{doc.name}")
                    Text("Type: $"+"{doc.mimeType}")
                    Text("Folder: " + doc.relativePath.ifBlank { "Internal storage" })
                    Text("Size: $"+"{doc.size / 1024} KB")
                    Text("Modified: " + if (doc.modified > 0) DateFormat.getDateTimeInstance().format(Date(doc.modified * 1000)) else "Unknown")
                    Text("Location: $"+"{doc.displayPath}", fontSize = 11.sp)
                } else {
                    Text("Documents: $"+"{selectedDocuments.size}")
                    Text("Total size: $"+"{selectedDocuments.sumOf { it.size } / 1024} KB")
                    Text("Selected files remain on your device until you choose an action.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Done") } }
    )

    if (showRename) AlertDialog(
        onDismissRequest = { showRename = false },
        title = { Text(if (renameFolderMode) "Rename folder" else if (selectedDocuments.size > 1) "Rename $"+"{selectedDocuments.size} documents" else "Rename document") },
        text = {
            OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                label = { Text(if (selectedDocuments.size > 1 && !renameFolderMode) "Base name (numbered suffixes added)" else "Name") })
        },
        confirmButton = { TextButton(onClick = { renameSelectedItems() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
    )

    transferMode?.let { mode ->
        val destinations = listOf("") + documents.filter { it.isDirectory }.map { it.relativePath }.distinct().sorted()
        AlertDialog(
            onDismissRequest = { if (!isActionRunning) transferMode = null },
            title = { Text("$"+"{mode} documents to") },
            text = {
                if (isActionRunning) CircularProgressIndicator(color = Color(0xFFFF4D7D))
                else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    destinations.forEach { path ->
                        TextButton(onClick = { moveOrCopyDocuments(path) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (path.isBlank()) "Internal storage (root)" else path, modifier = Modifier.fillMaxWidth(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { transferMode = null }, enabled = !isActionRunning) { Text("Cancel") } }
        )
    }
    if (isActionRunning) AlertDialog(
        onDismissRequest = {},
        title = { Text("Working…") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text("Updating your documents. Please keep instaGallery open.")
        } },
        confirmButton = {}
    )
    statusMessage?.let { message ->
        AlertDialog(onDismissRequest = { statusMessage = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { statusMessage = null }) { Text("OK") } })
    }
}

@Composable
private fun EmptyDocumentsMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.LightGray)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentTile(
    document: BrowserDocument,
    selected: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val icon = when {
        document.isDirectory -> Icons.Default.Folder
        document.extension == "pdf" -> Icons.Default.PictureAsPdf
        document.extension in setOf("xls", "xlsx", "ods", "csv", "tsv") -> Icons.Default.TableChart
        document.extension in setOf("ppt", "pptx", "odp") -> Icons.Default.Slideshow
        else -> Icons.Default.Description
    }
    val tint = when {
        document.isDirectory -> Color(0xFFFF4D7D)
        document.extension == "pdf" -> Color(0xFFFF6B6B)
        document.extension in setOf("xls", "xlsx", "ods", "csv", "tsv") -> Color(0xFF69C58A)
        document.extension in setOf("ppt", "pptx", "odp") -> Color(0xFFFFA45B)
        else -> Color(0xFF7AB7FF)
    }
    Column(
        Modifier.fillMaxWidth()
            .border(2.dp, if (selected) Color(0xFFFF4D7D) else Color.Transparent, MaterialTheme.shapes.medium)
            .background(Color(0xFF191919), MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(48.dp))
            if (selected) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Surface(Modifier.padding(5.dp).size(23.dp), color = Color(0xFFFF4D7D), shape = CircleShape) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(document.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (document.isDirectory) "Folder" else document.extension.uppercase().ifBlank { "FILE" },
                color = Color.LightGray, fontSize = 11.sp)
            if (showPath && document.relativePath.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(document.relativePath, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DocumentBottomAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

private fun scanDocuments(context: Context): Pair<List<BrowserDocument>, String?> {
    return try {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        val projection = (listOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            pathColumn
        ) + if (pathColumn == MediaStore.MediaColumns.DATA) emptyList() else listOf(MediaStore.MediaColumns.DATA)).distinct().toTypedArray()
        val files = mutableListOf<BrowserDocument>()
        // Build the folder tree from every indexed file, not only supported documents.
        // This keeps Doc > Folder visible like Media > Folder even when a directory
        // contains photos, videos, archives, or other non-document files as well.
        val folderPaths = linkedSetOf<String>()
        val collection = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndex(pathColumn)
            val absolutePathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (idIndex < 0 || nameIndex < 0 || pathIndex < 0) {
                return emptyList<BrowserDocument>() to "Android did not provide document metadata."
            }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val rawPath = if (cursor.isNull(pathIndex)) "" else cursor.getString(pathIndex).orEmpty()
                val relativePath = normalizeDocumentPath(
                    context,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawPath else rawPath.substringBeforeLast('/')
                )
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in supportedDocumentExtensions) continue

                // Only create folders that contain supported documents (or their parents).
                val segments = relativePath.split('/').filter { it.isNotBlank() }
                var folderPath = ""
                segments.forEach { segment ->
                    folderPath = if (folderPath.isBlank()) segment else "$folderPath/$segment"
                    folderPaths.add(folderPath)
                }
                val id = cursor.getLong(idIndex)
                files.add(
                    BrowserDocument(
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        name = name,
                        mimeType = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex)
                            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream",
                        relativePath = relativePath,
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                        modified = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) cursor.getLong(dateIndex) else 0L,
                        isDirectory = false,
                        absolutePath = if (absolutePathIndex >= 0 && !cursor.isNull(absolutePathIndex))
                            cursor.getString(absolutePathIndex).orEmpty()
                        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) rawPath else ""
                    )
                )
            }
        } ?: return emptyList<BrowserDocument>() to "Android could not scan the device's shared storage."
        val folders = folderPaths.associateWithTo(linkedMapOf()) { path ->
            BrowserDocument(
                uri = Uri.parse("instagallery-folder:" + Uri.encode(path)),
                name = path.substringAfterLast('/'),
                mimeType = "inode/directory",
                relativePath = path,
                size = 0L,
                modified = 0L,
                isDirectory = true
            )
        }
        (folders.values.toList() + files) to null
    } catch (e: SecurityException) {
        emptyList<BrowserDocument>() to "Android blocked document access. Allow All files access in Android Settings, then try again."
    } catch (e: Exception) {
        emptyList<BrowserDocument>() to ("Could not scan documents: " + (e.message ?: "unknown error"))
    }
}

private fun normalizeDocumentPath(context: Context, rawPath: String): String {
    val normalized = rawPath.replace('\\', '/').trim('/')
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return normalized
    val root = Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/').trimEnd('/')
    return normalized.removePrefix(root).trim('/')
}

@Composable
private fun BuiltInDocumentReader(document: BrowserDocument, onBack: () -> Unit) {
    val context = LocalContext.current
    val originalViewerExtensions = setOf(
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "html", "htm"
    )
    val requiresOriginalViewer = document.extension in originalViewerExtensions
    var openError by remember(document.uri) { mutableStateOf<String?>(null) }

    // The file itself is the screen: no in-app title bar, toolbar or status/navigation bars.
    DisposableEffect(document.uri) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    LaunchedEffect(document.uri) {
        if (requiresOriginalViewer) {
            val mime = when (document.extension) {
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "odt" -> "application/vnd.oasis.opendocument.text"
                "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
                "odp" -> "application/vnd.oasis.opendocument.presentation"
                "rtf" -> "application/rtf"
                "html", "htm" -> "text/html"
                else -> document.mimeType
            }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(document.uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                if (context.packageManager.resolveActivity(intent, 0) == null) {
                    openError = "No compatible app is installed to open this original document."
                } else {
                    context.startActivity(intent)
                    onBack()
                }
            } catch (e: Exception) {
                openError = "Could not open the original file. Install a compatible document viewer and try again."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        when {
            requiresOriginalViewer -> {
                if (openError != null) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(openError.orEmpty(), color = Color.White)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFF4D7D))
                    }
                }
            }
            document.extension == "pdf" -> PdfDocumentReader(document.uri)
            else -> TextBasedDocumentReader(document)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private data class PdfDocumentInfo(val pageCount: Int, val error: String?)

@Composable
private fun PdfDocumentReader(uri: Uri) {
    val context = LocalContext.current
    var pageCount by remember(uri) { mutableIntStateOf(0) }
    var loading by remember(uri) { mutableStateOf(true) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    val latestZoom = rememberUpdatedState(zoom)
    val latestZoomChange = rememberUpdatedState<(Float) -> Unit>({ newZoom -> zoom = newZoom })
    val pdfListState = rememberLazyListState()
    val pdfHorizontalScrollState = rememberScrollState()

    LaunchedEffect(uri) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Could not open this PDF.")
                fd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount <= 0) throw IllegalStateException("This PDF has no readable pages.")
                        PdfDocumentInfo(renderer.pageCount, null)
                    }
                }
            } catch (e: Exception) {
                PdfDocumentInfo(0, e.message ?: "Could not open this PDF.")
            }
        }
        pageCount = result.pageCount
        error = result.error
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFF4D7D))
        }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), color = Color.White)
        }
        else -> Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var previousDistance = 0f
                    var gestureZoom = latestZoom.value
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.isEmpty()) break
                        if (pointers.size >= 2) {
                            val first = pointers[0]
                            val second = pointers[1]
                            val distance = (first.position - second.position).getDistance()
                            if (previousDistance > 0.5f && distance > 0.5f) {
                                val ratio = distance / previousDistance
                                if (kotlin.math.abs(ratio - 1f) > 0.001f) {
                                    gestureZoom = (gestureZoom * ratio).coerceIn(0.5f, 4f)
                                    latestZoomChange.value(gestureZoom)
                                }
                            }
                            previousDistance = distance
                            pointers.forEach { it.consume() }
                        } else {
                            previousDistance = 0f
                        }
                    }
                }
            }
        ) {
            // One horizontally scrollable canvas wraps the entire vertical page list.
            // Zoom and horizontal panning are therefore shared by every page, not per-page.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val documentWidth = maxWidth * zoom
                Row(
                    Modifier.fillMaxSize().horizontalScroll(pdfHorizontalScrollState),
                    horizontalArrangement = Arrangement.Start
                ) {
                    LazyColumn(
                        state = pdfListState,
                        modifier = Modifier.width(documentWidth).fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        items((0 until pageCount).toList(), key = { it }) { pageIndex ->
                            PdfPageContent(uri = uri, pageIndex = pageIndex)
                        }
                    }
                }
            }
            DocumentZoomControls(
                zoom = zoom,
                onZoomChange = { zoom = it.coerceIn(0.5f, 4f) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).zIndex(3f)
            )
        }
    }
}

@Composable
private fun PdfPageContent(
    uri: Uri,
    pageIndex: Int
) {
    val context = LocalContext.current
    var bitmap by remember(uri, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri, pageIndex) { mutableStateOf<String?>(null) }
    var loading by remember(uri, pageIndex) { mutableStateOf(true) }
    LaunchedEffect(uri, pageIndex) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Could not open PDF page.")
                fd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            // Render with full color; do not recolor or modify the source file.
                            val scale = minOf(2.0f, 1800f / maxOf(page.width, page.height).toFloat())
                            val image = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not render page ${pageIndex + 1}."
                null
            }
        }
        bitmap = result
        loading = false
    }

    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "PDF page ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)
                    .background(Color.White)
            )
            loading -> Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color(0xFFFF4D7D)) }
            error != null -> Text(
                error.orEmpty(),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(18.dp)
            )
        }
    }
}

@Composable
private fun DocumentZoomControls(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xF5000000),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(2.dp, Color(0xFFFF4D7D)),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                onClick = { onZoomChange((zoom * 1.2f).coerceAtMost(4f)) },
                modifier = Modifier.size(58.dp),
                color = Color(0xFFFF4D7D),
                shape = CircleShape,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "${(zoom * 100).toInt()}%",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            Surface(
                onClick = { onZoomChange((zoom / 1.2f).coerceAtLeast(0.5f)) },
                modifier = Modifier.size(58.dp),
                color = Color.White,
                shape = CircleShape,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("−", color = Color(0xFF161616), fontSize = 42.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TextBasedDocumentReader(document: BrowserDocument) {
    val context = LocalContext.current
    var content by remember(document.uri) { mutableStateOf<String?>(null) }
    var error by remember(document.uri) { mutableStateOf<String?>(null) }
    var loading by remember(document.uri) { mutableStateOf(true) }
    var zoom by remember(document.uri) { mutableFloatStateOf(1f) }
    LaunchedEffect(document.uri) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try { readDocumentText(context, document) }
            catch (e: Exception) { null to (e.message ?: "Could not read this document.") }
        }
        content = result.first
        error = result.second
        loading = false
    }
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFFF4D7D))
        }
        content != null -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text(
                    content.orEmpty(),
                    color = Color(0xFFEDEDED),
                    fontSize = 14.sp * zoom,
                    lineHeight = 21.sp * zoom
                )
            }
            DocumentZoomControls(
                zoom = zoom,
                onZoomChange = { zoom = it.coerceIn(0.5f, 4f) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
            )
        }
        else -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This file could not be rendered.", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(error ?: "The file may be damaged or use an unsupported format.", color = Color.LightGray)
                if (document.extension in setOf("doc", "xls", "ppt")) {
                    Spacer(Modifier.height(10.dp))
                    Text("This older binary Office format is not supported by the built-in reader. Save it as DOCX, XLSX, PPTX, or PDF.",
                        color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun readDocumentText(context: Context, document: BrowserDocument): Pair<String?, String?> {
    val bytes = context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Could not open this file.")
    if (bytes.isEmpty()) return "" to null
    val ext = document.extension
    if (ext in setOf("txt", "csv", "tsv", "md", "log", "json", "yaml", "yml", "html", "htm", "xml", "rtf")) {
        var text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        when (ext) {
            "html", "htm", "xml" -> text = xmlToReadableText(text)
            "rtf" -> text = text.replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
                .replace(Regex("[{}]"), "").replace("\\\\par", "\n")
        }
        return text to null
    }
    if (ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")) {
        val archive = readZipTextEntries(bytes)
        val output = when (ext) {
            "docx" -> archive["word/document.xml"]?.let { xmlToReadableText(it) }
            "xlsx" -> spreadsheetText(archive)
            "pptx" -> presentationText(archive)
            else -> archive["content.xml"]?.let { xmlToReadableText(it) }
        }
        return if (output.isNullOrBlank()) null to "No readable text was found in this document." else output to null
    }
    if (ext in setOf("doc", "xls", "ppt")) return null to "This older binary Office format is not supported by the built-in parser."
    return null to "This file format is not supported by the built-in reader yet."
}

private fun readZipTextEntries(bytes: ByteArray): Map<String, String> {
    val result = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val size = zip.read(buffer)
                    if (size <= 0) break
                    total += size
                    if (total > 8 * 1024 * 1024) break
                    output.write(buffer, 0, size)
                }
                if (total <= 8 * 1024 * 1024) result[entry.name] = output.toString(StandardCharsets.UTF_8.name())
            }
            zip.closeEntry()
        }
    }
    return result
}

private fun spreadsheetText(archive: Map<String, String>): String {
    val shared = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        .findAll(archive["xl/sharedStrings.xml"].orEmpty()).map { xmlToReadableText(it.groupValues[1]).trim() }.toList()
    val sheets = archive.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { Regex("sheet(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE }
    return sheets.mapIndexed { sheetIndex, name ->
        val rows = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
            .findAll(archive[name].orEmpty()).mapNotNull { row ->
                val cells = Regex("<c\\b([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(row.groupValues[1]).mapNotNull { cell ->
                        val attrs = cell.groupValues[1]
                        val body = cell.groupValues[2]
                        val column = Regex("\\br=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                        val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                        val raw = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                            ?: if (type == "inlineStr") xmlToReadableText(body) else null
                        val value = when {
                            raw == null -> ""
                            type == "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else -> decodeXml(raw)
                        }
                        if (value.isBlank()) null else if (column == null) value else column + ": " + value
                    }.toList()
                cells.takeIf { it.isNotEmpty() }?.joinToString("    ")
            }.toList()
        "Sheet " + (sheetIndex + 1) + "\n" + rows.joinToString("\n")
    }.joinToString("\n\n")
}

private fun presentationText(archive: Map<String, String>): String {
    val slides = archive.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        .sortedBy { Regex("slide(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE }
    return slides.mapIndexed { index, slide ->
        "Slide " + (index + 1) + "\n" + xmlToReadableText(archive[slide].orEmpty())
    }.joinToString("\n\n")
}

private fun xmlToReadableText(xml: String): String {
    return decodeXml(xml.replace(Regex("</(?:w:p|a:p|text:p|p|row|tr)>"), "\n")
        .replace(Regex("<(?:w:tab|tab)\\b[^>]*/>"), "    ")
        .replace(Regex("<[^>]+>"), " "))
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n").trim()
}

private fun decodeXml(input: String): String = input
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
    .replace("&amp;", "&")
