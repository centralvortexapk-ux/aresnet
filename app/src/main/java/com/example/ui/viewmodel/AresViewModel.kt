package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AresNetApplication
import com.example.crypto.NodeIdentity
import com.example.data.entity.BlockedHashEntity
import com.example.data.entity.DownloadEntity
import com.example.data.entity.SharedFileEntity
import com.example.data.entity.TrackMetadataEntity
import com.example.network.NetworkStats
import com.example.network.SearchResultHit
import com.example.network.model.PeerNode
import com.example.player.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AresViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AresNetApplication
    private val repository = app.repository
    private val nodeEngine = app.nodeEngine
    private val identityManager = app.identityManager
    private val database = app.database
    val streamServer = app.streamServer
    val mediaPlayer = app.mediaPlayer

    // Sovereign Identity
    private val _nodeIdentity = MutableStateFlow(identityManager.getOrGenerateIdentity())
    val nodeIdentity: StateFlow<NodeIdentity> = _nodeIdentity.asStateFlow()

    // Navigation & Category Filters
    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultHit>>(emptyList())
    val searchResults: StateFlow<List<SearchResultHit>> = _searchResults.asStateFlow()

    // Spotify-style Music Catalog Flows from Room
    val catalogTracks: StateFlow<List<TrackMetadataEntity>> = database.trackMetadataDao().getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<TrackMetadataEntity>> = database.trackMetadataDao().getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayedTracks: StateFlow<List<TrackMetadataEntity>> = database.trackMetadataDao().getRecentlyPlayed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Persistent Room Flows
    val downloads: StateFlow<List<DownloadEntity>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sharedFiles: StateFlow<List<SharedFileEntity>> = repository.allSharedFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedHashes: StateFlow<List<BlockedHashEntity>> = repository.allBlockedHashes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Station Categories
    val stationCategories = database.stationCategoryDao().getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = nodeEngine.connectedPeers
    val networkStats: StateFlow<NetworkStats> = nodeEngine.stats
    val isServerListening: StateFlow<Boolean> = nodeEngine.isListening
    val serverPort: StateFlow<Int> = nodeEngine.currentPort
    val publicAddress: StateFlow<com.example.network.PublicAddressInfo> = nodeEngine.publicAddress

    fun refreshPublicAddress() {
        nodeEngine.refreshPublicAddress()
        _userMessage.value = "Consultando servidores STUN públicos..."
    }

    fun connectToPeer(host: String, port: Int) {
        viewModelScope.launch {
            _userMessage.value = "Conectando a nodo P2P en $host:$port..."
            val result = nodeEngine.connectToRemotePeer(host, port)
            result.onSuccess { peer ->
                _userMessage.value = "¡Conectado exitosamente a nodo remoto ${peer.nodeId}!"
            }.onFailure { error ->
                _userMessage.value = "Fallo de conexión: ${error.message}"
            }
        }
    }

    // P2P Tracker (Option 1 Rendezvous Server)
    val trackerClient = app.trackerClient
    val trackerStatus: StateFlow<com.example.network.tracker.TrackerStatus> = trackerClient.trackerStatus

    fun setTrackerUrl(url: String) {
        trackerClient.setTrackerUrl(url)
        _userMessage.value = "URL del Tracker configurada"
    }

    fun announceToTracker() {
        viewModelScope.launch {
            _userMessage.value = "Anunciando nodo en el Tracker P2P..."
            val result = trackerClient.announce(localPort = nodeEngine.currentPort.value)
            if (result.success) {
                _userMessage.value = "¡Sincronizado! IP pública detectada: ${result.detectedPublicIp}"
            } else {
                _userMessage.value = result.message
            }
        }
    }

    // Official GitHub Repository Configuration
    private val _repositoryUrl = MutableStateFlow("https://github.com/centralvortexapk-ux/aresnet")
    val repositoryUrl: StateFlow<String> = _repositoryUrl.asStateFlow()

    fun setRepositoryUrl(url: String) {
        _repositoryUrl.value = url.trim()
        _userMessage.value = "URL del repositorio actualizada"
    }

    fun resetRepositoryUrl() {
        _repositoryUrl.value = "https://github.com/centralvortexapk-ux/aresnet"
        _userMessage.value = "URL restablecida a la oficial de GitHub"
    }

    // Media3 Player State
    val playerState: StateFlow<PlayerState> = mediaPlayer.playerState

    // Toast/Snackbar notifications
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private var searchJob: Job? = null

    init {
        performSearch("", "ALL")
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
        performSearch(_searchQuery.value, category)
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
        performSearch(newQuery, _selectedCategory.value)
    }

    fun performSearch(query: String = _searchQuery.value, category: String = _selectedCategory.value) {
        searchJob?.cancel()
        _isSearching.value = true
        val hits = mutableListOf<SearchResultHit>()
        _searchResults.value = emptyList()

        searchJob = viewModelScope.launch {
            // Announce to tracker to ensure we're registered with our shared files
            try {
                trackerClient.announce(localPort = nodeEngine.currentPort.value)
            } catch (e: Exception) {
            }
            
            // Discover peers for all shared files from other users and active tracker hashes
            viewModelScope.launch {
                try {
                    // Discover all peers from tracker for any active hashes
                    nodeEngine.discoverAllPeersFromTracker(trackerClient)
                    // Also ensure discovery for our own shared files
                    val allShared = database.sharedFileDao().getAllSharedFilesList()
                    for (file in allShared) {
                        nodeEngine.discoverPeersFromTracker(trackerClient, file.fileSha256)
                    }
                } catch (e: Exception) {
                }
            }
            
            // Also discover peers for search query if it's a hash
            if (query.isNotEmpty() && query.length == 64 && query.matches(Regex("[a-fA-F0-9]+"))) {
                viewModelScope.launch {
                    try {
                        nodeEngine.discoverPeersFromTracker(trackerClient, query)
                    } catch (e: Exception) {
                    }
                }
            }
            
            // Wait a bit for connections to establish
            kotlinx.coroutines.delay(500)
            
            nodeEngine.broadcastSearch(query, category) { hit ->
                if (!hits.any { it.fileSha256 == hit.fileSha256 }) {
                    hits.add(hit)
                    _searchResults.value = hits.toList()
                }
            }
            _isSearching.value = false
        }
    }

    /**
     * Progressive P2P streaming playback (Spotify-style low latency):
     * Consumes localhost stream server while chunks buffer in background.
     */
    fun playP2PTrack(track: TrackMetadataEntity) {
        viewModelScope.launch {
            // Check if hash is blocked by moderation / DMCA
            if (database.blockedHashDao().isHashBlocked(track.fileSha256)) {
                _userMessage.value = "Pista bloqueada por aviso de derechos de autor (DMCA)"
                return@launch
            }

            val proxyUrl = streamServer.getStreamUrl(track.fileSha256)
            mediaPlayer.playP2PStream(
                localProxyUrl = proxyUrl,
                fileSha256 = track.fileSha256,
                title = track.title,
                artist = track.artist,
                album = track.album,
                coverSymbol = track.coverSymbol,
                knownDurationMs = track.durationMs
            )
            database.trackMetadataDao().recordPlayback(track.fileSha256)
        }
    }

    fun toggleFavorite(fileSha256: String) {
        viewModelScope.launch {
            database.trackMetadataDao().toggleFavorite(fileSha256)
        }
    }

    fun downloadTrackForOffline(track: TrackMetadataEntity) {
        viewModelScope.launch {
            val hit = SearchResultHit(
                fileSha256 = track.fileSha256,
                fileName = "${track.title.replace(' ', '_')}.mp3",
                fileSize = track.fileSize,
                category = "MUSIC",
                totalChunks = track.totalChunks,
                chunkHashes = listOf(),
                seedersCount = 12,
                peerHost = "127.0.0.1",
                peerPort = nodeEngine.currentPort.value,
                peerNodeId = "ares-stream-seeder"
            )
            repository.startOrResumeDownload(
                hit = hit,
                onBlocked = {
                    _userMessage.value = "Descarga denegada: Pista bloqueada por infracción de copyright (DMCA)"
                }
            )
            _userMessage.value = "Descargando para modo Offline: ${track.title}"
        }
    }

    fun startDownload(hit: SearchResultHit) {
        repository.startOrResumeDownload(
            hit = hit,
            onBlocked = {
                _userMessage.value = "Descarga denegada: Archivo bloqueado por infracción de políticas / copyright"
            }
        )
        _userMessage.value = "Descarga iniciada: ${hit.fileName}"
    }

    fun pauseDownload(fileSha256: String) {
        repository.pauseDownload(fileSha256)
    }

    fun resumeDownload(fileSha256: String) {
        repository.resumeDownload(fileSha256)
    }

    fun cancelDownload(fileSha256: String) {
        repository.cancelDownload(fileSha256)
    }

    fun reportContent(fileSha256: String, fileName: String, reason: String) {
        repository.reportContent(fileSha256, fileName, reason)
        // Refresh search results to remove reported file
        _searchResults.value = _searchResults.value.filterNot { it.fileSha256 == fileSha256 }
        _userMessage.value = "Contenido reportado y bloqueado exitosamente"
    }

    fun playMediaFile(filePath: String, title: String) {
        val file = File(filePath)
        if (file.exists()) {
            mediaPlayer.playFile(file, title)
        } else {
            _userMessage.value = "El archivo todavía no está completo para reproducir"
        }
    }

    fun togglePlayPause() {
        mediaPlayer.togglePlayPause()
    }

    fun shareLocalFile(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val contentResolver = app.contentResolver
                var fileName = "shared_file_${System.currentTimeMillis()}"
                var fileSize = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) fileName = name
                        }
                        if (sizeIndex != -1) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }

                val sharedDir = File(app.filesDir, "shared_library").apply { mkdirs() }
                val destFile = File(sharedDir, fileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (fileSize == 0L) {
                    fileSize = destFile.length()
                }

                val sha256 = com.example.crypto.ChunkIntegrity.sha256(destFile.readBytes())

                // Moderation check against blocked DMCA / illegal hashes
                if (database.blockedHashDao().isHashBlocked(sha256)) {
                    destFile.delete()
                    _userMessage.value = "No se puede compartir: El archivo está en la lista de bloqueo (DMCA/Políticas)"
                    return@launch
                }

                val category = when {
                    fileName.endsWith(".mp3", true) || fileName.endsWith(".flac", true) ||
                            fileName.endsWith(".ogg", true) || fileName.endsWith(".wav", true) -> "MUSIC"
                    fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) ||
                            fileName.endsWith(".webm", true) -> "VIDEOS"
                    fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) ||
                            fileName.endsWith(".webp", true) -> "IMAGES"
                    fileName.endsWith(".pdf", true) || fileName.endsWith(".epub", true) -> "AUDIOBOOKS"
                    else -> "FILES"
                }

                val chunkSize = 256 * 1024
                val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)

                val sharedEntity = SharedFileEntity(
                    fileSha256 = sha256,
                    fileName = fileName,
                    filePath = destFile.absolutePath,
                    fileSize = fileSize,
                    category = category,
                    totalChunks = totalChunks,
                    chunkHashesJson = "[]",
                    isSeeding = true
                )

                database.sharedFileDao().insertOrUpdate(sharedEntity)
                _userMessage.value = "¡Archivo compartido en la red P2P!: $fileName"
                
                // Re-announce to tracker with updated shared files
                viewModelScope.launch {
                    try {
                        trackerClient.announce(localPort = nodeEngine.currentPort.value)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            } catch (e: Exception) {
                _userMessage.value = "Error al compartir archivo: ${e.localizedMessage ?: "Error desconocido"}"
            }
        }
    }

    fun unshareFile(fileSha256: String) {
        viewModelScope.launch {
            val entity = database.sharedFileDao().getByHash(fileSha256)
            if (entity != null) {
                database.sharedFileDao().delete(entity)
                _userMessage.value = "Archivo retirado de la red P2P"
                
                // Re-announce to tracker with updated shared files
                viewModelScope.launch {
                    try {
                        trackerClient.announce(localPort = nodeEngine.currentPort.value)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }
    }

    // Theme Configuration (Dark, Light, System)
    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        val modeName = when (mode) {
            ThemeMode.DARK -> "Tema oscuro activado"
            ThemeMode.LIGHT -> "Tema claro activado"
            ThemeMode.SYSTEM -> "Tema del sistema activado"
        }
        _userMessage.value = modeName
    }

    // Stream Cache Size and Clearing
    private val _streamCacheSize = MutableStateFlow(streamServer.getCacheSizeBytes())
    val streamCacheSize: StateFlow<Long> = _streamCacheSize.asStateFlow()

    fun refreshCacheSize() {
        _streamCacheSize.value = streamServer.getCacheSizeBytes()
    }

    fun clearStreamCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val freed = streamServer.clearAllCache()
            _streamCacheSize.value = streamServer.getCacheSizeBytes()
            val freedMb = String.format(java.util.Locale.US, "%.1f", freed / (1024.0 * 1024.0))
            _userMessage.value = "Caché de streaming liberada: $freedMb MB"
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }
}

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

