package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.DownloadState
import io.ymusic.player.PlayableItem
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** How long a song must keep playing before it is auto-downloaded, so skipping through a queue downloads nothing. */
private const val LISTEN_BEFORE_DOWNLOAD_MS = 10_000L

/**
 * [AppSettings.downloadAutoPlaying]: while on, every YouTube song that plays for a few seconds is queued as a
 * Quick download (audio only), unless a file of it is already in the download folder or a download of it is already queued, running
 * (a finished one does not count: delete the file and it downloads again). Needs a usable download folder; with none it does nothing (it never opens the folder picker
 * mid-playback). Off = no effect at all.
 */
@Composable
internal fun AutoDownloadPlayingHost(graph: AppGraph) {
    val downloads = graph.downloads ?: return
    val enabled by remember(graph) { graph.settings.flow(AppSettings.downloadAutoPlaying) }.collectAsState()
    if (!enabled) return
    val queue by graph.player.queue.collectAsState()
    val current = queue.currentItem as? PlayableItem.YtMedia ?: return
    if (current.live) return
    val saved by graph.quickDownload.collectAsState()

    LaunchedEffect(current.videoId) {
        delay(LISTEN_BEFORE_DOWNLOAD_MS)
        // The download folder itself is the source of truth (a file there, whatever app put it there). Only a
        // shell that cannot list the folder, or a listing that fails, falls back to the Library's ids.
        if (!hasUsableDownloadLocation(graph)) return@LaunchedEffect
        if (alreadyInDownloadFolder(graph, current)) return@LaunchedEffect
        val pending = downloads.snapshot().any {
            it.videoId == current.videoId && (it.state.isActive || it.state == DownloadState.PAUSED)
        }
        if (pending) return@LaunchedEffect
        val preset = if (saved.format.kind == DownloadKind.VIDEO) {
            saved.copy(format = if (downloads.ffmpegAvailable) DownloadFormat.AUDIO_WEBM else DownloadFormat.M4A, height = null)
        } else {
            saved
        }
        downloads.enqueue(preset.request(current.videoId, current.title ?: current.videoId, current.thumbnailUrl, graph.mp3SourceBitrate()))
    }
}

/** True when [item] is already saved: a file in the download folder named like the download would be (the
 *  title's stem, optionally `_N` from a duplicate), else — when the folder can't be listed — the Library holds it. */
private suspend fun alreadyInDownloadFolder(graph: AppGraph, item: PlayableItem.YtMedia): Boolean {
    val list = graph.listDownloadFolderNames
    val folder = graph.settings.flow(AppSettings.downloadLocation).value
    if (list != null) {
        val names = try {
            list(folder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // unreadable right now: can't verify the folder, so use the Library's record below
        }
        if (names != null) {
            val stem = DownloadFileNames.fromTitle(item.title ?: item.videoId).ifEmpty { item.videoId }.lowercase()
            val duplicate = Regex("^" + Regex.escape(stem) + "_\\d+$")
            return names.any { name ->
                val base = name.substringBeforeLast('.').lowercase()
                base == stem || duplicate.matches(base)
            }
        }
    }
    return item.videoId in (graph.mediaStore?.youtubeVideoIds() ?: emptySet())
}
