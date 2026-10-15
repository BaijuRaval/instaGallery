package com.baiju.instagallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

private data class BrowserDocument(
    val uri: Uri, val name: String, val mimeType: String, val relativePath: String,
    val size: Long, val modified: Long, val isDirectory: Boolean
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val displayPath: String get() = if (relativePath.isBlank()) name else relativePath + "/" + name
}

private val supportedDocumentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "tsv", "rtf",
    "md", "log", "html", "htm", "xml", "json", "yaml", "yml", "odt", "ods", "odp"
)

@Composable
internal fun DocumentsScreen(treeUri: Uri?, onChooseFolder: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var documents by remember(treeUri) { mutableStateOf(emptyList<BrowserDocument>()) }
    var loading by remember(treeUri) { mutableStateOf(treeUri != null) }
    var scanError by remember(treeUri) { mutableStateOf<String?>(null) }
    var viewMode by remember(treeUri) { mutableStateOf("all") }
    var currentFolder by remember(treeUri) { mutableStateOf("") }
    var openedDocument by remember { mutableStateOf<BrowserDocument?>(null) }

    LaunchedEffect(treeUri) {
        documents = emptyList()
        currentFolder = ""
        viewMode = "all"
        scanError = null
        if (treeUri == null) loading = false else {
            loading = true
            val result = withContext(Dispatchers.IO) { scanDocumentTree(context, treeUri) }
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
                if (currentFolder.isNotBlank()) currentFolder = currentFolder.substringBeforeLast('/', "") else onBack()
            }) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (currentFolder.isBlank()) "Documents" else currentFolder.substringAfterLast('/'),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (treeUri == null) "Choose a folder to begin" else documents.count { !it.isDirectory }.toString() + " documents",
                    color = Color.LightGray, fontSize = 12.sp)
            }
            if (treeUri != null) TextButton(onClick = onChooseFolder) { Text("Change folder") }
        }

        if (treeUri == null) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(62.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Choose a documents folder", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Select Documents, Downloads, or another folder. Supported files in it and its subfolders will appear here.",
                        color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onChooseFolder) { Text("Choose folder") }
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
                TextButton(onClick = onChooseFolder) { Text("Open folder") }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF4D7D))
                }
                scanError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(scanError.orEmpty(), color = Color.White)
                        Button(onClick = onChooseFolder) { Text("Choose folder again") }
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
                        if (allFiles.isEmpty()) EmptyDocumentsMessage("No supported documents found in this folder.")
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
                            if (currentFolder.isBlank()) "No folders or supported documents found."
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

private fun scanDocumentTree(context: Context, treeUri: Uri): Pair<List<BrowserDocument>, String?> {
    return try {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return emptyList<BrowserDocument>() to "Could not open the selected folder. Please choose it again."
        if (!root.exists() || !root.canRead()) return emptyList<BrowserDocument>() to
            "The selected folder is no longer accessible. Choose it again."
        val found = mutableListOf<BrowserDocument>()
        fun visit(folder: DocumentFile, path: String, depth: Int) {
            if (depth > 12) return
            val children = try { folder.listFiles().sortedBy { (it.name ?: "").lowercase() } }
                catch (_: Exception) { emptyList() }
            children.forEach { child ->
                val name = child.name ?: return@forEach
                if (child.isDirectory) {
                    val childPath = if (path.isBlank()) name else path + "/" + name
                    found.add(BrowserDocument(child.uri, name, "inode/directory", childPath, 0L, child.lastModified(), true))
                    visit(child, childPath, depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in supportedDocumentExtensions) {
                        val mime = child.type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                        found.add(BrowserDocument(child.uri, name, mime, path, child.length().coerceAtLeast(0L), child.lastModified(), false))
                    }
                }
            }
        }
        visit(root, "", 0)
        found to null
    } catch (_: SecurityException) {
        emptyList<BrowserDocument>() to "Access to this folder was denied. Please choose the folder again."
    } catch (e: Exception) {
        emptyList<BrowserDocument>() to ("Could not read this folder: " + (e.message ?: "unknown error"))
    }
}

@Composable
private fun BuiltInDocumentReader(document: BrowserDocument, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to documents", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(document.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(document.extension.uppercase() + " reader", color = Color.LightGray, fontSize = 11.sp)
            }
        }
        Divider(color = Color(0xFF303030))
        if (document.extension == "pdf") PdfDocumentReader(document.uri) else TextBasedDocumentReader(document)
    }
}

private data class PdfRenderResult(val count: Int, val bitmap: Bitmap?, val error: String?)

@Composable
private fun PdfDocumentReader(uri: Uri) {
    val context = LocalContext.current
    var pageIndex by remember(uri) { mutableIntStateOf(0) }
    var pageCount by remember(uri) { mutableIntStateOf(0) }
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    var error by remember(uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri, pageIndex) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Could not open this PDF.")
                fd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val count = renderer.pageCount
                        if (count <= 0) throw IllegalStateException("This PDF has no readable pages.")
                        renderer.openPage(pageIndex.coerceIn(0, count - 1)).use { page ->
                            val scale = minOf(1.5f, 1500f / maxOf(page.width, page.height).toFloat())
                            val image = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfRenderResult(count, image, null)
                        }
                    }
                }
            } catch (e: Exception) { PdfRenderResult(0, null, e.message ?: "Could not render this PDF.") }
        }
        pageCount = result.count
        bitmap = result.bitmap
        error = result.error
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = pageIndex > 0 && !loading, onClick = { pageIndex-- }) { Text("Previous") }
            Text(if (pageCount > 0) "Page " + (pageIndex + 1) + " of " + pageCount else "PDF",
                color = Color.White, fontSize = 13.sp)
            TextButton(enabled = pageCount > 0 && pageIndex < pageCount - 1 && !loading, onClick = { pageIndex++ }) {
                Text("Next")
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF4D7D))
            }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), color = Color.White)
            }
            bitmap != null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                contentAlignment = Alignment.TopCenter) {
                Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "PDF page " + (pageIndex + 1),
                    modifier = Modifier.fillMaxWidth())
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
