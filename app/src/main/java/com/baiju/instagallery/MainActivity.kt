package com.baiju.instagallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private data class GalleryMedia(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val album: String,
    val dateAdded: Long
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent { GalleryApp() }
    }
}

@Composable
private fun GalleryApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasMediaPermission()) }
    var media by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    var selectedAlbum by remember { mutableStateOf("All media") }
    var viewerStartUri by remember { mutableStateOf<Uri?>(null) }
    var albumMenu by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = context.hasMediaPermission()
    }

    fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) media = context.queryGalleryMedia()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF101010),
        surface = Color(0xFF191919),
        primary = Color(0xFFFF4D7D)
    )) {
        val visible = remember(media, selectedAlbum) {
            if (selectedAlbum == "All media") media else media.filter { it.album == selectedAlbum }
        }
        val viewerStart = viewerStartUri
        if (viewerStart != null && visible.isNotEmpty()) {
            val startIndex = visible.indexOfFirst { it.uri == viewerStart }.coerceAtLeast(0)
            ReelsViewer(items = visible, startIndex = startIndex, onClose = { viewerStartUri = null })
        } else {
            GalleryScreen(
                media = media,
                hasPermission = hasPermission,
                selectedAlbum = selectedAlbum,
                onRequestPermission = ::requestPermissions,
                onSelectAlbum = { selectedAlbum = it },
                onOpen = { viewerStartUri = it.uri },
                albumMenu = albumMenu,
                onAlbumMenu = { albumMenu = it }
            )
        }
    }
}

private fun Context.hasMediaPermission(): Boolean {
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return permissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.queryGalleryMedia(): List<GalleryMedia> {
    val result = mutableListOf<GalleryMedia>()
    val collections = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
    collections.forEach { collection ->
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            "bucket_display_name"
        )
        try {
            contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val albumCol = cursor.getColumnIndex("bucket_display_name")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Media" else "Media"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "Other" else "Other"
                    result += GalleryMedia(
                        ContentUris.withAppendedId(collection, id), name, mime, album, date
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission may have been limited or revoked.
        }
    }
    return result.distinctBy { it.uri }.sortedByDescending { it.dateAdded }
}

@Composable
private fun GalleryScreen(
    media: List<GalleryMedia>,
    hasPermission: Boolean,
    selectedAlbum: String,
    onRequestPermission: () -> Unit,
    onSelectAlbum: (String) -> Unit,
    onOpen: (GalleryMedia) -> Unit,
    albumMenu: Boolean,
    onAlbumMenu: (Boolean) -> Unit
) {
    val visible = remember(media, selectedAlbum) {
        if (selectedAlbum == "All media") media else media.filter { it.album == selectedAlbum }
    }
    val albums = remember(media) { listOf("All media") + media.map { it.album }.distinct().sorted() }

    Column(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Collections, null, tint = Color(0xFFFF4D7D), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("instaGallery", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${visible.size} items", color = Color.LightGray, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { onAlbumMenu(true) }) {
                    Icon(Icons.Default.Folder, "Choose album", tint = Color.White)
                }
                DropdownMenu(expanded = albumMenu, onDismissRequest = { onAlbumMenu(false) }) {
                    albums.forEach { album ->
                        DropdownMenuItem(
                            text = { Text(album) },
                            onClick = { onSelectAlbum(album); onAlbumMenu(false) }
                        )
                    }
                }
            }
        }

        when {
            !hasPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.Image, null, tint = Color.LightGray, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your photos and videos stay on this device.", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) { Text("Allow gallery access") }
                }
            }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos or videos found", color = Color.LightGray)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visible, key = { it.uri.toString() }) { item ->
                    Box(Modifier.aspectRatio(0.78f).clickable { onOpen(item) }) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (item.isVideo) {
                            Icon(
                                Icons.Default.PlayArrow, "Video",
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelsViewer(items: List<GalleryMedia>, startIndex: Int, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    var muted by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    val current = items.getOrNull(pagerState.currentPage)
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) { playing = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                VideoPage(
                    item = item,
                    active = page == pagerState.currentPage,
                    muted = muted,
                    playing = playing,
                    onEnded = {
                        scope.launch {
                            val next = if (pagerState.currentPage < items.lastIndex) {
                                pagerState.currentPage + 1
                            } else 0
                            pagerState.animateScrollToPage(next)
                        }
                    },
                    onTogglePlay = { playing = !playing }
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: "Media", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pagerState.currentPage + 1} / ${items.size}", color = Color.LightGray, fontSize = 12.sp)
            }
            if (current?.isVideo == true) {
                IconButton(onClick = { muted = !muted }) {
                    Icon(
                        if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        "Toggle mute", tint = Color.White
                    )
                }
            }
        }
        if (current?.isVideo == true && !playing) {
            Icon(Icons.Default.PlayArrow, "Paused", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(72.dp))
        }
        Text(
            "Swipe up for next",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
        )
    }
}

@Composable
private fun VideoPage(
    item: GalleryMedia,
    active: Boolean,
    muted: Boolean,
    playing: Boolean,
    onEnded: () -> Unit,
    onTogglePlay: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            if (event == Lifecycle.Event.ON_RESUME && active) player.playWhenReady = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    LaunchedEffect(active, muted, playing) {
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = active && playing
    }

    DisposableEffect(player, active) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && active) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val exoPlayer = player
    Box(
        Modifier.fillMaxSize().pointerInput(item.uri, active) {
            detectTapGestures(onTap = { if (active) onTogglePlay() })
        },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = exoPlayer
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )
    }
}
