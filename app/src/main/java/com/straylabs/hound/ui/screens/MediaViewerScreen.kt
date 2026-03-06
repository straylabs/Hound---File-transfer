package com.straylabs.hound.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.straylabs.hound.client.FileItem
import com.straylabs.hound.util.FileUtils
import kotlinx.coroutines.delay

// ---- Re-export file-type helpers for call sites in the same package ----

fun isImageFile(name: String): Boolean = FileUtils.isImageFile(name)
fun isVideoFile(name: String): Boolean = FileUtils.isVideoFile(name)
fun isAudioFile(name: String): Boolean = FileUtils.isAudioFile(name)
fun isPreviewable(name: String): Boolean = FileUtils.isPreviewable(name)

// ---- Entry point ----

/**
 * Full-screen media viewer. Dispatches to the appropriate sub-viewer based on file type.
 *
 * @param item        The file to preview.
 * @param baseUrl     Server base URL (e.g. "http://192.168.1.5:8080").
 * @param imageItems  All image files in the same directory (for swipe navigation).
 * @param onDismiss   Called when the user wants to close the preview.
 * @param onDownload  Called when the user taps the download button (null = hide button).
 */
@Composable
fun MediaPreview(
    item: FileItem,
    baseUrl: String,
    imageItems: List<FileItem>,
    credentials: Pair<String, String>? = null,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            FileUtils.isImageFile(item.name) -> {
                ImageViewer(
                    item = item,
                    baseUrl = baseUrl,
                    imageItems = imageItems,
                    onDismiss = onDismiss,
                    onDownload = onDownload
                )
            }
            FileUtils.isVideoFile(item.name) -> {
                VideoPlayer(
                    item = item,
                    baseUrl = baseUrl,
                    credentials = credentials,
                    onDismiss = onDismiss,
                    onDownload = onDownload
                )
            }
            FileUtils.isAudioFile(item.name) -> {
                AudioPlayer(
                    item = item,
                    baseUrl = baseUrl,
                    credentials = credentials,
                    onDismiss = onDismiss,
                    onDownload = onDownload
                )
            }
        }
    }
}

// ---- Image Viewer ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(
    item: FileItem,
    baseUrl: String,
    imageItems: List<FileItem>,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    val startIndex = imageItems.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { imageItems.size }
    var showBars by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(
                url = "${baseUrl.trimEnd('/')}/${imageItems[page].path.trimStart('/')}",
                contentDescription = imageItems[page].name,
                onTap = { showBars = !showBars }
            )
        }

        // Top bar
        AnimatedVisibility(
            visible = showBars,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Text(
                        text = imageItems.getOrNull(pagerState.currentPage)?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onDownload != null) {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = "Download",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }

        // Page indicator
        if (imageItems.size > 1) {
            AnimatedVisibility(
                visible = showBars,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${imageItems.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun ZoomableImage(
    url: String,
    contentDescription: String,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) offset += panChange
        else offset = Offset.Zero
    }

    val context = LocalContext.current
    val imageRequest = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ---- Video Player ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlayer(
    item: FileItem,
    baseUrl: String,
    credentials: Pair<String, String>? = null,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val videoUrl = "${baseUrl.trimEnd('/')}/${item.path.trimStart('/')}"

    val exoPlayer = remember(videoUrl) {
        buildExoPlayer(context, videoUrl, credentials)
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top-left: back button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        // Top-right: download button
        if (onDownload != null) {
            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            ) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = "Download",
                    tint = Color.White
                )
            }
        }
    }
}

// ---- Audio Player ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayer(
    item: FileItem,
    baseUrl: String,
    credentials: Pair<String, String>? = null,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val audioUrl = "${baseUrl.trimEnd('/')}/${item.path.trimStart('/')}"

    val exoPlayer = remember(audioUrl) {
        buildExoPlayer(context, audioUrl, credentials)
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // isPlaying and duration are event-driven via Player.Listener (no polling needed)
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var duration by remember { mutableLongStateOf(maxOf(exoPlayer.duration, 1L)) }
    // position changes continuously — lightweight 500ms poll is still appropriate
    var position by remember { mutableLongStateOf(0L) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = maxOf(exoPlayer.duration, 1L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back / download button row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            if (onDownload != null) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "Download", tint = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Album art placeholder
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // File name
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Progress slider
        Slider(
            value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
            onValueChange = { fraction ->
                exoPlayer.seekTo((fraction * duration).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
        )

        // Time row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(position), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Text(formatMs(duration), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { exoPlayer.seekTo((position - 10_000).coerceAtLeast(0L)) }) {
                Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            IconButton(onClick = { exoPlayer.seekTo((position + 10_000).coerceAtMost(duration)) }) {
                Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildExoPlayer(
    context: android.content.Context,
    url: String,
    credentials: Pair<String, String>?
): ExoPlayer {
    val dsFactory = DefaultHttpDataSource.Factory().apply {
        if (credentials != null) {
            val (u, p) = credentials
            val token = Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
            setDefaultRequestProperties(mapOf("Authorization" to "Basic $token"))
        }
    }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
