package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Hub
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.StationCategoryEntity
import com.example.data.entity.TrackMetadataEntity
import com.example.ui.viewmodel.AresViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column as ComposeColumn

data class MusicStation(
    val id: String,
    val name: String,
    val subtitle: String,
    val colorStart: Long,
    val colorEnd: Long,
    val iconEmoji: String,
    val sampleTrackHash: String
)

@Composable
fun HomeScreen(
    viewModel: AresViewModel,
    onNavigateToPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val catalogTracks by viewModel.catalogTracks.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayedTracks.collectAsState()
    val favorites by viewModel.favoriteTracks.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val stationCategories by viewModel.stationCategories.collectAsState()
    val isAresConnected by viewModel.isAresNetworkConnected.collectAsState()

    val displayTracks = catalogTracks

    // Recommended Radio Stations (Spotify Radio Style)
    val stations = listOf(
        MusicStation(
            id = "station_synth",
            name = "Radio Synthwave 2026",
            subtitle = "Con Vortex Mesh & Neon Peers",
            colorStart = 0xFF00E5FF,
            colorEnd = 0xFF3B82F6,
            iconEmoji = "⚡",
            sampleTrackHash = displayTracks.getOrNull(0)?.fileSha256 ?: ""
        ),
        MusicStation(
            id = "station_lofi",
            name = "Radio Lo-Fi Nodos Chill",
            subtitle = "Beats para programar y relajarse",
            colorStart = 0xFF8B5CF6,
            colorEnd = 0xFFEC4899,
            iconEmoji = "☕",
            sampleTrackHash = displayTracks.getOrNull(1)?.fileSha256 ?: ""
        ),
        MusicStation(
            id = "station_classical",
            name = "Estación Cyber Clásica",
            subtitle = "Sinfonías descentralizadas P2P",
            colorStart = 0xFF10B981,
            colorEnd = 0xFF059669,
            iconEmoji = "🌌",
            sampleTrackHash = displayTracks.getOrNull(2)?.fileSha256 ?: ""
        ),
        MusicStation(
            id = "station_ambient",
            name = "Estación Deep Ambient",
            subtitle = "Frecuencias espaciales y relax",
            colorStart = 0xFFF59E0B,
            colorEnd = 0xFFEF4444,
            iconEmoji = "🛡️",
            sampleTrackHash = displayTracks.getOrNull(3)?.fileSha256 ?: ""
        )
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP SECTION: Title Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = "Inicio",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        AresNetworkSwitchCard(
            isAresConnected = isAresConnected,
            onToggle = { enabled ->
                viewModel.toggleAresNetwork(enabled)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // TOP SECTION: LazyVerticalGrid for recent music folders (2 columns)
        val quickFolders = displayTracks.take(6)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(195.dp)
                .padding(horizontal = 16.dp)
                .testTag("recent_music_folders_grid"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(quickFolders, key = { it.fileSha256 }) { track ->
                SpotifyQuickFolderCard(
                    track = track,
                    isPlaying = playerState.isPlaying && playerState.trackHash == track.fileSha256,
                    onPlay = {
                        viewModel.playP2PTrack(track)
                        onNavigateToPlayer()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // LOWER SECTION: LazyColumn for recommended stations and playlists below
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("recommended_stations_column"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Categorías por defecto
            if (stationCategories.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = "Categorías",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(stationCategories, key = { it.id }) { category ->
                                CategoryStationCard(
                                    category = category,
                                    onClick = { }
                                )
                            }
                        }
                    }
                }
            }

            // Catálogo P2P
            item {
                Column {
                    Text(
                        text = "Catálogo P2P",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(displayTracks, key = { it.fileSha256 }) { track ->
                            SpotifyAlbumCard(
                                track = track,
                                isPlaying = playerState.isPlaying && playerState.trackHash == track.fileSha256,
                                onPlay = {
                                    viewModel.playP2PTrack(track)
                                    onNavigateToPlayer()
                                }
                            )
                        }
                    }
                }
            }

        if (recentlyPlayed.isNotEmpty()) {
            item {
                Text(
                    text = "Escuchado recientemente",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            items(recentlyPlayed, key = { it.fileSha256 }) { track ->
                SpotifyTrackRow(
                    track = track,
                    isPlaying = playerState.isPlaying && playerState.trackHash == track.fileSha256,
                    onPlay = {
                        viewModel.playP2PTrack(track)
                        onNavigateToPlayer()
                    },
                    onToggleFavorite = { viewModel.toggleFavorite(track.fileSha256) },
                    onDownloadOffline = { viewModel.downloadTrackForOffline(track) }
                )
            }
        }

        if (displayTracks.isNotEmpty()) {
            items(displayTracks, key = { it.fileSha256 }) { track ->
                SpotifyTrackRow(
                    track = track,
                    isPlaying = playerState.isPlaying && playerState.trackHash == track.fileSha256,
                    onPlay = {
                        viewModel.playP2PTrack(track)
                        onNavigateToPlayer()
                    },
                    onToggleFavorite = { viewModel.toggleFavorite(track.fileSha256) },
                    onDownloadOffline = { viewModel.downloadTrackForOffline(track) }
                )
            }
        } else {
            item {
                Text(
                    text = "No hay contenido disponible. Comparte archivos o busca en la red P2P.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        }
    }
}

@Composable
fun SpotifyQuickFolderCard(
    track: TrackMetadataEntity,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onPlay)
            .testTag("quick_${track.title.take(8)}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(track.coverGradientStart), Color(track.coverGradientEnd))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = track.coverSymbol, fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            if (isPlaying) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
fun CategoryStationCard(
    category: StationCategoryEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(155.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("category_${category.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(category.colorStart), Color(category.colorEnd))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = category.iconEmoji, fontSize = 44.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = category.name,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = category.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SpotifyAlbumCard(
    track: TrackMetadataEntity,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onPlay)
            .testTag("album_${track.title.take(8)}")
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(track.coverGradientStart), Color(track.coverGradientEnd))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = track.coverSymbol, fontSize = 48.sp)

            // Spotify-style Green/Cyan floating play button
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(38.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Reproducir streaming P2P",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${track.artist} • ${track.genre}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SpotifyTrackRow(
    track: TrackMetadataEntity,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadOffline: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .testTag("track_row_${track.title.take(8)}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(track.coverGradientStart), Color(track.coverGradientEnd))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = track.coverSymbol, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Favorite Button
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Offline Download Button
            IconButton(
                onClick = onDownloadOffline,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Guardar offline",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun AresNetworkSwitchCard(
    isAresConnected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("ares_network_switch_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isAresConnected) Color(0xFF10B981).copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAresConnected) Color(0xFF10B981).copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = if (isAresConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Red Ares Global",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isAresConnected) "Conectado • Sincronización P2P activa" else "Desconectado • Pulsa para conectar",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = if (isAresConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = isAresConnected,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF10B981),
                    checkedTrackColor = Color(0xFF10B981).copy(alpha = 0.5f)
                ),
                modifier = Modifier.testTag("ares_network_switch")
            )
        }
    }
}
