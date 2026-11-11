package com.example.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "AresNet P2P Stream",
    val album: String = "",
    val coverSymbol: String = "🎵",
    val trackHash: String = "",
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    val currentFilePath: String = "",
    val isStreaming: Boolean = false
)

class AresMediaPlayer(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        initPlayer()
    }

    private fun initPlayer() {
        if (exoPlayer != null) return

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) startProgressTracker() else progressJob?.cancel()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            _playerState.value = _playerState.value.copy(isBuffering = true)
                        }
                        Player.STATE_READY -> {
                            _playerState.value = _playerState.value.copy(
                                isBuffering = false,
                                durationMs = if (duration > 0) duration else _playerState.value.durationMs
                            )
                        }
                        Player.STATE_ENDED -> {
                            _playerState.value = _playerState.value.copy(
                                isPlaying = false,
                                isBuffering = false,
                                currentPositionMs = duration
                            )
                            progressJob?.cancel()
                        }
                        Player.STATE_IDLE -> {
                            _playerState.value = _playerState.value.copy(isBuffering = false)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _playerState.value = _playerState.value.copy(
                        hasError = true,
                        errorMessage = error.localizedMessage ?: "Error de reproducción P2P",
                        isPlaying = false,
                        isBuffering = false
                    )
                }
            })
        }
    }

    /**
     * Plays a progressive P2P audio stream fed by the local HTTP proxy (Spotify-style buffer).
     */
    fun playP2PStream(
        localProxyUrl: String,
        fileSha256: String,
        title: String,
        artist: String,
        album: String = "AresNet Stream",
        coverSymbol: String = "🎵",
        knownDurationMs: Long = 180000
    ) {
        initPlayer()
        val mediaUri = Uri.parse(localProxyUrl)

        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()

        _playerState.value = _playerState.value.copy(
            title = title,
            artist = artist,
            album = album,
            coverSymbol = coverSymbol,
            trackHash = fileSha256,
            currentFilePath = localProxyUrl,
            durationMs = knownDurationMs,
            isStreaming = true,
            hasError = false,
            errorMessage = ""
        )

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun playFile(file: File, title: String? = null, artist: String? = null) {
        if (!file.exists()) {
            _playerState.value = _playerState.value.copy(
                hasError = true,
                errorMessage = "Archivo no encontrado en almacenamiento local"
            )
            return
        }

        initPlayer()
        val mediaUri = Uri.fromFile(file)
        val displayTitle = title ?: file.nameWithoutExtension.replace('_', ' ')
        val displayArtist = artist ?: "AresNet Peer Media"

        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setArtist(displayArtist)
                    .build()
            )
            .build()

        _playerState.value = _playerState.value.copy(
            title = displayTitle,
            artist = displayArtist,
            currentFilePath = file.absolutePath,
            isStreaming = false,
            hasError = false,
            errorMessage = ""
        )

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
    }

    fun stop() {
        exoPlayer?.stop()
        progressJob?.cancel()
        _playerState.value = _playerState.value.copy(
            isPlaying = false,
            currentPositionMs = 0
        )
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _playerState.value = _playerState.value.copy(
                        currentPositionMs = player.currentPosition.coerceAtLeast(0),
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
                        durationMs = if (player.duration > 0) player.duration else _playerState.value.durationMs
                    )
                }
                delay(400)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
