package com.baiju.instagallery

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.DateFormat
import java.util.Date
import java.security.MessageDigest
import kotlin.math.min

private data class GalleryMedia(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val album: String,
    val dateAdded: Long,
    val size: Long,
    val relativePath: String,
    val durationMs: Long = 0L
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    // Use the 10-second frame for long videos, but never request past the end of a short clip.
    val thumbnailTimeMs: Long get() = if (!isVideo) 0L else if (durationMs <= 0L) 10_000L
        else if (durationMs > 10_000L) 10_000L else (durationMs - 500L).coerceAtLeast(0L)
}
private data class CopyResult(val success: Boolean, val error: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { GalleryApp() }
    }
}

@Composable
private fun GalleryApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var hasPermission by remember { mutableStateOf(context.hasMediaPermission()) }
    var media by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    var selectedAlbum by remember { mutableStateOf("All media") }
    var viewerStartUri by remember { mutableStateOf<Uri?>(null) }
    var tab by remember { mutableStateOf("Reel") }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var hiddenFolders by remember(context) {
        mutableStateOf(context.getSharedPreferences("instagallery_settings", Context.MODE_PRIVATE)
            .getStringSet("hidden_folders", emptySet())?.toSet() ?: emptySet())
    }
    var folderDetailsName by remember { mutableStateOf<String?>(null) }
    var folderRenameTarget by remember { mutableStateOf<String?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var infoItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var renameItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var renameText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var settingsPage by remember { mutableStateOf<String?>(null) }
    var folderRemoveTarget by remember { mutableStateOf<String?>(null) }
    var manageMediaInfoOpen by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var transferItems by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var transferReturnTab by remember { mutableStateOf("Reel") }
    var transferReturnAlbum by remember { mutableStateOf("All media") }
    var pendingTransferMessage by remember { mutableStateOf<String?>(null) }
    val appScope = rememberCoroutineScope()
    val appContext = context.applicationContext
    val thumbnailLoader = remember(appContext) {
        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.22)
                    .build()
            }
            .crossfade(false)
            .build()
    }
    // These states live above the screen switch so opening media/settings doesn't reset scroll.
    val mediaGridState = rememberLazyGridState()
    val folderGridState = rememberLazyGridState()
    var thumbnailRevision by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = context.hasMediaPermission()
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        media = context.queryGalleryMedia()
        selected = emptySet()
        pendingTransferMessage?.let { message ->
            statusMessage = if (result.resultCode == android.app.Activity.RESULT_OK) message else "Files were copied; original files were kept."
            pendingTransferMessage = null
        }
    }

    fun requestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> emptyArray()
        }
        permissionLauncher.launch(permissions)
    }
    fun refresh() {
        appScope.launch {
            val refreshed = withContext(Dispatchers.IO) { context.queryGalleryMedia() }
            media = refreshed
        }
    }
    fun setFolderHidden(folder: String, hidden: Boolean) {
        val updated = if (hidden) hiddenFolders + folder else hiddenFolders - folder
        hiddenFolders = updated
        context.getSharedPreferences("instagallery_settings", Context.MODE_PRIVATE)
            .edit().putStringSet("hidden_folders", updated).apply()
        if (hidden && selectedFolder == folder) selectedFolder = null
        if (hidden && selectedAlbum == folder) selectedAlbum = "All media"
        actionMenu = false
    }
    fun renameFolder(folder: String, proposedName: String) {
        val cleanName = proposedName.trim().trim('/').replace("/", "_").replace("\\", "_")
        if (cleanName.isBlank()) {
            statusMessage = "Enter a valid folder name."
            return
        }
        if (cleanName == folder) {
            folderRenameTarget = null
            return
        }
        val targets = media.filter { it.album == folder }
        var renamed = 0
        targets.forEach { item ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val oldPath = item.relativePath.trimEnd('/')
                    val parentPath = oldPath.substringBeforeLast('/', "")
                    val newPath = if (parentPath.isBlank()) "$cleanName/" else "$parentPath/$cleanName/"
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                    }
                    if (context.contentResolver.update(item.uri, values, null, null) > 0) renamed++
                } else {
                    val source = File(item.relativePath, item.name)
                    val oldDirectory = File(item.relativePath.trimEnd(File.separatorChar))
                    val targetDirectory = File(oldDirectory.parentFile, cleanName)
                    if (!targetDirectory.exists()) targetDirectory.mkdirs()
                    val target = File(targetDirectory, item.name)
                    if (source.exists() && !target.exists() && source.renameTo(target)) {
                        MediaScannerConnection.scanFile(context,
                            arrayOf(source.absolutePath, target.absolutePath),
                            arrayOf(item.mimeType, item.mimeType), null)
                        renamed++
                    }
                }
            } catch (_: Exception) { }
        }
        folderRenameTarget = null
        selectedFolder = null
        refresh()
        statusMessage = if (renamed == targets.size && targets.isNotEmpty()) {
            "Folder renamed to $cleanName."
        } else if (renamed > 0) {
            "Renamed $renamed of ${targets.size} files. Check the folder contents."
        } else {
            "Android couldn't rename this folder. Check media permissions and try again."
        }
    }
    fun beginTransfer(mode: String) {
        val targets = media.filter { it.uri in selected }
        if (targets.isEmpty()) return
        transferItems = targets
        transferMode = mode
        transferReturnTab = tab
        transferReturnAlbum = selectedAlbum
        selected = emptySet()
        actionMenu = false
        tab = "Folders"
        selectedAlbum = "All media"
    }
    fun cancelTransfer() {
        transferMode = null
        transferItems = emptyList()
        tab = transferReturnTab
        selectedAlbum = transferReturnAlbum
    }
    fun transferToFolder(folder: String) {
        val destination = media.firstOrNull { it.album == folder }
        val targets = transferItems
        if (destination == null || targets.isEmpty()) {
            statusMessage = "Could not find the destination folder."
            cancelTransfer()
            return
        }
        val results = targets.map { item ->
            // Android restricts top-level MediaStore folders. Keep the album name under an allowed media directory.
            val safeRelativePath = context.safeDestinationPath(item, destination)
            context.copyMediaToFolder(item, safeRelativePath)
        }
        val copied = results.count { it.success }
        val firstError = results.firstOrNull { !it.success }?.error
        val wasMove = transferMode == "Move"
        transferMode = null
        transferItems = emptyList()
        tab = transferReturnTab
        selectedAlbum = transferReturnAlbum
        refresh()

        when {
            copied == 0 -> statusMessage = "Couldn't copy to $folder. $firstError"
            !wasMove && copied == targets.size -> statusMessage = "Done! Copied $copied item(s) to $folder."
            !wasMove -> statusMessage = "Copied $copied of ${targets.size} item(s) to $folder. One file failed: $firstError"
            copied != targets.size -> statusMessage = "Copied $copied of ${targets.size} item(s) to $folder. Originals were kept because not all files copied. Error: $firstError"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingTransferMessage = "Moved $copied item(s) to $folder."
                try {
                    val request = MediaStore.createDeleteRequest(context.contentResolver, targets.map { it.uri })
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } catch (e: Exception) {
                    pendingTransferMessage = null
                    statusMessage = "Files copied to $folder, but Android couldn't request permission to delete originals. Originals were kept."
                }
            }
            else -> {
                try {
                    val deleted = targets.all { context.contentResolver.delete(it.uri, null, null) > 0 }
                    refresh()
                    statusMessage = if (deleted) "Moved $copied item(s) to $folder." else "Files copied to $folder, but some originals couldn't be deleted. Check your Gallery."
                } catch (_: SecurityException) {
                    statusMessage = "Files copied to $folder, but Android blocked deletion. Originals were kept."
                }
            }
        }
    }
    fun deleteMedia(items: List<GalleryMedia>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                items.forEach { context.contentResolver.delete(it.uri, null, null) }
                refresh()
                selected = emptySet()
            }
        } catch (_: Exception) { statusMessage = "Android could not delete these files. Try from the device's Gallery app." }
    }
    fun shareMedia(items: List<GalleryMedia>) {
        if (items.isEmpty()) return
        val shareType = when {
            items.size == 1 -> items.first().mimeType
            items.all { it.mimeType.startsWith("image/") } -> "image/*"
            items.all { it.mimeType.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val intent = Intent(if (items.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = shareType
            if (items.size == 1) putExtra(Intent.EXTRA_STREAM, items.first().uri)
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            clipData = ClipData.newUri(context.contentResolver, "Shared media", items.first().uri)
            items.drop(1).forEach { item -> clipData?.addItem(ClipData.Item(item.uri)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share media"))
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            // Load media metadata on a worker so the grid can paint without waiting on main-thread I/O.
            val loadedMedia = withContext(Dispatchers.IO) { context.queryGalleryMedia() }
            media = loadedMedia
        }
    }
    // Start persistent thumbnail generation right after MediaStore returns, not when a folder is opened.
    // Existing cache files are checked/reused; only missing or changed media is decoded.
    LaunchedEffect(media) {
        if (media.isEmpty()) return@LaunchedEffect
        val cacheSemaphore = Semaphore(2)
        media.chunked(16).forEach { batch ->
            val changed = coroutineScope {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        cacheSemaphore.withPermit { context.ensureThumbnailCache(item) }
                    }
                }.awaitAll()
            }.any { it }
            if (changed) thumbnailRevision++
        }
    }
    val visible = remember(media, selectedAlbum, hiddenFolders) {
        (if (selectedAlbum == "All media") media else media.filter { it.album == selectedAlbum })
            .filter { it.album !in hiddenFolders }
    }

    // Full-screen media is deliberately immersive; the viewer itself has no controls or labels.
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (viewerStartUri != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }
    val hasInnerScreen = viewerStartUri != null || actionMenu || manageMediaInfoOpen || albumMenu ||
        infoItem != null || renameItem != null || statusMessage != null || transferMode != null ||
        selected.isNotEmpty() || selectedFolder != null || settingsPage != null || folderRemoveTarget != null ||
        (tab == "Folders" && selectedAlbum != "All media") || tab != "Reel"
    BackHandler(enabled = hasInnerScreen) {
        when {
            viewerStartUri != null -> { viewerStartUri = null; selected = emptySet() }
            transferMode != null -> cancelTransfer()
            actionMenu -> actionMenu = false
            settingsPage == "hiddenFolders" -> settingsPage = "main"
            settingsPage != null -> settingsPage = null
            folderRemoveTarget != null -> folderRemoveTarget = null
            manageMediaInfoOpen -> manageMediaInfoOpen = false
            albumMenu -> albumMenu = false
            infoItem != null -> infoItem = null
            renameItem != null -> renameItem = null
            statusMessage != null -> statusMessage = null
            selected.isNotEmpty() -> selected = emptySet()
            selectedFolder != null -> selectedFolder = null
            tab == "Folders" && selectedAlbum != "All media" -> selectedAlbum = "All media"
            tab != "Reel" -> { tab = "Reel"; selectedAlbum = "All media" }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF101010), surface = Color(0xFF191919), primary = Color(0xFFFF4D7D))) {
        if (settingsPage != null) {
            SettingsScreen(
                page = settingsPage!!,
                folderNames = media.map { it.album }.distinct().sorted(),
                folderCounts = media.groupingBy { it.album }.eachCount(),
                hiddenFolders = hiddenFolders,
                onBack = { settingsPage = if (settingsPage == "hiddenFolders") "main" else null },
                onOpenHiddenFolders = { settingsPage = "hiddenFolders" },
                onToggleFolder = { folder, hidden -> setFolderHidden(folder, hidden) }
            )
        } else {
        val viewerStart = viewerStartUri
        if (viewerStart != null && visible.isNotEmpty()) {
            val startIndex = visible.indexOfFirst { it.uri == viewerStart }.coerceAtLeast(0)
            ReelsViewer(visible, startIndex, thumbnailLoader) { viewerStartUri = null }
        } else {
            Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Collections, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when {
                            transferMode != null -> "Choose destination"
                            selectedFolder != null -> selectedFolder!!
                            tab == "Folders" && selectedAlbum != "All media" -> selectedAlbum
                            selected.isNotEmpty() -> "${selected.size} selected"
                            else -> "instaGallery"
                        }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            transferMode != null -> "${transferMode} to a folder"
                            selectedFolder != null -> "Folder selected • ${media.count { it.album == selectedFolder }} items"
                            selected.isNotEmpty() -> "${selected.size} selected • ${visible.size} items"
                            else -> "${visible.size} items"
                        }, color = Color.LightGray, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (selected.isNotEmpty() || selectedFolder != null) {
                            Box {
                                IconButton(onClick = { actionMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "Selection actions", tint = Color.White)
                                }
                                DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                                    if (selectedFolder != null) {
                                        val folderName = selectedFolder!!
                                        DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) },
                                            onClick = { actionMenu = false; folderDetailsName = folderName })
                                        DropdownMenuItem(text = { Text("Rename folder") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { actionMenu = false; folderRenameTarget = folderName; folderRenameText = folderName })
                                        DropdownMenuItem(text = { Text(if (folderName in hiddenFolders) "Unhide folder" else "Hide folder") },
                                            leadingIcon = { Icon(if (folderName in hiddenFolders) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                                            onClick = { setFolderHidden(folderName, folderName !in hiddenFolders) })
                                        DropdownMenuItem(text = { Text("Delete folder") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6680)) },
                                            onClick = {
                                                actionMenu = false
                                                folderRemoveTarget = folderName
                                            })
                                    } else {
                                        if (tab == "Reel" || selectedAlbum != "All media") {
                                            DropdownMenuItem(
                                                text = { Text(if (visible.isNotEmpty() && selected.size == visible.size) "Deselect all" else "Select all") },
                                                leadingIcon = { Icon(Icons.Default.Collections, null) },
                                                onClick = {
                                                    selected = if (visible.isNotEmpty() && selected.size == visible.size) emptySet() else visible.map { it.uri }.toSet()
                                                    actionMenu = false
                                                })
                                        }
                                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                            actionMenu = false
                                            shareMedia(media.filter { it.uri in selected })
                                        })
                                        DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = {
                                            actionMenu = false
                                            media.firstOrNull { it.uri in selected }?.let { infoItem = it }
                                        })
                                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                            actionMenu = false
                                            media.firstOrNull { it.uri in selected }?.let { renameItem = it; renameText = it.name }
                                        })
                                        DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { beginTransfer("Copy") })
                                        DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = {
                                            actionMenu = false
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(context)) {
                                                manageMediaInfoOpen = true
                                            } else {
                                                beginTransfer("Move")
                                            }
                                        })
                                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6680)) }, onClick = {
                                            actionMenu = false
                                            deleteMedia(media.filter { it.uri in selected })
                                        })
                                    }
                                }
                            }
                        } else if (tab == "Folders") {
                            IconButton(onClick = { settingsPage = "main" }) {
                                Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                            }
                        }
                    }
                }
                when {
                    !hasPermission -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("Your photos and videos stay on this device.", color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = ::requestPermissions) { Text("Allow gallery access") }
                        }
                    }
                    visible.isEmpty() && !(tab == "Folders" && selectedAlbum == "All media") -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No photos or videos found", color = Color.LightGray) }
                    tab == "Folders" && selectedAlbum == "All media" -> {
                        val folders = media.filter { it.album !in hiddenFolders }.groupBy { it.album }.toSortedMap()
                        if (folders.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No folders found", color = Color.LightGray) }
                        } else {
                            LazyVerticalGrid(state = folderGridState, columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(folders.keys.toList(), key = { it }) { folder ->
                                    val folderItems = folders[folder].orEmpty()
                                    FolderTile(
                                        name = folder,
                                        items = folderItems,
                                        imageLoader = thumbnailLoader,
                                        thumbnailRevision = thumbnailRevision,
                                        selected = selectedFolder == folder,
                                        onClick = {
                                            if (transferMode != null) {
                                                transferToFolder(folder)
                                            } else if (selectedFolder != null) {
                                                selectedFolder = if (selectedFolder == folder) null else folder
                                            } else {
                                                selectedAlbum = folder
                                            }
                                        },
                                        onLongClick = {
                                            selected = emptySet()
                                            selectedFolder = folder
                                            actionMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        val gridState = mediaGridState
                        val latestSelection = rememberUpdatedState(selected)
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(visible, gridState) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var currentPosition = down.position
                                    var releasedEarly = false
                                    var movedBeforeLongPress = false
                                    val completedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null) {
                                                releasedEarly = true
                                                return@withTimeoutOrNull true
                                            }
                                            currentPosition = change.position
                                            if (!change.pressed) {
                                                releasedEarly = true
                                                return@withTimeoutOrNull true
                                            }
                                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                movedBeforeLongPress = true
                                                return@withTimeoutOrNull true
                                            }
                                        }
                                        true
                                    }
                                    if (completedBeforeLongPress != null) {
                                        if (releasedEarly && !movedBeforeLongPress) {
                                            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
                                                currentPosition.x >= itemInfo.offset.x && currentPosition.x < itemInfo.offset.x + itemInfo.size.width &&
                                                currentPosition.y >= itemInfo.offset.y && currentPosition.y < itemInfo.offset.y + itemInfo.size.height
                                            }
                                            val item = info?.let { visible.getOrNull(it.index) }
                                            if (item != null) {
                                                val current = latestSelection.value
                                                if (current.isNotEmpty()) {
                                                    selected = if (item.uri in current) current - item.uri else current + item.uri
                                                } else {
                                                    viewerStartUri = item.uri
                                                }
                                            }
                                        }
                                    } else {
                                        val dragSelection = latestSelection.value.toMutableSet()
                                        var lastSelectedUri: Uri? = null
                                        fun selectAt(position: androidx.compose.ui.geometry.Offset) {
                                            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
                                                position.x >= itemInfo.offset.x && position.x < itemInfo.offset.x + itemInfo.size.width &&
                                                position.y >= itemInfo.offset.y && position.y < itemInfo.offset.y + itemInfo.size.height
                                            }
                                            val item = info?.let { visible.getOrNull(it.index) }
                                            if (item != null && item.uri != lastSelectedUri) {
                                                dragSelection.add(item.uri)
                                                selected = dragSelection.toSet()
                                                lastSelectedUri = item.uri
                                            }
                                        }
                                        down.consume()
                                        selectAt(down.position)
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null) break
                                            if (change.pressed) {
                                                change.consume()
                                                selectAt(change.position)
                                            } else {
                                                change.consume()
                                                break
                                            }
                                        }
                                    }
                                }
                            },
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(visible, key = { it.uri.toString() }) { item ->
                                MediaThumbnail(item, item.uri in selected, thumbnailLoader, thumbnailRevision)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF171717)).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NavigationBarItem(selected = tab == "Folders", onClick = { tab = "Folders"; selectedAlbum = "All media"; selectedFolder = null; selected = emptySet() }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Folders") })
                    NavigationBarItem(selected = tab == "Reel", onClick = { tab = "Reel"; selectedAlbum = "All media"; selectedFolder = null; selected = emptySet() }, icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Reel") })
                }
            }
            if (manageMediaInfoOpen) AlertDialog(
                onDismissRequest = { manageMediaInfoOpen = false },
                title = { Text("Enable direct Move") },
                text = { Text("Android 12 and newer can skip the delete confirmation after you allow media management once. Tap Continue to open system settings, enable \"Allow management of media\" for instaGallery, then return and choose Move again. Files are deleted only after every copy succeeds.") },
                confirmButton = {
                    TextButton(onClick = {
                        manageMediaInfoOpen = false
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
                                    .setData(Uri.parse("package:" + context.packageName))
                            )
                        } catch (_: Exception) {
                            statusMessage = "Could not open media management settings. Open Android Settings > Apps > Special app access > Media management and enable instaGallery."
                        }
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { manageMediaInfoOpen = false }) { Text("Cancel") } }
            )
            if (albumMenu) {
                val albums = listOf("All media") + media.map { it.album }.distinct().sorted()
                AlertDialog(onDismissRequest = { albumMenu = false }, title = { Text("Folders") }, text = {
                    Column(Modifier.heightIn(max = 400.dp)) {
                        albums.forEach { album -> TextButton(onClick = { selectedAlbum = album; tab = "Folders"; albumMenu = false }) { Text(album, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    }
                }, confirmButton = { TextButton(onClick = { albumMenu = false }) { Text("Close") } })
            }
            folderRemoveTarget?.let { folderName ->
                AlertDialog(
                    onDismissRequest = { folderRemoveTarget = null },
                    title = { Text("Remove folder from instaGallery?") },
                    text = {
                        Text("This only hides \"$folderName\" from instaGallery. Photos, videos, other files, and the actual folder on your phone will stay untouched.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            setFolderHidden(folderName, true)
                            selectedFolder = null
                            folderRemoveTarget = null
                        }) { Text("Remove from app") }
                    },
                    dismissButton = { TextButton(onClick = { folderRemoveTarget = null }) { Text("Cancel") } }
                )
            }
            folderDetailsName?.let { folderName ->
                AlertDialog(
                    onDismissRequest = { folderDetailsName = null },
                    title = { Text("Folder details") },
                    text = {
                        Column {
                            Text("Name: $folderName")
                            Text("Items: ${media.count { it.album == folderName }}")
                            Text("Visibility: ${if (folderName in hiddenFolders) "Hidden" else "Visible"}")
                            Text("Files remain on your device; hiding only removes this folder from instaGallery.")
                        }
                    },
                    confirmButton = { TextButton(onClick = { folderDetailsName = null }) { Text("Done") } }
                )
            }
            folderRenameTarget?.let { folderName ->
                AlertDialog(
                    onDismissRequest = { folderRenameTarget = null },
                    title = { Text("Rename folder") },
                    text = {
                        OutlinedTextField(value = folderRenameText, onValueChange = { folderRenameText = it },
                            singleLine = true, label = { Text("Folder name") })
                    },
                    confirmButton = { TextButton(onClick = { renameFolder(folderName, folderRenameText) }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { folderRenameTarget = null }) { Text("Cancel") } }
                )
            }
            infoItem?.let { item ->
                AlertDialog(onDismissRequest = { infoItem = null }, title = { Text("Details") }, text = {
                    Column {
                        Text("Name: ${item.name}")
                        Text("Type: ${item.mimeType}")
                        Text("Folder: ${item.album}")
                        Text("Size: ${item.size / 1024} KB")
                        Text("Added: ${DateFormat.getDateTimeInstance().format(Date(item.dateAdded * 1000))}")
                        Text("Location: ${item.uri}", fontSize = 11.sp)
                    }
                }, confirmButton = { TextButton(onClick = { infoItem = null }) { Text("Done") } })
            }
            renameItem?.let { item ->
                AlertDialog(onDismissRequest = { renameItem = null }, title = { Text("Rename") }, text = {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("File name") })
                }, confirmButton = { TextButton(onClick = {
                    val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, renameText.trim()) }
                    try {
                        val changed = context.contentResolver.update(item.uri, values, null, null)
                        statusMessage = if (changed > 0) "Renamed successfully" else "Rename not supported for this file"
                        refresh()
                    } catch (_: Exception) { statusMessage = "Rename was blocked by Android permissions" }
                    renameItem = null
                }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renameItem = null }) { Text("Cancel") } })
            }
            statusMessage?.let { msg -> AlertDialog(onDismissRequest = { statusMessage = null }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { statusMessage = null }) { Text("OK") } }) }
        }
        }
    }
}

@Composable
private fun SettingsScreen(
    page: String,
    folderNames: List<String>,
    folderCounts: Map<String, Int>,
    hiddenFolders: Set<String>,
    onBack: () -> Unit,
    onOpenHiddenFolders: () -> Unit,
    onToggleFolder: (String, Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                if (page == "hiddenFolders") "Hide or unhide folders" else "Settings",
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (page == "hiddenFolders") {
            Text(
                "Choose which folders appear in instaGallery. Your original files and phone folders are not changed.",
                color = Color.LightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            if (folderNames.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No folders found.", color = Color.LightGray)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    folderNames.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${folderCounts[folder] ?: 0} media items", color = Color.LightGray, fontSize = 12.sp)
                            }
                            TextButton(onClick = { onToggleFolder(folder, folder !in hiddenFolders) }) {
                                Text(if (folder in hiddenFolders) "Unhide" else "Hide")
                            }
                        }
                        Divider(color = Color(0xFF303030))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Surface(
                    onClick = onOpenHiddenFolders,
                    color = Color(0xFF191919),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Hide or unhide folders", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Manage which folders appear in the gallery", color = Color.LightGray, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("More settings will be added here.", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

private const val THUMBNAIL_MAX_SIZE = 512

// filesDir (not cacheDir) keeps generated thumbnail JPEGs across app launches.
private fun Context.thumbnailCacheFile(item: GalleryMedia): File {
    val keyMaterial = "${item.uri}|${item.size}|${item.dateAdded}|${item.durationMs}|${item.thumbnailTimeMs}"
    val hash = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(File(filesDir, "media_thumbnail_store"), "$hash.jpg")
}

private fun Context.ensureThumbnailCache(item: GalleryMedia): Boolean {
    val target = thumbnailCacheFile(item)
    if (target.isFile && target.length() > 0L) return false
    val directory = target.parentFile ?: return false
    if (!directory.exists() && !directory.mkdirs()) return false
    var bitmap: Bitmap? = null
    val temporary = File(directory, target.name + ".tmp")
    return try {
        bitmap = if (item.isVideo) createVideoThumbnail(item) else createImageThumbnail(item.uri)
        if (bitmap == null) return false
        val wrote = FileOutputStream(temporary).use { output ->
            val ok = bitmap!!.compress(Bitmap.CompressFormat.JPEG, 84, output)
            output.flush()
            ok
        }
        if (!wrote) {
            temporary.delete()
            false
        } else if (temporary.renameTo(target)) {
            true
        } else {
            temporary.delete()
            target.isFile && target.length() > 0L
        }
    } catch (_: Exception) {
        temporary.delete()
        false
    } finally {
        bitmap?.recycle()
    }
}

private fun Context.createImageThumbnail(uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val original = info.size
            val scale = min(1f, min(THUMBNAIL_MAX_SIZE.toFloat() / original.width, THUMBNAIL_MAX_SIZE.toFloat() / original.height))
            decoder.setTargetSize(
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMBNAIL_MAX_SIZE &&
        bounds.outHeight / (sample * 2) >= THUMBNAIL_MAX_SIZE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
    return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun Context.createVideoThumbnail(item: GalleryMedia): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(this, item.uri)
        val frameTimeUs = item.thumbnailTimeMs * 1_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMBNAIL_MAX_SIZE, THUMBNAIL_MAX_SIZE
            )
        } else {
            retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleThumbnail(it) }
        }
    } finally {
        retriever.release()
    }
}

private fun scaleThumbnail(bitmap: Bitmap): Bitmap {
    val scale = min(1f, min(THUMBNAIL_MAX_SIZE.toFloat() / bitmap.width, THUMBNAIL_MAX_SIZE.toFloat() / bitmap.height))
    if (scale >= 1f) return bitmap
    val scaled = Bitmap.createScaledBitmap(
        bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1), true
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

@Composable
private fun rememberThumbnailRequest(context: Context, item: GalleryMedia, cacheRevision: Int): ImageRequest? =
    remember(item.uri, item.size, item.dateAdded, item.durationMs, item.thumbnailTimeMs, cacheRevision) {
        val file = context.thumbnailCacheFile(item)
        if (file.isFile && file.length() > 0L) {
            val key = file.nameWithoutExtension
            ImageRequest.Builder(context)
                .data(file)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .crossfade(false)
                .build()
        } else null
    }

private fun Context.hasMediaPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val writeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }
    val permissions = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return permissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.queryGalleryMedia(): List<GalleryMedia> {
    val result = mutableListOf<GalleryMedia>()
    listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
        val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, "bucket_display_name").apply {
            if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) add(MediaStore.Video.VideoColumns.DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            else add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()
        try {
            contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val albumCol = cursor.getColumnIndex("bucket_display_name")
                val durationCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val pathCol = cursor.getColumnIndex(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += GalleryMedia(ContentUris.withAppendedId(collection, id),
                        if (nameCol >= 0) cursor.getString(nameCol) ?: "Media" else "Media",
                        if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else "",
                        if (albumCol >= 0) cursor.getString(albumCol) ?: "Other" else "Other",
                        if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        if (pathCol >= 0) {
                            val storedPath = cursor.getString(pathCol).orEmpty()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) storedPath
                            else File(storedPath).parentFile?.absolutePath?.plus(File.separator).orEmpty()
                        } else if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) "Movies/" else "Pictures/",
                        if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI && durationCol >= 0) cursor.getLong(durationCol) else 0L)
                }
            }
        } catch (_: SecurityException) {}
    }
    return result.distinctBy { it.uri }.sortedByDescending { it.dateAdded }
}

private fun Context.safeDestinationPath(item: GalleryMedia, destination: GalleryMedia): String {
    val raw = destination.relativePath.trim().trim('/')
    val parts = raw.split('/').filter { it.isNotBlank() }
    val root = parts.firstOrNull().orEmpty()
    val allowedRoots = if (item.isVideo) setOf("Movies", "DCIM", "Pictures") else setOf("DCIM", "Pictures")
    return if (root in allowedRoots) {
        "$raw/"
    } else {
        val safeRoot = if (item.isVideo) "Movies" else "Pictures"
        // Preserve the chosen folder's visible album name without writing to a blocked top-level directory.
        val folderName = destination.album.ifBlank { parts.lastOrNull() ?: "Copied" }
            .replace("/", "_").replace("\\", "_").trim().ifBlank { "Copied" }
        "$safeRoot/$folderName/"
    }
}

private fun Context.copyMediaToFolder(item: GalleryMedia, relativePath: String): CopyResult {
    if (relativePath.isBlank()) return CopyResult(false, "Destination folder path is empty.")

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return try {
            val folder = File(relativePath)
            if (!folder.exists() && !folder.mkdirs()) return CopyResult(false, "Cannot create the destination folder.")
            val baseName = item.name.substringBeforeLast('.', item.name)
            val extension = item.name.substringAfterLast('.', "")
            var outputFile = File(folder, item.name)
            var suffix = 1
            while (outputFile.exists()) {
                val name = if (extension.isBlank()) "$baseName (copy $suffix)" else "$baseName (copy $suffix).$extension"
                outputFile = File(folder, name)
                suffix++
            }
            val input = contentResolver.openInputStream(item.uri)
                ?: return CopyResult(false, "Android couldn't read the original file.")
            input.use { source -> FileOutputStream(outputFile).use { target -> source.copyTo(target) } }
            MediaScannerConnection.scanFile(this, arrayOf(outputFile.absolutePath), arrayOf(item.mimeType), null)
            CopyResult(true)
        } catch (e: SecurityException) {
            CopyResult(false, "Storage permission denied. Allow photo, video and file access in Android Settings.")
        } catch (e: Exception) {
            CopyResult(false, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val normalizedPath = relativePath.trimStart('/').let { if (it.endsWith("/")) it else "$it/" }
    var destinationUri: Uri? = null
    return try {
        val input = contentResolver.openInputStream(item.uri)
            ?: return CopyResult(false, "Android couldn't read the original file. Check media permission.")
        input.use { source ->
            val baseName = item.name.substringBeforeLast('.', item.name)
            val extension = item.name.substringAfterLast('.', "")
            var outputName = item.name
            var suffix = 1
            fun nameExists(candidate: String): Boolean {
                return contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(normalizedPath, candidate),
                    null
                )?.use { it.moveToFirst() } == true
            }
            while (nameExists(outputName)) {
                outputName = if (extension.isBlank()) "$baseName (copy $suffix)" else "$baseName (copy $suffix).$extension"
                suffix++
                if (suffix > 1000) throw IllegalStateException("Too many files with the same name in this folder.")
            }
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, normalizedPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            destinationUri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("Android refused to create a file in this folder. Try another folder.")
            val output = contentResolver.openOutputStream(destinationUri!!, "w")
                ?: throw IllegalStateException("Android couldn't open the new file for writing.")
            output.use { target -> source.copyTo(target) }
            val ready = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            val updated = contentResolver.update(destinationUri!!, ready, null, null)
            if (updated <= 0) throw IllegalStateException("Android couldn't finish saving the copied file.")
        }
        CopyResult(true)
    } catch (e: SecurityException) {
        destinationUri?.let { uri -> try { contentResolver.delete(uri, null, null) } catch (_: Exception) {} }
        CopyResult(false, "Android denied access to the source or destination folder. Try a regular Pictures/DCIM folder or check media permission.")
    } catch (e: Exception) {
        destinationUri?.let { uri -> try { contentResolver.delete(uri, null, null) } catch (_: Exception) {} }
        CopyResult(false, e.localizedMessage ?: e.javaClass.simpleName)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    name: String,
    items: List<GalleryMedia>,
    imageLoader: ImageLoader,
    thumbnailRevision: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cover = items.firstOrNull()
    val coverRequest = if (cover != null) rememberThumbnailRequest(context, cover, thumbnailRevision) else null
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF503040) else Color(0xFF191919), MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f).background(Color.DarkGray)) {
            if (cover != null) {
                AsyncImage(
                    model = coverRequest,
                    imageLoader = imageLoader,
                    contentDescription = "$name folder preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Folder, null, tint = Color.LightGray, modifier = Modifier.align(Alignment.Center).size(48.dp))
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color(0x5533A6FF)), contentAlignment = Alignment.TopEnd) {
                    Text("✓", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(8.dp))
                }
            }
            Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp))
        }
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        Text("${items.size} items", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp))
    }
}

@Composable
private fun MediaThumbnail(item: GalleryMedia, selected: Boolean, imageLoader: ImageLoader, thumbnailRevision: Int) {
    val context = LocalContext.current
    val request = rememberThumbnailRequest(context, item, thumbnailRevision)
    Box(Modifier.aspectRatio(0.78f).background(if (selected) Color(0xFF503040) else Color.DarkGray)) {
        AsyncImage(model = request,
            imageLoader = imageLoader, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x6633A6FF)), contentAlignment = Alignment.TopEnd) {
            Text("✓", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(5.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelsViewer(items: List<GalleryMedia>, startIndex: Int, imageLoader: ImageLoader, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    var playing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { playing = true }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val item = items[page]
            if (item.isVideo) {
                VideoPage(item, page == pagerState.currentPage, playing, onEnded = {
                    scope.launch { pagerState.animateScrollToPage(if (pagerState.currentPage < items.lastIndex) pagerState.currentPage + 1 else 0) }
                }, onTogglePlay = { playing = !playing })
            } else {
                AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(item.uri).crossfade(false).build(), imageLoader = imageLoader, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun VideoPage(item: GalleryMedia, active: Boolean, playing: Boolean, onEnded: () -> Unit, onTogglePlay: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
            prepare()
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            if (event == Lifecycle.Event.ON_RESUME && active) player.playWhenReady = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); player.release() }
    }
    LaunchedEffect(active, playing) { player.volume = 1f; player.playWhenReady = active && playing }
    DisposableEffect(player, active) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED && active) onEnded() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val exoPlayer = player
    Box(Modifier.fillMaxSize().pointerInput(item.uri, active) { detectTapGestures(onTap = { if (active) onTogglePlay() }) }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = exoPlayer; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            update = { it.player = exoPlayer }, modifier = Modifier.fillMaxSize())
    }
}
