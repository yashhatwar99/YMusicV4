package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.DownloadState
import io.ymusic.player.PlayableItem
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import kotlinx.coroutines.delay

/** How long a song must keep playing before it is auto-downloaded, so skipping through a queue downloads nothing. */
private const val LISTEN_BEFORE_DOWNLOAD_MS = 10_000L

/**
 * [AppSettings.downloadAutoPlaying]: while on, every YouTube song that plays for a few seconds is queued as a
 * Quick download (audio only), unless the Library already holds it or a download of it is already queued, running
 * or finished. Needs a usable download folder; with none it does nothing (it never opens the folder picker
 * mid-playback). Off = no effect at all.
 */
@Composable
internal fun AutoDownloadPlayingHost(graph: AppGraph) {
    val downloads = graph.downloads ?: return
    val store = graph.mediaStore ?: return
    val enabled by remember(graph) { graph.settings.flow(AppSettings.downloadAutoPlaying) }.collectAsState()
    if (!enabled) return
    val queue by graph.player.queue.collectAsState()
    val current = queue.currentItem as? PlayableItem.YtMedia ?: return
    if (current.live) return
    val saved by graph.quickDownload.collectAsState()

    LaunchedEffect(current.videoId) {
        delay(LISTEN_BEFORE_DOWNLOAD_MS)
        // Read at decision time, not from a cached set: the Library's ids load asynchronously.
        if (current.videoId in store.youtubeVideoIds()) return@LaunchedEffect
        val pending = downloads.snapshot().any {
            it.videoId == current.videoId && it.state != DownloadState.FAILED && it.state != DownloadState.CANCELED
        }
        if (pending) return@LaunchedEffect
        if (!hasUsableDownloadLocation(graph)) return@LaunchedEffect
        val preset = if (saved.format.kind == DownloadKind.VIDEO) {
            saved.copy(format = if (downloads.ffmpegAvailable) DownloadFormat.AUDIO_WEBM else DownloadFormat.M4A, height = null)
        } else {
            saved
        }
        downloads.enqueue(preset.request(current.videoId, current.title ?: current.videoId, current.thumbnailUrl, graph.mp3SourceBitrate()))
    }
}
