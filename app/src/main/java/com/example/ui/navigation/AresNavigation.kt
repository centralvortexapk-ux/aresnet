package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class AresDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    SEARCH("search", "Buscar", Icons.Default.Search),
    DOWNLOADS("downloads", "Descargas", Icons.Default.Download),
    PLAYER("player", "Reproductor", Icons.Default.PlayCircle),
    NETWORK("network", "Red P2P", Icons.Default.Hub),
    LEGAL("legal", "Legal & Filtros", Icons.Default.Gavel)
}
