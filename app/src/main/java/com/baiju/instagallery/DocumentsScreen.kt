package com.baiju.instagallery

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

private data class BrowserDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val displayPath: String get() = if (relativePath.isBlank()) name else "$relativePath/$name"
}

private val supportedDocumentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "tsv", "rtf",
    "md", "log", "html", "htm", "xml", "json", "yaml", "yml", "odt", "ods", "odp"
)

@Composable
internal fun DocumentsScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onBack: () -> Unit,
    initialViewMode: String = "all"
) {
    val context = LocalContext.current
    var documents by remember { mutableStateOf(emptyList<BrowserDocument>()) }
    var loading by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf(initialViewMode) }
    var currentFolder by remember { mutableStateOf("") }
    var openedDocument by remember { mutableStateOf<BrowserDocument?>(null) }

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

    BackHandler(enabled = openedDocument != null || currentFolder.isNotBlank() || viewMode != "all") {
        when {
            openedDocument != null -> openedDocument = null
            currentFolder.isNotBlank() -> currentFolder = currentFolder.substringBeforeLast('/', "")
            viewMode != "all" -> { viewMode = "all"; currentFolder = "" }
        }
    }

    val selectedDocument = openedDocument
    if (selectedDocument != null) {
        BuiltInDocumentReader(selectedDocument) { openedDocument = null }
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (currentFolder.isNotBlank()) currentFolder = currentFolder.substringBeforeLast('/', "")
                else onBack()
            }) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (currentFolder.isBlank()) "Documents" else currentFolder.substringAfterLast('/'),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (!hasAccess) "Automatic document scan" else documents.count { !it.isDirectory }.toString() + " documents",
                    color = Color.LightGray, fontSize = 12.sp)
            }
        }

        if (!hasAccess) {
            Box(Modifier.fillMaxSize().padding(26.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(62.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Scan documents automatically", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No folder picker. Allow instaGallery to access files once in Android Settings; then PDF, Word, Excel, PowerPoint and text documents will be scanned automatically.",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onRequestAccess) { Text("Allow file access") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = viewMode == "all", onClick = { viewMode = "all"; currentFolder = "" },
                    label = { Text("All Documents") })
                FilterChip(selected = viewMode == "folders", onClick = { viewMode = "folders" },
                    label = { Text("Folders") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scanRevision++ }) { Text("Rescan") }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF4D7D))
                }
                scanError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(scanError.orEmpty(), color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scanRevision++ }) { Text("Try again") }
                    }
                }
                else -> {
                    val allFiles = documents.filter { !it.isDirectory }.sortedBy { it.displayPath.lowercase() }
                    val childFolders = documents.filter {
                        it.isDirectory && it.relativePath.substringBeforeLast('/', "") == currentFolder
                    }.sortedBy { it.name.lowercase() }
                    val currentFiles = documents.filter { !it.isDirectory && it.relativePath == currentFolder }
                        .sortedBy { it.name.lowercase() }

                    if (viewMode == "all") {
                        if (allFiles.isEmpty()) EmptyDocumentsMessage("No supported documents found on this device.")
                        else LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(allFiles, key = { it.uri.toString() }) { doc ->
                                DocumentTile(doc, true) { openedDocument = doc }
                            }
                        }
                    } else {
                        val entries = childFolders + currentFiles
                        if (entries.isEmpty()) EmptyDocumentsMessage(
                            if (currentFolder.isBlank()) "No document folders or supported files found."
                            else "This folder has no subfolders or supported documents.")
                        else LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(entries, key = { it.uri.toString() }) { entry ->
                                DocumentTile(entry, false) {
                                    if (entry.isDirectory) currentFolder = entry.relativePath else openedDocument = entry
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDocumentsMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.LightGray)
    }
}

@Composable
private fun DocumentTile(document: BrowserDocument, showPath: Boolean, onClick: () -> Unit) {
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
    Surface(onClick = onClick, color = Color(0xFF191919), shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text(document.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (document.isDirectory) "Folder" else document.extension.uppercase().ifBlank { "FILE" },
                color = Color.LightGray, fontSize = 11.sp)
            if (showPath && document.relativePath.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(document.relativePath, color = Color.Gray, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun scanDocuments(context: Context): Pair<List<BrowserDocument>, String?> {
    return try {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            pathColumn
        )
        val files = mutableListOf<BrowserDocument>()
        val collection = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndex(pathColumn)
            if (idIndex < 0 || nameIndex < 0 || pathIndex < 0) {
                return emptyList<BrowserDocument>() to "Android did not provide document metadata."
            }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in supportedDocumentExtensions) continue
                val rawPath = if (cursor.isNull(pathIndex)) "" else cursor.getString(pathIndex).orEmpty()
                val relativePath = normalizeDocumentPath(
                    context,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawPath else rawPath.substringBeforeLast('/')
                )
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
                        isDirectory = false
                    )
                )
            }
        } ?: return emptyList<BrowserDocument>() to "Android could not scan the device's shared storage."
        val folders = linkedMapOf<String, BrowserDocument>()
        files.forEach { file ->
            val segments = file.relativePath.split('/').filter { it.isNotBlank() }
            var path = ""
            segments.forEach { segment ->
                path = if (path.isBlank()) segment else "$path/$segment"
                folders[path] = BrowserDocument(
                    uri = Uri.parse("instagallery-folder:" + Uri.encode(path)),
                    name = segment,
                    mimeType = "inode/directory",
                    relativePath = path,
                    size = 0L,
                    modified = 0L,
                    isDirectory = true
                )
            }
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

    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to documents", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(document.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (requiresOriginalViewer) "Opening original file" else document.extension.uppercase() + " reader",
                    color = Color.LightGray, fontSize = 11.sp
                )
            }
        }
        Divider(color = Color(0xFF303030))
        when {
            requiresOriginalViewer -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (openError == null) {
                            CircularProgressIndicator(color = Color(0xFFFF4D7D))
                            Spacer(Modifier.height(14.dp))
                            Text("Opening with original formatting…", color = Color.White)
                        } else {
                            Text(openError.orEmpty(), color = Color.White)
                            Spacer(Modifier.height(14.dp))
                            TextButton(onClick = onBack) { Text("Back to documents") }
                        }
                    }
                }
            }
            document.extension == "pdf" -> PdfDocumentReader(document.uri)
            else -> TextBasedDocumentReader(document)
        }
    }
}

private data class PdfDocumentInfo(val pageCount: Int, val error: String?)

@Composable
private fun PdfDocumentReader(uri: Uri) {
    val context = LocalContext.current
    var pageCount by remember(uri) { mutableIntStateOf(0) }
    var loading by remember(uri) { mutableStateOf(true) }
    var error by remember(uri) { mutableStateOf<String?>(null) }

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
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                "$pageCount pages · scroll vertically to read",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items((0 until pageCount).toList(), key = { it }) { pageIndex ->
                    PdfPageContent(uri = uri, pageIndex = pageIndex)
                }
            }
        }
    }
}

@Composable
private fun PdfPageContent(uri: Uri, pageIndex: Int) {
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
                            // Render the original page, retaining its embedded colors and graphics.
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

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "PDF page ${pageIndex + 1}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
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

@Composable
private fun TextBasedDocumentReader(document: BrowserDocument) {
    val context = LocalContext.current
    var content by remember(document.uri) { mutableStateOf<String?>(null) }
    var error by remember(document.uri) { mutableStateOf<String?>(null) }
    var loading by remember(document.uri) { mutableStateOf(true) }
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
        content != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(content.orEmpty(), color = Color(0xFFEDEDED), fontSize = 14.sp, lineHeight = 21.sp)
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
