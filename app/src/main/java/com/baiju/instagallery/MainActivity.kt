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
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.DateFormat
import java.util.Date
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.atan2

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

@Composable
private fun BottomAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        }
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

private fun simpleFadeTransition() =
    fadeIn(animationSpec = tween(durationMillis = 180)) togetherWith
        fadeOut(animationSpec = tween(durationMillis = 180))

@Composable
internal fun GalleryHeader(
    title: String,
    subtitle: String,
    onOpenMenu: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenMenu) {
            Icon(Icons.Default.Menu, "Open menu", tint = Color.White, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { GalleryApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GalleryApp() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var hasPermission by remember { mutableStateOf(context.hasMediaPermission()) }
    var media by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    var selectedAlbum by remember { mutableStateOf("All media") }
    var viewerStartUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var viewerDisplayUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var viewerCurrentUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingGridScrollUri by remember { mutableStateOf<Uri?>(null) }
    var highlightedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var resumeHandled by remember { mutableStateOf(false) }
    val settingsPrefs = remember(context) {
        context.getSharedPreferences("instagallery_settings", Context.MODE_PRIVATE)
    }
    var hasDocumentAccess by remember { mutableStateOf(context.hasDocumentAccess()) }
    val documentAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasDocumentAccess = context.hasDocumentAccess()
    }
    var autoPlayNext by remember(context) {
        mutableStateOf(settingsPrefs.getBoolean("auto_play_next", true))
    }
    var rememberLastPlayed by remember(context) {
        mutableStateOf(settingsPrefs.getBoolean("remember_last_played", false))
    }
    var tab by remember { mutableStateOf("Reel") }
    var menuScope by remember { mutableStateOf("All") }
    var menuContent by remember { mutableStateOf("Media") }
    var documentFolderMode by remember { mutableStateOf(false) }
    var documentViewerOpen by remember { mutableStateOf(false) }
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
    var renameMultipleItems by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var renameText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var settingsPage by remember { mutableStateOf<String?>(null) }
    var mainMenuOpen by remember { mutableStateOf(false) }
    var pendingRenameActions by remember { mutableStateOf<List<Pair<GalleryMedia, String>>>(emptyList()) }
    var pendingRenameAlreadyDone by remember { mutableIntStateOf(0) }
    var pendingRenameTotalCount by remember { mutableIntStateOf(0) }
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
    var mediaGridColumns by remember(context) {
        mutableIntStateOf(settingsPrefs.getInt("media_grid_columns", 3).coerceIn(2, 6))
    }
    var folderGridColumns by remember(context) {
        mutableIntStateOf(settingsPrefs.getInt("folder_grid_columns", 2).coerceIn(1, 4))
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var thumbnailRevision by remember { mutableIntStateOf(0) }

    // Keep pinch-to-zoom grid density between app launches.
    LaunchedEffect(mediaGridColumns) {
        settingsPrefs.edit().putInt("media_grid_columns", mediaGridColumns).apply()
    }
    LaunchedEffect(folderGridColumns) {
        settingsPrefs.edit().putInt("folder_grid_columns", folderGridColumns).apply()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = context.hasMediaPermission()
        hasDocumentAccess = context.hasDocumentAccess()
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        media = context.queryGalleryMedia()
        selected = emptySet()
        pendingTransferMessage?.let { message ->
            statusMessage = if (result.resultCode == android.app.Activity.RESULT_OK) message else "Files were copied; original files were kept."
            pendingTransferMessage = null
        }
    }
    val renamePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingRenameActions
        val total = pendingRenameTotalCount
        if (result.resultCode == android.app.Activity.RESULT_OK && pending.isNotEmpty()) {
            var renamed = pendingRenameAlreadyDone
            var failed = 0
            pending.forEach { (item, newName) ->
                try {
                    val title = newName.substringBeforeLast('.', newName)
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                        put(MediaStore.MediaColumns.TITLE, title)
                    }
                    if (context.contentResolver.update(item.uri, values, null, null) > 0) renamed++ else failed++
                } catch (_: Exception) { failed++ }
            }
            statusMessage = if (renamed == total && failed == 0) {
                "Renamed all $renamed file(s) successfully."
            } else {
                "Renamed $renamed of $total file(s). $failed file(s) could not be renamed."
            }
            media = context.queryGalleryMedia()
            selected = emptySet()
        } else if (pending.isNotEmpty()) {
            statusMessage = if (pendingRenameAlreadyDone > 0) {
                "Renamed $pendingRenameAlreadyDone of $total file(s); Android permission was not granted for the rest."
            } else {
                "Rename was cancelled because Android write permission was not granted."
            }
        }
        pendingRenameActions = emptyList()
        pendingRenameAlreadyDone = 0
        pendingRenameTotalCount = 0
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

    fun requestDocumentsAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                documentAccessLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:" + context.packageName))
                )
            } catch (_: Exception) {
                try {
                    documentAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    statusMessage = "Open Android Settings > Apps > instaGallery > Special app access > All files access."
                }
            }
        } else {
            requestPermissions()
            hasDocumentAccess = context.hasDocumentAccess()
        }
    }
    fun refresh() {
        if (isRefreshing) return
        appScope.launch {
            isRefreshing = true
            try {
                val refreshed = withContext(Dispatchers.IO) { context.queryGalleryMedia() }
                media = refreshed
            } finally {
                isRefreshing = false
            }
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
    fun finalDisplayName(originalName: String, enteredName: String): String {
        val clean = enteredName.trim().trim('/').replace("/", "_").replace("\\", "_")
        if (clean.isBlank()) return ""
        val dot = originalName.lastIndexOf('.')
        val extension = if (dot > 0 && dot < originalName.lastIndex) originalName.substring(dot) else ""
        if (extension.isBlank() || clean.endsWith(extension, ignoreCase = true)) return clean
        val typedDot = clean.lastIndexOf('.')
        val base = if (typedDot > 0) clean.substring(0, typedDot) else clean
        return base + extension
    }

    fun renameMediaItems(actions: List<Pair<GalleryMedia, String>>) {
        if (actions.isEmpty()) return
        if (actions.any { it.second.isBlank() }) {
            statusMessage = "Enter a valid file name."
            return
        }
        var renamed = 0
        val blocked = mutableListOf<Pair<GalleryMedia, String>>()
        var recoverableAction: android.content.IntentSender? = null
        var failedWithoutPermission = 0
        actions.forEach { (item, newName) ->
            try {
                val title = newName.substringBeforeLast('.', newName)
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                    put(MediaStore.MediaColumns.TITLE, title)
                }
                if (context.contentResolver.update(item.uri, values, null, null) > 0) renamed++ else blocked.add(item to newName)
            } catch (e: android.app.RecoverableSecurityException) {
                blocked.add(item to newName)
                if (recoverableAction == null) recoverableAction = e.userAction.actionIntent.intentSender
            } catch (_: SecurityException) {
                blocked.add(item to newName)
            } catch (_: Exception) {
                failedWithoutPermission++
            }
        }
        if (blocked.isNotEmpty()) {
            try {
                val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.createWriteRequest(context.contentResolver, blocked.map { it.first.uri }).intentSender
                } else {
                    recoverableAction
                }
                if (intentSender != null) {
                    pendingRenameActions = blocked
                    pendingRenameAlreadyDone = renamed
                    pendingRenameTotalCount = actions.size
                    renamePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    selected = emptySet()
                    return
                }
            } catch (_: Exception) { }
            statusMessage = if (renamed > 0) {
                "Renamed " + renamed + " of " + actions.size + " file(s). Android blocked the remaining rename operation(s)."
            } else {
                "Android blocked the rename. Allow instaGallery to modify the selected media, then try again."
            }
            refresh()
            return
        }
        statusMessage = if (renamed == actions.size && failedWithoutPermission == 0) {
            if (actions.size == 1) "Renamed successfully." else "Renamed all " + renamed + " file(s) successfully."
        } else {
            "Renamed " + renamed + " of " + actions.size + " file(s). Some files could not be renamed."
        }
        selected = emptySet()
        refresh()
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
    val visible = remember(media, selectedAlbum, hiddenFolders, tab) {
        (if (selectedAlbum == "All media") media else media.filter { it.album == selectedAlbum })
            .filter { it.album !in hiddenFolders }
            .filter { item -> when (tab) { "Images" -> !item.isVideo; "Videos" -> item.isVideo; else -> true } }
    }

    LaunchedEffect(hasPermission, media, rememberLastPlayed) {
        if (hasPermission && media.isNotEmpty() && !resumeHandled) {
            resumeHandled = true
            if (rememberLastPlayed) {
                val lastUri = settingsPrefs.getString("last_media_uri", null)
                if (lastUri != null && media.any { it.uri.toString() == lastUri }) {
                    viewerStartUri = Uri.parse(lastUri)
                    viewerDisplayUri = Uri.parse(lastUri)
                    viewerCurrentUri = Uri.parse(lastUri)
                }
            }
        }
    }

    LaunchedEffect(pendingGridScrollUri, viewerStartUri, viewerDisplayUri, visible) {
        val target = pendingGridScrollUri
        if (target != null && viewerStartUri == null && viewerDisplayUri == null) {
            val index = visible.indexOfFirst { it.uri == target }
            if (index >= 0) {
                mediaGridState.scrollToItem(index)
                withFrameNanos { }
                mediaGridState.scrollToItem(index)
                highlightedMediaUri = target
            }
            pendingGridScrollUri = null
        }
    }
    LaunchedEffect(highlightedMediaUri) {
        val target = highlightedMediaUri ?: return@LaunchedEffect
        delay(1_700)
        if (highlightedMediaUri == target) highlightedMediaUri = null
    }

    // Always prioritize what the user is looking at now. Opening a folder cancels/reorders this
    // background queue so its thumbnails are generated before unrelated gallery items.
    LaunchedEffect(media, tab, selectedAlbum, hiddenFolders) {
        if (media.isEmpty()) return@LaunchedEffect
        val prioritized = when {
            tab == "Folders" && selectedAlbum != "All media" -> visible
            tab == "Folders" -> {
                val coverItems = visible.groupBy { it.album }.values.mapNotNull { it.firstOrNull() }
                coverItems + visible.take(30)
            }
            else -> visible.take(36)
        }
        val orderedItems = (prioritized + media).distinctBy { it.uri }
        val cacheSemaphore = Semaphore(2)
        orderedItems.chunked(4).forEach { batch ->
            val changed = coroutineScope {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        cacheSemaphore.withPermit { context.ensureThumbnailCache(item) }
                    }
                }.awaitAll()
            }.any { it }
            // Refresh cache-file-backed thumbnail requests every few completed items,
            // not after a whole-library batch has finished.
            if (changed) thumbnailRevision++
        }
    }

    // Full-screen media is deliberately immersive; the viewer itself has no controls or labels.
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (viewerDisplayUri != null || (tab == "Documents" && documentViewerOpen)) {
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }
    val hasInnerScreen = viewerStartUri != null || mainMenuOpen || actionMenu || manageMediaInfoOpen || albumMenu ||
        infoItem != null || renameItem != null || renameMultipleItems.isNotEmpty() || statusMessage != null || transferMode != null ||
        selected.isNotEmpty() || selectedFolder != null || settingsPage != null || folderRemoveTarget != null ||
        (tab == "Folders" && selectedAlbum != "All media") || tab != "Reel"
    BackHandler(enabled = hasInnerScreen) {
        when {
            mainMenuOpen -> mainMenuOpen = false
            viewerStartUri != null -> {
                pendingGridScrollUri = viewerCurrentUri ?: viewerStartUri
                highlightedMediaUri = null
                viewerStartUri = null
                selected = emptySet()
            }
            transferMode != null -> cancelTransfer()
            actionMenu -> actionMenu = false
            settingsPage == "hiddenFolders" -> settingsPage = "main"
            settingsPage != null -> settingsPage = null
            folderRemoveTarget != null -> folderRemoveTarget = null
            manageMediaInfoOpen -> manageMediaInfoOpen = false
            albumMenu -> albumMenu = false
            infoItem != null -> infoItem = null
            renameItem != null -> renameItem = null
            renameMultipleItems.isNotEmpty() -> renameMultipleItems = emptyList()
            statusMessage != null -> statusMessage = null
            selected.isNotEmpty() -> selected = emptySet()
            selectedFolder != null -> selectedFolder = null
            tab == "Folders" && selectedAlbum != "All media" -> selectedAlbum = "All media"
            tab != "Reel" -> {
                val returnToFolders = tab == "Documents" && menuScope == "Folder"
                tab = if (returnToFolders) "Folders" else "Reel"
                if (!returnToFolders) menuScope = "All"
                menuContent = "Media"
                documentFolderMode = false
                selectedAlbum = "All media"
                selectedFolder = null
                selected = emptySet()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF101010), surface = Color(0xFF191919), primary = Color(0xFFFF4D7D))) {
        AnimatedContent(
            targetState = settingsPage,
            transitionSpec = { simpleFadeTransition() },
            label = "settingsTransition"
        ) { targetSettingsPage ->
        if (targetSettingsPage != null) {
            SettingsScreen(
                page = targetSettingsPage,
                folderNames = media.map { it.album }.distinct().sorted(),
                folderCounts = media.groupingBy { it.album }.eachCount(),
                hiddenFolders = hiddenFolders,
                autoPlayNext = autoPlayNext,
                rememberLastPlayed = rememberLastPlayed,
                onToggleAutoPlayNext = { enabled ->
                    autoPlayNext = enabled
                    settingsPrefs.edit().putBoolean("auto_play_next", enabled).apply()
                },
                onToggleRememberLastPlayed = { enabled ->
                    rememberLastPlayed = enabled
                    settingsPrefs.edit().putBoolean("remember_last_played", enabled).apply()
                },
                onBack = { settingsPage = if (settingsPage == "hiddenFolders") "main" else null },
                onOpenHiddenFolders = { settingsPage = "hiddenFolders" },
                onToggleFolder = { folder, hidden -> setFolderHidden(folder, hidden) }
            )
        } else if (mainMenuOpen) {
            GalleryMenuScreen(
                currentScope = menuScope,
                currentContent = menuContent,
                onClose = { mainMenuOpen = false },
                onSelectScope = { nextScope ->
                    menuScope = nextScope
                    documentFolderMode = nextScope == "Folder" && menuContent == "Doc"
                    tab = when {
                        menuContent == "Doc" -> "Documents"
                        nextScope == "Folder" -> "Folders"
                        else -> "Reel"
                    }
                    selectedAlbum = "All media"
                    selectedFolder = null
                    selected = emptySet()
                    if (tab == "Documents") {
                        viewerStartUri = null
                        viewerDisplayUri = null
                        viewerCurrentUri = null
                    }
                    mainMenuOpen = false
                },
                onSelectContent = { nextContent ->
                    menuContent = nextContent
                    documentFolderMode = nextContent == "Doc" && menuScope == "Folder"
                    tab = when {
                        nextContent == "Doc" -> "Documents"
                        menuScope == "Folder" -> "Folders"
                        else -> "Reel"
                    }
                    selectedAlbum = "All media"
                    selectedFolder = null
                    selected = emptySet()
                    if (tab == "Documents") {
                        viewerStartUri = null
                        viewerDisplayUri = null
                        viewerCurrentUri = null
                    }
                    mainMenuOpen = false
                },
                onOpenSettings = { mainMenuOpen = false; settingsPage = "main" }
            )
        } else if (tab == "Documents") {
            DocumentsScreen(
                hasAccess = hasDocumentAccess,
                onRequestAccess = ::requestDocumentsAccess,
                initialViewMode = if (documentFolderMode) "folders" else "all",
                onDocumentOpenChanged = { documentViewerOpen = it },
                onOpenMenu = { mainMenuOpen = true },
                mediaGridColumns = mediaGridColumns,
                folderGridColumns = folderGridColumns,
                mediaGridState = mediaGridState,
                folderGridState = folderGridState,
                onMediaGridColumnsChange = { mediaGridColumns = it },
                onFolderGridColumnsChange = { folderGridColumns = it },
                onBack = {
                    documentViewerOpen = false
                    tab = if (menuScope == "Folder") "Folders" else "Reel"
                    menuContent = "Media"
                    documentFolderMode = false
                    selectedAlbum = "All media"
                    selectedFolder = null
                    selected = emptySet()
                }
            )
        } else {
        val viewerStart = viewerStartUri
        val viewerDisplay = viewerDisplayUri
        if (viewerDisplay != null) {
            // Keep the gallery out of composition until the fade-out finishes, preventing thumbnails
            // from showing through around the viewer during close.
            LaunchedEffect(viewerStart, viewerDisplay) {
                if (viewerStart == null) {
                    delay(220)
                    if (viewerStartUri == null && viewerDisplayUri == viewerDisplay) {
                        viewerDisplayUri = null
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AnimatedVisibility(
                    visible = viewerStart != null && visible.isNotEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(animationSpec = tween(180)),
                    exit = fadeOut(animationSpec = tween(180))
                ) {
                    val startIndex = visible.indexOfFirst {
                        it.uri == (viewerStart ?: viewerDisplay)
                    }.coerceAtLeast(0)
                    ReelsViewer(
                        items = visible,
                        startIndex = startIndex,
                        imageLoader = thumbnailLoader,
                        autoPlayNext = autoPlayNext,
                        rememberLastPlayed = rememberLastPlayed,
                        onPageViewed = { uri ->
                            viewerCurrentUri = uri
                            val savedUri = settingsPrefs.getString("last_media_uri", null)
                            if (savedUri != uri.toString()) {
                                settingsPrefs.edit()
                                    .putString("last_media_uri", uri.toString())
                                    .putLong("last_playback_position", 0L)
                                    .apply()
                            }
                        }
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().pointerInput(tab, selected, selectedFolder, transferMode, mainMenuOpen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var horizontalSwipe = false
                    var multiTouchSwipe = false
                    var lastDx = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) multiTouchSwipe = true
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) break
                        lastDx = change.position.x - down.position.x
                        val totalDy = change.position.y - down.position.y
                        if (!multiTouchSwipe && kotlin.math.abs(lastDx) > viewConfiguration.touchSlop && kotlin.math.abs(lastDx) > kotlin.math.abs(totalDy) * 1.25f) {
                            horizontalSwipe = true
                        }
                        if (!change.pressed) {
                            if (horizontalSwipe && !multiTouchSwipe && selected.isEmpty() && selectedFolder == null && transferMode == null && !mainMenuOpen) {
                                tab = if (tab == "Folders") "Reel" else "Folders"
                                selectedAlbum = "All media"
                                selected = emptySet()
                            }
                            break
                        }
                    }
                }
            }) {
                AnimatedContent(
                    targetState = when {
                        selected.isNotEmpty() -> "media" to null
                        selectedFolder != null -> "folder" to selectedFolder
                        else -> "normal" to null
                    },
                    transitionSpec = { simpleFadeTransition() },
                    modifier = Modifier.fillMaxWidth(),
                    label = "headerActionTransition"
                ) { headerTarget ->
                    when (headerTarget.first) {
                        "media" -> {
                            val allVisibleSelected = visible.isNotEmpty() && visible.all { it.uri in selected }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selected = emptySet() }) {
                                    Icon(Icons.Default.Close, "Exit selection", tint = Color.White)
                                }
                                TextButton(
                                    onClick = { selected = if (allVisibleSelected) emptySet() else visible.map { it.uri }.toSet() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${selected.size}/${visible.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(if (allVisibleSelected) "Deselect all" else "Select all", fontSize = 12.sp, color = Color.LightGray)
                                    }
                                }
                            }
                        }
                        "folder" -> {
                            val folderName = headerTarget.second.orEmpty()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedFolder = null }) {
                                    Icon(Icons.Default.Close, "Clear folder selection", tint = Color.White)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(folderName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Folder selected • ${media.count { it.album == folderName }} items", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                        }
                        else -> {
                            GalleryHeader(
                                title = when {
                                    transferMode != null -> "Choose destination"
                                    tab == "Folders" && selectedAlbum != "All media" -> selectedAlbum
                                    tab == "Folders" -> "Folder"
                                    tab == "Images" -> "Images"
                                    tab == "Videos" -> "Videos"
                                    else -> "Reel"
                                },
                                subtitle = if (transferMode != null) "${transferMode} to a folder" else "${visible.size} items",
                                onOpenMenu = { mainMenuOpen = true }
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = tab to selectedAlbum,
                    transitionSpec = { simpleFadeTransition() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                    label = "galleryContentTransition"
                ) { galleryTarget ->
                    val gridTab = galleryTarget.first
                    val gridAlbum = galleryTarget.second
                    val animatedVisible = remember(media, hiddenFolders, gridAlbum, gridTab) {
                        (if (gridAlbum == "All media") media else media.filter { it.album == gridAlbum })
                            .filter { it.album !in hiddenFolders }
                            .filter { item -> when (gridTab) { "Images" -> !item.isVideo; "Videos" -> item.isVideo; else -> true } }
                    }
                    when {
                    !hasPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("Your photos and videos stay on this device.", color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = ::requestPermissions) { Text("Allow gallery access") }
                        }
                    }
                    animatedVisible.isEmpty() && !(gridTab == "Folders" && gridAlbum == "All media") -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos or videos found", color = Color.LightGray) }
                    gridTab == "Folders" && gridAlbum == "All media" -> {
                        val folders = media.filter { it.album !in hiddenFolders }.groupBy { it.album }.toSortedMap()
                        if (folders.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No folders found", color = Color.LightGray) }
                        } else {
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = { refresh() },
                                modifier = Modifier.fillMaxSize()
                            ) {
                            LazyVerticalGrid(state = folderGridState, columns = GridCells.Fixed(folderGridColumns), modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var accumulatedZoom = 1f
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pointers = event.changes.filter { it.pressed }
                                        if (pointers.isEmpty()) break
                                        if (pointers.size >= 2) {
                                            val first = pointers[0]
                                            val second = pointers[1]
                                            val previousDistance = (first.previousPosition - second.previousPosition).getDistance()
                                            val currentDistance = (first.position - second.position).getDistance()
                                            if (previousDistance > 0.5f) {
                                                accumulatedZoom *= currentDistance / previousDistance
                                                if (accumulatedZoom > 1.16f) {
                                                    folderGridColumns = (folderGridColumns - 1).coerceAtLeast(1)
                                                    accumulatedZoom = 1f
                                                } else if (accumulatedZoom < 0.86f) {
                                                    folderGridColumns = (folderGridColumns + 1).coerceAtMost(4)
                                                    accumulatedZoom = 1f
                                                }
                                            }
                                            pointers.forEach { it.consume() }
                                        }
                                    }
                                }
                            }, contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(folders.keys.toList(), key = { it }) { folder ->
                                    val folderItems = folders[folder].orEmpty()
                                    Box {
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
                        }
                    }
                    else -> {
                        // The outgoing AnimatedContent page gets its own temporary state so a crossfade
                        // between two albums never attaches one LazyGridState to two grids at once.
                        val outgoingGridState = rememberLazyGridState()
                        val gridState = if (galleryTarget == (tab to selectedAlbum)) mediaGridState else outgoingGridState
                        val latestSelection = rememberUpdatedState(selected)
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { refresh() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(mediaGridColumns),
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var accumulatedZoom = 1f
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pointers = event.changes.filter { it.pressed }
                                        if (pointers.isEmpty()) break
                                        if (pointers.size >= 2) {
                                            val first = pointers[0]
                                            val second = pointers[1]
                                            val previousDistance = (first.previousPosition - second.previousPosition).getDistance()
                                            val currentDistance = (first.position - second.position).getDistance()
                                            if (previousDistance > 0.5f) {
                                                accumulatedZoom *= currentDistance / previousDistance
                                                if (accumulatedZoom > 1.16f) {
                                                    mediaGridColumns = (mediaGridColumns - 1).coerceAtLeast(2)
                                                    accumulatedZoom = 1f
                                                } else if (accumulatedZoom < 0.86f) {
                                                    mediaGridColumns = (mediaGridColumns + 1).coerceAtMost(6)
                                                    accumulatedZoom = 1f
                                                }
                                            }
                                            pointers.forEach { it.consume() }
                                        }
                                    }
                                }
                            }.pointerInput(animatedVisible, gridState) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var currentPosition = down.position
                                    var releasedEarly = false
                                    var movedBeforeLongPress = false
                                    var multiTouchDetected = false
                                    val completedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.count { it.pressed } >= 2) {
                                                multiTouchDetected = true
                                                return@withTimeoutOrNull true
                                            }
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
                                        if (!multiTouchDetected && releasedEarly && !movedBeforeLongPress) {
                                            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
                                                currentPosition.x >= itemInfo.offset.x && currentPosition.x < itemInfo.offset.x + itemInfo.size.width &&
                                                currentPosition.y >= itemInfo.offset.y && currentPosition.y < itemInfo.offset.y + itemInfo.size.height
                                            }
                                            val item = info?.let { animatedVisible.getOrNull(it.index) }
                                            if (item != null) {
                                                val current = latestSelection.value
                                                if (current.isNotEmpty()) {
                                                    selected = if (item.uri in current) current - item.uri else current + item.uri
                                                } else {
                                                    highlightedMediaUri = null
                                                    viewerCurrentUri = item.uri
                                                    viewerStartUri = item.uri
                                                    viewerDisplayUri = item.uri
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
                                            val item = info?.let { animatedVisible.getOrNull(it.index) }
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
                            items(animatedVisible, key = { it.uri.toString() }) { item ->
                                Box {
                                    MediaThumbnail(
                                        item, item.uri in selected, item.uri == highlightedMediaUri, thumbnailLoader, thumbnailRevision
                                    )
                                }
                            }
                        }
                        }
                    }
                    }
                }
                AnimatedContent(
                    targetState = when {
                        selected.isNotEmpty() -> "media" to null
                        selectedFolder != null -> "folder" to selectedFolder
                        else -> "tabs" to null
                    },
                    transitionSpec = { simpleFadeTransition() },
                    modifier = Modifier.fillMaxWidth(),
                    label = "bottomActionTransition"
                ) { footerTarget ->
                    when (footerTarget.first) {
                        "media" -> {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF171717)).padding(horizontal = 2.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                BottomAction("Details", Icons.Default.Info, Modifier.weight(1f)) { media.firstOrNull { it.uri in selected }?.let { infoItem = it } }
                                BottomAction("Rename", Icons.Default.Edit, Modifier.weight(1f)) {
                                    val targets = media.filter { it.uri in selected }
                                    if (targets.size > 1) {
                                        renameItem = null
                                        renameMultipleItems = targets
                                        val firstName = targets.first().name
                                        renameText = if (firstName.lastIndexOf('.') > 0) firstName.substring(0, firstName.lastIndexOf('.')) else firstName
                                    } else {
                                        renameMultipleItems = emptyList()
                                        targets.firstOrNull()?.let { renameItem = it; renameText = it.name }
                                    }
                                }
                                BottomAction("Copy", Icons.Default.ContentCopy, Modifier.weight(1f)) { beginTransfer("Copy") }
                                BottomAction("Move", Icons.Default.DriveFileMove, Modifier.weight(1f)) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(context)) manageMediaInfoOpen = true else beginTransfer("Move")
                                }
                                BottomAction("Delete", Icons.Default.Delete, Modifier.weight(1f), tint = Color(0xFFFF6680)) { deleteMedia(media.filter { it.uri in selected }) }
                                BottomAction("Share", Icons.Default.Share, Modifier.weight(1f)) { shareMedia(media.filter { it.uri in selected }) }
                            }
                        }
                        "folder" -> {
                            val folderName = footerTarget.second.orEmpty()
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF171717)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                BottomAction("Details", Icons.Default.Info, Modifier.weight(1f)) { folderDetailsName = folderName }
                                BottomAction("Rename", Icons.Default.Edit, Modifier.weight(1f)) { folderRenameTarget = folderName; folderRenameText = folderName }
                                BottomAction(if (folderName in hiddenFolders) "Unhide" else "Hide", if (folderName in hiddenFolders) Icons.Default.Visibility else Icons.Default.VisibilityOff, Modifier.weight(1f)) { setFolderHidden(folderName, folderName !in hiddenFolders) }
                                BottomAction("Delete", Icons.Default.Delete, Modifier.weight(1f), tint = Color(0xFFFF6680)) { folderRemoveTarget = folderName }
                            }
                        }
                        else -> {
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                    }
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
                    title = { Text("Delete media in folder?") },
                    text = {
                        Text("Delete all photos and videos inside \"${folderName}\"? Only images and videos will be deleted. Other file types and the folder itself will be left untouched.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val folderMedia = media.filter { it.album == folderName }
                            folderRemoveTarget = null
                            selectedFolder = null
                            if (folderMedia.isNotEmpty()) deleteMedia(folderMedia)
                        }) { Text("Delete photos & videos", color = Color(0xFFFF6680)) }
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
            if (renameMultipleItems.isNotEmpty()) {
                val targets = renameMultipleItems
                AlertDialog(
                    onDismissRequest = { renameMultipleItems = emptyList() },
                    title = { Text("Rename ${targets.size} files") },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            label = { Text("Base name (numbered suffixes added)") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val baseName = renameText.trim().trimEnd(' ', '.')
                            if (baseName.isBlank()) {
                                statusMessage = "Enter a valid base name."
                            } else {
                                val actions = targets.mapIndexed { index, target ->
                                    val dot = target.name.lastIndexOf('.')
                                    val extension = if (dot > 0 && dot < target.name.lastIndex) target.name.substring(dot) else ""
                                    val enteredBase = baseName.trim().trimEnd(' ', '.')
                                    val base = if (extension.isNotBlank() && enteredBase.endsWith(extension, ignoreCase = true)) {
                                        enteredBase.dropLast(extension.length).trimEnd('.')
                                    } else {
                                        enteredBase
                                    }
                                    target to (base + " (" + (index + 1) + ")" + extension)
                                }
                                renameMediaItems(actions)
                            }
                            renameMultipleItems = emptyList()
                        }) { Text("Rename") }
                    },
                    dismissButton = { TextButton(onClick = { renameMultipleItems = emptyList() }) { Text("Cancel") } }
                )
            } else {
                renameItem?.let { item ->
                    AlertDialog(onDismissRequest = { renameItem = null }, title = { Text("Rename") }, text = {
                        OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("File name (extension kept)") })
                    }, confirmButton = { TextButton(onClick = {
                        val proposedName = finalDisplayName(item.name, renameText)
                        if (proposedName.isBlank()) {
                            statusMessage = "Enter a valid file name."
                        } else {
                            renameMediaItems(listOf(item to proposedName))
                        }
                        renameItem = null
                    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renameItem = null }) { Text("Cancel") } })
                }
            }
            statusMessage?.let { msg -> AlertDialog(onDismissRequest = { statusMessage = null }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { statusMessage = null }) { Text("OK") } }) }
        }
        }
        }
    }
}

@Composable
private fun GalleryMenuScreen(
    currentScope: String,
    currentContent: String,
    onClose: () -> Unit,
    onSelectScope: (String) -> Unit,
    onSelectContent: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val selectedColor = Color(0xFF3A2630)
    val normalColor = Color(0xFF191919)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close menu", tint = Color.White) }
            Text("Menu", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Text("Folder / All", color = Color.LightGray, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = { onSelectScope("Folder") },
                color = if (currentScope == "Folder") selectedColor else normalColor,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Folder", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = { onSelectScope("All") },
                color = if (currentScope == "All") selectedColor else normalColor,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Collections, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("All", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Text("Media / Doc", color = Color.LightGray, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = { onSelectContent("Media") },
                color = if (currentContent == "Media") selectedColor else normalColor,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Media", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = { onSelectContent("Doc") },
                color = if (currentContent == "Doc") selectedColor else normalColor,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Doc", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = onOpenSettings,
            color = normalColor,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Setting", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Playback and hidden folders", color = Color.LightGray, fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
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
    autoPlayNext: Boolean,
    rememberLastPlayed: Boolean,
    onToggleAutoPlayNext: (Boolean) -> Unit,
    onToggleRememberLastPlayed: (Boolean) -> Unit,
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
                PlaybackSettingRow(
                    title = "Auto-play next / Loop current",
                    subtitle = if (autoPlayNext) {
                        "On: play next video when it ends; images stay 5 seconds"
                    } else {
                        "Off: repeat the current video instead of moving to the next"
                    },
                    checked = autoPlayNext,
                    onCheckedChange = onToggleAutoPlayNext
                )
                PlaybackSettingRow(
                    title = "Remember last playback",
                    subtitle = "Reopen the last viewed item and resume video time",
                    checked = rememberLastPlayed,
                    onCheckedChange = onToggleRememberLastPlayed
                )
            }
        }
    }
}

@Composable
private fun PlaybackSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = Color(0xFF191919),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    // Two queue entries may overlap briefly when the user switches folders during decoding.
    // Lock on the destination so one thumbnail is never generated/written twice at once.
    return synchronized(target.absolutePath.intern()) {
        if (target.isFile && target.length() > 0L) return@synchronized false
        val directory = target.parentFile ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        var bitmap: Bitmap? = null
        val temporary = File(directory, target.name + ".tmp")
        try {
            bitmap = if (item.isVideo) createVideoThumbnail(item) else createImageThumbnail(item.uri)
            if (bitmap == null) return@synchronized false
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

private fun Context.hasDocumentAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
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
private fun MediaThumbnail(
    item: GalleryMedia,
    selected: Boolean,
    highlighted: Boolean,
    imageLoader: ImageLoader,
    thumbnailRevision: Int
) {
    val context = LocalContext.current
    val request = rememberThumbnailRequest(context, item, thumbnailRevision)
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            repeat(6) { beat ->
                flashAlpha.animateTo(if (beat % 2 == 0) 0.72f else 0f, animationSpec = tween(160))
            }
            flashAlpha.snapTo(0f)
        } else {
            flashAlpha.snapTo(0f)
        }
    }
    Box(Modifier.aspectRatio(0.78f).background(if (selected) Color(0xFF503040) else Color.DarkGray)) {
        AsyncImage(model = request,
            imageLoader = imageLoader, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x6633A6FF)), contentAlignment = Alignment.TopEnd) {
            Text("✓", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(5.dp))
        }
        if (flashAlpha.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color(0xFFFF4D7D).copy(alpha = flashAlpha.value * 0.26f)))
            Box(Modifier.fillMaxSize().padding(2.dp).border(3.dp, Color(0xFFFF4D7D).copy(alpha = flashAlpha.value)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelsViewer(
    items: List<GalleryMedia>,
    startIndex: Int,
    imageLoader: ImageLoader,
    autoPlayNext: Boolean,
    rememberLastPlayed: Boolean,
    onPageViewed: (Uri) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    var playing by remember { mutableStateOf(true) }
    var currentMediaZoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        currentMediaZoomed = false
    }

    LaunchedEffect(pagerState.currentPage, items) {
        items.getOrNull(pagerState.currentPage)?.let { onPageViewed(it.uri) }
    }

    LaunchedEffect(pagerState.currentPage, autoPlayNext, currentMediaZoomed) {
        if (currentMediaZoomed) return@LaunchedEffect
        val item = items.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (!item.isVideo && autoPlayNext) {
            val page = pagerState.currentPage
            delay(5_000)
            if (
                pagerState.currentPage == page &&
                items.isNotEmpty() &&
                !pagerState.isScrollInProgress &&
                !currentMediaZoomed
            ) {
                // Run scrolling in the remembered UI scope so this effect being restarted
                // when currentPage changes cannot cancel the animation halfway through.
                scope.launch {
                    pagerState.animateScrollToPage(if (page < items.lastIndex) page + 1 else 0)
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) { playing = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Avoid creating and preparing video decoders for neighboring pages.
            beyondViewportPageCount = 0
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                VideoPage(
                    item = item,
                    active = page == pagerState.currentPage,
                    playing = playing,
                    autoPlayNext = autoPlayNext,
                    rememberLastPlayed = rememberLastPlayed,
                        onEnded = {
                        if (autoPlayNext && !currentMediaZoomed && !pagerState.isScrollInProgress) {
                            scope.launch {
                                val current = pagerState.currentPage
                                if (!pagerState.isScrollInProgress && !currentMediaZoomed) {
                                    pagerState.animateScrollToPage(if (current < items.lastIndex) current + 1 else 0)
                                }
                            }
                        }
                    },
                    onTogglePlay = { playing = !playing },
                    onZoomChanged = { currentMediaZoomed = it }
                )
            } else {
                var zoom by remember(item.uri) { mutableFloatStateOf(1f) }
                var pan by remember(item.uri) { mutableStateOf(Offset.Zero) }
                var rotation by remember(item.uri) { mutableFloatStateOf(0f) }
                var rotationSnapJob by remember(item.uri) { mutableStateOf<Job?>(null) }
                var zoomAnimJob by remember(item.uri) { mutableStateOf<Job?>(null) }
                var screenFillActive by remember(item.uri) { mutableStateOf(false) }
                var viewportSize by remember(item.uri) { mutableStateOf(IntSize.Zero) }
                // Use AsyncImage for constraint-aware Coil rendering; keep decode bounded to screen size.
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current
                val imageWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx().coerceAtLeast(1) }
                val imageHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx().coerceAtLeast(1) }
                val imageRequest = remember(item.uri, imageWidthPx, imageHeightPx) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(imageWidthPx, imageHeightPx)
                        .crossfade(false)
                        .build()
                }
                var imageAspectRatio by remember(item.uri) { mutableFloatStateOf(0f) }
                val screenFillZoom = calculateScreenFillZoom(
                    viewportSize.width, viewportSize.height,
                    imageAspectRatio, 0f, rotation
                )
                val latestScreenFillZoom by rememberUpdatedState(screenFillZoom)
                var initialFitApplied by remember(item.uri) { mutableStateOf(false) }
                LaunchedEffect(item.uri, viewportSize, imageAspectRatio) {
                    if (!initialFitApplied && viewportSize.width > 0 && viewportSize.height > 0 && imageAspectRatio > 0f) {
                        pan = Offset.Zero
                        zoom = 1f
                        screenFillActive = false
                        currentMediaZoomed = false
                        initialFitApplied = true
                    }
                }
                AsyncImage(
                    model = imageRequest,
                    imageLoader = imageLoader,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize()
                        .onSizeChanged { viewportSize = it }
                        .pointerInput(item.uri) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                rotationSnapJob?.cancel()
                                rotationSnapJob = null
                                zoomAnimJob?.cancel()
                                zoomAnimJob = null
                                var usedMultiTouch = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pointers = event.changes.filter { it.pressed }
                                    if (pointers.isEmpty()) {
                                        if (usedMultiTouch) {
                                            val fromRotation = rotation
                                            val targetRotation = snapRotationToRightAngle(rotation)
                                            rotationSnapJob?.cancel()
                                            rotationSnapJob = scope.launch {
                                                val animator = Animatable(fromRotation)
                                                animator.animateTo(
                                                    targetRotation,
                                                    animationSpec = spring(
                                                        dampingRatio = 1f,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                ) {
                                                    rotation = value
                                                }
                                            }
                                        }
                                        break
                                    }
                                    if (pointers.size >= 2) {
                                        usedMultiTouch = true
                                        screenFillActive = false
                                        currentMediaZoomed = true
                                        val first = pointers[0]
                                        val second = pointers[1]
                                        val previousDistance = (first.previousPosition - second.previousPosition).getDistance()
                                        val currentDistance = (first.position - second.position).getDistance()
                                        if (previousDistance > 0.5f) {
                                            val oldZoom = zoom
                                            val newZoom = (oldZoom * (currentDistance / previousDistance)).coerceIn(0.25f, 5f)
                                            val oldCenter = (first.previousPosition + second.previousPosition) / 2f
                                            val newCenter = (first.position + second.position) / 2f
                                            val oldRotation = rotation
                                            val newRotation = oldRotation + calculateRotationDelta(
                                                first.previousPosition, second.previousPosition,
                                                first.position, second.position
                                            )
                                            pan = calculateTransformPan(
                                                currentPan = pan,
                                                oldZoom = oldZoom,
                                                newZoom = newZoom,
                                                oldRotation = oldRotation,
                                                newRotation = newRotation,
                                                oldCentroid = oldCenter,
                                                newCentroid = newCenter,
                                                containerSize = size
                                            )
                                            rotation = newRotation
                                            zoom = newZoom
                                        }
                                        pointers.forEach { it.consume() }
                                    } else if (usedMultiTouch) {
                                        val change = pointers.first()
                                        if (change.position != change.previousPosition) {
                                            if (zoom > 1.001f) {
                                                pan += change.position - change.previousPosition
                                            }
                                            // Consume the last finger until the full pinch/twist gesture ends.
                                            change.consume()
                                        }
                                    } else if (zoom > 1.001f) {
                                        val change = pointers.first()
                                        val delta = change.position - change.previousPosition
                                        // Vertical swipes stay available to the pager at every zoom/rotation.
                                        if (delta != Offset.Zero && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) {
                                            pan += Offset(delta.x, 0f)
                                            change.consume()
                                        }
                                    }
                                }
                                currentMediaZoomed = zoom > 1.001f || screenFillActive
                            }
                        }
                        .pointerInput(item.uri) {
                            detectTapGestures(onDoubleTap = {
                                val target = if (screenFillActive) 1f else latestScreenFillZoom
                                screenFillActive = !screenFillActive
                                currentMediaZoomed = target > 1.001f
                                zoomAnimJob?.cancel()
                                zoomAnimJob = scope.launch {
                                    val animator = Animatable(zoom)
                                    animator.animateTo(
                                        target.coerceIn(0.25f, 12f),
                                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                    ) { zoom = value }
                                }
                            })
                        }
                        .graphicsLayer {
                            val rotationFit = calculateRotationFitScale(viewportSize.width, viewportSize.height, rotation)
                            scaleX = zoom * rotationFit
                            scaleY = zoom * rotationFit
                            translationX = pan.x
                            translationY = pan.y
                            rotationZ = rotation
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        },
                    contentScale = ContentScale.Fit,
                    onState = { state ->
                        if (state is coil.compose.AsyncImagePainter.State.Success) {
                            val intrinsic = state.painter.intrinsicSize
                            if (
                                intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                                intrinsic.width > 0f && intrinsic.height > 0f
                            ) {
                                val aspect = intrinsic.width / intrinsic.height
                                if (aspect.isFinite() && aspect > 0f && imageAspectRatio != aspect) {
                                    imageAspectRatio = aspect
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPage(
    item: GalleryMedia,
    active: Boolean,
    playing: Boolean,
    autoPlayNext: Boolean,
    rememberLastPlayed: Boolean,
    onEnded: () -> Unit,
    onTogglePlay: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) {
        context.getSharedPreferences("instagallery_settings", Context.MODE_PRIVATE)
    }
    val initialPositionMs = if (
        rememberLastPlayed && prefs.getString("last_media_uri", null) == item.uri.toString()
    ) prefs.getLong("last_playback_position", 0L) else 0L

    val player = remember(item.uri, initialPositionMs) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
            repeatMode = if (autoPlayNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            volume = 1f
        }
    }
    val exoPlayer = player
    val rotationScope = rememberCoroutineScope()
    var zoom by remember(item.uri) { mutableFloatStateOf(1f) }
    var pan by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var rotation by remember(item.uri) { mutableFloatStateOf(0f) }
    var rotationSnapJob by remember(item.uri) { mutableStateOf<Job?>(null) }
    var zoomAnimJob by remember(item.uri) { mutableStateOf<Job?>(null) }
    var screenFillActive by remember(item.uri) { mutableStateOf(false) }
    var videoAspectRatio by remember(item.uri) { mutableFloatStateOf(0f) }
    var viewportSize by remember(item.uri) { mutableStateOf(IntSize.Zero) }
    val screenFillZoom = calculateScreenFillZoom(
        viewportSize.width, viewportSize.height, videoAspectRatio, 1f, rotation
    )
    val latestScreenFillZoom by rememberUpdatedState(screenFillZoom)
    var initialFitApplied by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(active, viewportSize, videoAspectRatio) {
        if (active && !initialFitApplied && viewportSize.width > 0 && viewportSize.height > 0 && videoAspectRatio > 0f) {
            pan = Offset.Zero
            zoom = 1f
            screenFillActive = false
            onZoomChanged(false)
            initialFitApplied = true
        }
    }
    var seekBarVisible by remember(item.uri) { mutableStateOf(false) }
    var fastForwarding by remember(item.uri) { mutableStateOf(false) }
    var suppressTapAfterFastForward by remember(item.uri) { mutableStateOf(false) }
    var currentPositionMs by remember(item.uri) { mutableLongStateOf(initialPositionMs) }
    var durationMs by remember(item.uri) { mutableLongStateOf(item.durationMs.coerceAtLeast(0L)) }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            if (event == Lifecycle.Event.ON_RESUME && active) player.playWhenReady = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            if (rememberLastPlayed) {
                prefs.edit()
                    .putString("last_media_uri", item.uri.toString())
                    .putLong("last_playback_position", player.currentPosition.coerceAtLeast(0L))
                    .apply()
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    LaunchedEffect(active, playing) {
        player.volume = 1f
        player.playWhenReady = active && playing
    }

    LaunchedEffect(autoPlayNext, player) {
        player.repeatMode = if (autoPlayNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    LaunchedEffect(player, active, rememberLastPlayed) {
        while (active) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
                ?: item.durationMs.coerceAtLeast(0L)
            if (rememberLastPlayed) {
                prefs.edit()
                    .putString("last_media_uri", item.uri.toString())
                    .putLong("last_playback_position", currentPositionMs)
                    .apply()
            }
            delay(250)
        }
    }

    DisposableEffect(player, active, autoPlayNext) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && active && autoPlayNext) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(item.uri) {
                detectTapGestures(
                    onTap = {
                        if (active) {
                            if (suppressTapAfterFastForward) {
                                suppressTapAfterFastForward = false
                            } else {
                                onTogglePlay()
                            }
                        }
                    },
                    onDoubleTap = {
                        if (active) {
                            val target = if (screenFillActive) 1f else latestScreenFillZoom
                            screenFillActive = !screenFillActive
                            onZoomChanged(target > 1.001f || screenFillActive)
                            zoomAnimJob?.cancel()
                            zoomAnimJob = rotationScope.launch {
                                val animator = Animatable(zoom)
                                animator.animateTo(
                                    target.coerceIn(0.25f, 12f),
                                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                                ) { zoom = value }
                            }
                        }
                    }
                )
            }
            .pointerInput(item.uri, active, player) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    rotationSnapJob?.cancel()
                    rotationSnapJob = null
                    zoomAnimJob?.cancel()
                    zoomAnimJob = null

                    val downPosition = down.position
                    var didMove = false
                    var horizontalSeeking = false
                    var multiTouch = false
                    var longPressTriggered = false
                    var finished = false
                    var seekStartPosition = player.currentPosition.coerceAtLeast(0L)

                    while (!finished) {
                        val event = if (!didMove && !longPressTriggered && !multiTouch) {
                            withTimeoutOrNull(450L) { awaitPointerEvent(PointerEventPass.Initial) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }

                        if (event == null) {
                            longPressTriggered = true
                            suppressTapAfterFastForward = true
                            fastForwarding = true
                            player.setPlaybackSpeed(2f)
                            continue
                        }

                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.isEmpty()) {
                            if (multiTouch) {
                                val fromRotation = rotation
                                val targetRotation = snapRotationToRightAngle(rotation)
                                rotationSnapJob?.cancel()
                                rotationSnapJob = rotationScope.launch {
                                    val animator = Animatable(fromRotation)
                                    animator.animateTo(
                                        targetRotation,
                                        animationSpec = spring(
                                            dampingRatio = 1f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ) {
                                        rotation = value
                                    }
                                }
                            }
                            finished = true
                            continue
                        }

                        if (pointers.size >= 2) {
                            multiTouch = true
                            screenFillActive = false
                            zoomAnimJob?.cancel()
                            zoomAnimJob = null
                            onZoomChanged(true)
                            if (longPressTriggered) {
                                player.setPlaybackSpeed(1f)
                                fastForwarding = false
                            }
                            val first = pointers[0]
                            val second = pointers[1]
                            val previousDistance = (first.previousPosition - second.previousPosition).getDistance()
                            val currentDistance = (first.position - second.position).getDistance()
                            if (previousDistance > 0.5f) {
                                val oldZoom = zoom
                                val newZoom = (oldZoom * (currentDistance / previousDistance)).coerceIn(0.25f, 5f)
                                val oldCenter = (first.previousPosition + second.previousPosition) / 2f
                                val newCenter = (first.position + second.position) / 2f
                                val oldRotation = rotation
                                val newRotation = oldRotation + calculateRotationDelta(
                                    first.previousPosition, second.previousPosition,
                                    first.position, second.position
                                )
                                pan = calculateTransformPan(
                                    currentPan = pan,
                                    oldZoom = oldZoom,
                                    newZoom = newZoom,
                                    oldRotation = oldRotation,
                                    newRotation = newRotation,
                                    oldCentroid = oldCenter,
                                    newCentroid = newCenter,
                                    containerSize = size
                                )
                                rotation = newRotation
                                zoom = newZoom
                            }
                            pointers.forEach { it.consume() }
                            seekBarVisible = false
                            continue
                        }

                        if (multiTouch) {
                            val change = pointers.first()
                            if (change.position != change.previousPosition) {
                                if (zoom > 1.001f) {
                                    pan += change.position - change.previousPosition
                                }
                                // Keep the last remaining finger in the pinch gesture from scrolling the pager.
                                change.consume()
                            }
                            continue
                        }

                        val change = pointers.first()
                        val dx = change.position.x - downPosition.x
                        val dy = change.position.y - downPosition.y

                        if (!didMove && maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > viewConfiguration.touchSlop) {
                            didMove = true
                            horizontalSeeking = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                            if (horizontalSeeking) {
                                seekStartPosition = player.currentPosition.coerceAtLeast(0L)
                                seekBarVisible = true
                                if (longPressTriggered) {
                                    player.setPlaybackSpeed(1f)
                                    fastForwarding = false
                                }
                            }
                        }

                        if (horizontalSeeking) {
                            val duration = player.duration.takeIf {
                                it > 0L && it != androidx.media3.common.C.TIME_UNSET
                            } ?: item.durationMs
                            if (duration > 0L && size.width > 0) {
                                val deltaMs = ((dx / size.width.toFloat()) * duration.toFloat()).toLong()
                                currentPositionMs = (seekStartPosition + deltaMs).coerceIn(0L, duration)
                                player.seekTo(currentPositionMs)
                            }
                            seekBarVisible = true
                            change.consume()
                        }
                        // Don't consume vertical motion: the pager must still scroll when media is zoomed/rotated.
                    }

                    if (longPressTriggered && !horizontalSeeking) {
                        player.setPlaybackSpeed(1f)
                        fastForwarding = false
                        // Guard against the tap detector interpreting release as a normal tap.
                        rotationScope.launch {
                            delay(450)
                            suppressTapAfterFastForward = false
                        }
                    }
                    seekBarVisible = false
                    onZoomChanged(zoom > 1.001f || screenFillActive)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null, false) as PlayerView).apply {
                    this.player = exoPlayer
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isClickable = false
                    isFocusable = false
                }
            },
            update = {
                it.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it }.graphicsLayer {
                val rotationFit = calculateRotationFitScale(viewportSize.width, viewportSize.height, rotation)
                scaleX = zoom * rotationFit
                scaleY = zoom * rotationFit
                translationX = pan.x
                translationY = pan.y
                rotationZ = rotation
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
            }
        )

        if (fastForwarding) {
            Surface(
                color = Color(0xBB111111),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    "» 2×",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }

        if (seekBarVisible) {
            val safeDuration = durationMs.coerceAtLeast(1L)
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .background(Color.LightGray.copy(alpha = 0.55f))
                ) {
                    Box(
                        Modifier.fillMaxWidth(
                            (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                        ).fillMaxHeight().background(Color(0xFFFF4D7D))
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatPlaybackTime(currentPositionMs)} / ${formatPlaybackTime(safeDuration)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

private fun calculateScreenFillZoom(
    viewportWidth: Int,
    viewportHeight: Int,
    mediaWidthOrAspect: Float,
    mediaHeight: Float,
    rotationDegrees: Float
): Float {
    if (viewportWidth <= 0 || viewportHeight <= 0) return 2f
    val mediaAspect = if (mediaHeight > 0f && mediaWidthOrAspect > 0f) {
        mediaWidthOrAspect / mediaHeight
    } else {
        mediaWidthOrAspect
    }
    if (!mediaAspect.isFinite() || mediaAspect <= 0f) {
        return (2f / calculateRotationFitScale(viewportWidth, viewportHeight, rotationDegrees)).coerceIn(1.8f, 12f)
    }
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cos = kotlin.math.abs(kotlin.math.cos(radians))
    val sin = kotlin.math.abs(kotlin.math.sin(radians))
    val rotatedAspect = (mediaAspect * cos + sin) / (cos + mediaAspect * sin).coerceAtLeast(0.001)
    val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
    val aspectScale = maxOf(rotatedAspect / viewportAspect, viewportAspect / rotatedAspect)
    val rotationFit = calculateRotationFitScale(viewportWidth, viewportHeight, rotationDegrees)
    // Counteract rotation-fit shrinkage so the animated zoom can cover the screen after rotation.
    return maxOf(1.8f, aspectScale.toFloat() / rotationFit.coerceAtLeast(0.01f)).coerceIn(1.8f, 12f)
}

private fun snapRotationToRightAngle(angle: Float): Float {
    // Preserve the nearest equivalent turn (e.g. 359° -> 360°, not 0°) for a short, smooth snap.
    return kotlin.math.round(angle / 90f) * 90f
}

private fun calculateRotationFitScale(width: Int, height: Int, rotationDegrees: Float): Float {
    if (width <= 0 || height <= 0) return 1f
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cos = kotlin.math.abs(kotlin.math.cos(radians))
    val sin = kotlin.math.abs(kotlin.math.sin(radians))
    val rotatedWidth = width * cos + height * sin
    val rotatedHeight = height * cos + width * sin
    val fitScale = minOf(width / rotatedWidth, height / rotatedHeight).toFloat().coerceIn(0.01f, 1f)
    // Keep a little breathing room so a rotated portrait video does not touch or clip at screen edges.
    val rotationMargin = 1f - 0.15f * sin.toFloat()
    return (fitScale * rotationMargin).coerceIn(0.01f, 1f)
}

private fun calculateRotationDelta(
    oldFirst: Offset,
    oldSecond: Offset,
    newFirst: Offset,
    newSecond: Offset
): Float {
    val oldAngle = atan2(oldSecond.y - oldFirst.y, oldSecond.x - oldFirst.x)
    val newAngle = atan2(newSecond.y - newFirst.y, newSecond.x - newFirst.x)
    var delta = Math.toDegrees((newAngle - oldAngle).toDouble()).toFloat()
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

private fun calculateTransformPan(
    currentPan: Offset,
    oldZoom: Float,
    newZoom: Float,
    oldRotation: Float,
    newRotation: Float,
    oldCentroid: Offset,
    newCentroid: Offset,
    containerSize: IntSize
): Offset {
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val oldFit = calculateRotationFitScale(containerSize.width, containerSize.height, oldRotation)
    val newFit = calculateRotationFitScale(containerSize.width, containerSize.height, newRotation)
    val oldScale = (oldZoom * oldFit).coerceAtLeast(0.001f)
    val newScale = newZoom * newFit
    val scaleChange = newScale / oldScale

    // Preserve the image point under the fingers while scale and rotation change together.
    val radians = Math.toRadians((newRotation - oldRotation).toDouble())
    val cos = kotlin.math.cos(radians).toFloat()
    val sin = kotlin.math.sin(radians).toFloat()
    val previousVector = oldCentroid - center - currentPan
    val rotatedVector = Offset(
        previousVector.x * cos - previousVector.y * sin,
        previousVector.x * sin + previousVector.y * cos
    )
    return newCentroid - center - rotatedVector * scaleChange
}

private fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
