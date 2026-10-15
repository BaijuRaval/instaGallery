package com.baiju.instagallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class GalleryMedia(val uri: Uri, val name: String, val mimeType: String, val album: String, val dateAdded: Long, val size: Long) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

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
    var infoItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var renameItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var renameText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = context.hasMediaPermission()
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        media = context.queryGalleryMedia()
        selected = emptySet()
    }

    fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions)
    }
    fun refresh() { media = context.queryGalleryMedia() }
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
        val intent = Intent(if (items.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.size == 1) items.first().mimeType else "*/*"
            if (items.size == 1) putExtra(Intent.EXTRA_STREAM, items.first().uri)
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share media"))
    }

    LaunchedEffect(hasPermission) { if (hasPermission) media = context.queryGalleryMedia() }
    val visible = remember(media, selectedAlbum) { if (selectedAlbum == "All media") media else media.filter { it.album == selectedAlbum } }

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
    BackHandler(enabled = viewerStartUri != null) { viewerStartUri = null; selected = emptySet() }

    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF101010), surface = Color(0xFF191919), primary = Color(0xFFFF4D7D))) {
        val viewerStart = viewerStartUri
        if (viewerStart != null && visible.isNotEmpty()) {
            val startIndex = visible.indexOfFirst { it.uri == viewerStart }.coerceAtLeast(0)
            ReelsViewer(visible, startIndex) { viewerStartUri = null }
        } else {
            Column(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Collections, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (selected.isNotEmpty()) "${selected.size} selected" else "instaGallery", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${visible.size} items", color = Color.LightGray, fontSize = 12.sp)
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                }
                if (selected.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shareMedia(media.filter { it.uri in selected }) }) { Icon(Icons.Default.Share, null); Text("Share") }
                        TextButton(onClick = {
                            val first = media.firstOrNull { it.uri in selected }
                            if (first != null) { infoItem = first }
                        }) { Icon(Icons.Default.Info, null); Text("Details") }
                        TextButton(onClick = {
                            val first = media.firstOrNull { it.uri in selected }
                            if (first != null) { renameItem = first; renameText = first.name }
                        }) { Icon(Icons.Default.Edit, null); Text("Rename") }
                        TextButton(onClick = {
                            val targets = media.filter { it.uri in selected }
                            val count = targets.count { context.copyMediaToGallery(it) }
                            statusMessage = "Copied $count of ${targets.size} items to instaGallery"
                            refresh()
                        }) { Icon(Icons.Default.ContentCopy, null); Text("Copy") }
                        TextButton(onClick = {
                            val targets = media.filter { it.uri in selected }
                            val count = targets.count { context.copyMediaToGallery(it) }
                            if (count == targets.size) deleteMedia(targets)
                            else statusMessage = "Copied $count of ${targets.size}; originals kept for safety"
                            refresh()
                        }) { Icon(Icons.Default.DriveFileMove, null); Text("Move") }
                        IconButton(onClick = { deleteMedia(media.filter { it.uri in selected }) }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6680)) }
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
                    visible.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No photos or videos found", color = Color.LightGray) }
                    else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visible, key = { it.uri.toString() }) { item ->
                            val isSelected = item.uri in selected
                            MediaThumbnail(item, isSelected,
                                onClick = {
                                    if (selected.isNotEmpty()) selected = if (isSelected) selected - item.uri else selected + item.uri
                                    else viewerStartUri = item.uri
                                },
                                onLongClick = { selected = if (isSelected) selected - item.uri else selected + item.uri })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF171717)).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NavigationBarItem(selected = tab == "Folders", onClick = { tab = "Folders"; albumMenu = true }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Folders") })
                    NavigationBarItem(selected = tab == "Reel", onClick = { tab = "Reel"; selectedAlbum = "All media" }, icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Reel") })
                }
            }
            if (settingsOpen) AlertDialog(onDismissRequest = { settingsOpen = false }, title = { Text("Settings") }, text = { Text("instaGallery • Offline media gallery\nYour media stays on this device.") }, confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } })
            if (albumMenu) {
                val albums = listOf("All media") + media.map { it.album }.distinct().sorted()
                AlertDialog(onDismissRequest = { albumMenu = false }, title = { Text("Folders") }, text = {
                    Column(Modifier.heightIn(max = 400.dp)) {
                        albums.forEach { album -> TextButton(onClick = { selectedAlbum = album; tab = "Folders"; albumMenu = false }) { Text(album, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    }
                }, confirmButton = { TextButton(onClick = { albumMenu = false }) { Text("Close") } })
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

private fun Context.hasMediaPermission(): Boolean {
    val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    return permissions.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.queryGalleryMedia(): List<GalleryMedia> {
    val result = mutableListOf<GalleryMedia>()
    listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, "bucket_display_name")
        try {
            contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val albumCol = cursor.getColumnIndex("bucket_display_name")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += GalleryMedia(ContentUris.withAppendedId(collection, id),
                        if (nameCol >= 0) cursor.getString(nameCol) ?: "Media" else "Media",
                        if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else "",
                        if (albumCol >= 0) cursor.getString(albumCol) ?: "Other" else "Other",
                        if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L)
                }
            }
        } catch (_: SecurityException) {}
    }
    return result.distinctBy { it.uri }.sortedByDescending { it.dateAdded }
}

private fun Context.copyMediaToGallery(item: GalleryMedia): Boolean {
    val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val values = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, if (item.isVideo) "Movies/instaGallery" else "Pictures/instaGallery")
    }
    return try {
        val dest = contentResolver.insert(collection, values) ?: return false
        val input = contentResolver.openInputStream(item.uri) ?: return false
        val output = contentResolver.openOutputStream(dest) ?: return false
        input.use { i -> output.use { o -> i.copyTo(o) } }
        true
    } catch (_: Exception) { false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(item: GalleryMedia, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.aspectRatio(0.78f).background(if (selected) Color(0xFF503040) else Color.DarkGray).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(model = ImageRequest.Builder(context).data(item.uri).apply { if (item.isVideo) videoFrameMillis(1000) }.crossfade(true).build(),
            contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x6633A6FF)), contentAlignment = Alignment.TopEnd) {
            Text("✓", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(5.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelsViewer(items: List<GalleryMedia>, startIndex: Int, onClose: () -> Unit) {
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
                AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(item.uri).crossfade(true).build(), contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
    Box(Modifier.fillMaxSize().pointerInput(item.uri, active) { androidx.compose.foundation.gestures.detectTapGestures(onTap = { if (active) onTogglePlay() }) }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; player = exoPlayer; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            update = { it.player = exoPlayer }, modifier = Modifier.fillMaxSize())
    }
}
