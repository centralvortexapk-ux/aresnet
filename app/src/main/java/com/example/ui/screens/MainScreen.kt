package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moderation.ReportContentDialog
import com.example.monetization.AdMobBanner
import com.example.network.SearchResultHit
import com.example.ui.viewmodel.AresViewModel

enum class MainNavigationTab(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    HOME("home", "Inicio", Icons.Default.Home),
    SEARCH("search", "Buscar", Icons.Default.Search),
    LIBRARY("library", "Tu Biblioteca", Icons.Default.Bookmarks)
}

enum class SearchCategory(
    val id: String,
    val title: String,
    val icon: ImageVector
) {
    ALL("ALL", "Todo", Icons.Default.Folder),
    MUSIC("MUSIC", "Música", Icons.Default.Audiotrack),
    VIDEOS("VIDEO", "Videos", Icons.Default.VideoLibrary),
    FILES("SOFTWARE", "Archivos", Icons.Default.Folder),
    IMAGES("IMAGE", "Imágenes", Icons.Default.Image),
    AUDIOBOOKS("AUDIOBOOK", "Audiolibros", Icons.Default.MenuBook),
    PODCASTS("PODCAST", "Podcasts", Icons.Default.Podcasts),
    LIVE("LIVE", "Eventos en vivo", Icons.Default.LiveTv)
}

data class BrowseCategoryCard(
    val title: String,
    val category: SearchCategory,
    val queryHint: String,
    val colorStart: Long,
    val colorEnd: Long,
    val emoji: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AresViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(MainNavigationTab.HOME) }
    var selectedSearchCategory by remember { mutableStateOf(SearchCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val isListening by viewModel.isServerListening.collectAsState()
    val port by viewModel.serverPort.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val miniPlayerVisible by remember {
        derivedStateOf { playerState.title.isNotEmpty() && playerState.trackHash.isNotEmpty() }
    }

    // Modals for secondary features
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var showPlayerSheet by remember { mutableStateOf(false) }
    var showNetworkSheet by remember { mutableStateOf(false) }
    var showLegalSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var reportingHit by remember { mutableStateOf<SearchResultHit?>(null) }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "ARESNET",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isListening) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isListening) "P2P MESH :$port" else "STANDBY",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = if (isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Downloads badge action
                        val activeDownloadsCount = downloads.count { it.status == "DOWNLOADING" }
                        IconButton(
                            onClick = { showDownloadsSheet = true },
                            modifier = Modifier.testTag("top_bar_downloads_btn")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (activeDownloadsCount > 0) {
                                        Badge { Text("$activeDownloadsCount") }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Descargas",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // P2P Network / Sovereign identity action
                        IconButton(
                            onClick = { showNetworkSheet = true },
                            modifier = Modifier.testTag("top_bar_network_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Red P2P",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Legal & Moderation action
                        IconButton(
                            onClick = { showLegalSheet = true },
                            modifier = Modifier.testTag("top_bar_legal_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gavel,
                                contentDescription = "Términos y Legal",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }

                        // Settings, How-it-works, and Theme action
                        IconButton(
                            onClick = { showSettingsSheet = true },
                            modifier = Modifier.testTag("top_bar_settings_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Configuración",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )

                // Fixed Top Ad Space - Permanently anchored at the top, guaranteed non-overlapping
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    AdMobBanner()
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        bottomBar = {
            Column {
                // Persistent Spotify-style Floating Mini-Player bar when audio is playing/loaded
                AnimatedVisibility(
                    visible = miniPlayerVisible,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    SpotifyMiniPlayer(
                        title = playerState.title,
                        artist = playerState.artist,
                        coverSymbol = playerState.coverSymbol,
                        isPlaying = playerState.isPlaying,
                        bufferedPosition = playerState.bufferedPositionMs,
                        duration = playerState.durationMs,
                        currentPosition = playerState.currentPositionMs,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onClick = { showPlayerSheet = true }
                    )
                }

                // Spotify-style Bottom Navigation Bar
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("main_bottom_navigation"),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    MainNavigationTab.values().forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("tab_${tab.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (selectedTab) {
                MainNavigationTab.HOME -> {
                    HomeScreen(
                        viewModel = viewModel,
                        onNavigateToPlayer = { showPlayerSheet = true }
                    )
                }

                MainNavigationTab.SEARCH -> {
                    SearchExploreSection(
                        searchQuery = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            viewModel.onSearchQueryChanged(it)
                        },
                        selectedCategory = selectedSearchCategory,
                        onCategorySelect = { cat ->
                            selectedSearchCategory = cat
                            viewModel.setCategory(cat.id)
                        },
                        searchResults = searchResults,
                        isSearching = isSearching,
                        onPlayTrack = { hit ->
                            viewModel.streamServer.let { server ->
                                viewModel.mediaPlayer.playP2PStream(
                                    localProxyUrl = server.getStreamUrl(hit.fileSha256),
                                    fileSha256 = hit.fileSha256,
                                    title = hit.fileName,
                                    artist = "Peer ${hit.peerNodeId}",
                                    album = hit.category,
                                    coverSymbol = "⚡",
                                    knownDurationMs = 210000
                                )
                            }
                            showPlayerSheet = true
                        },
                        onDownload = { hit ->
                            viewModel.startDownload(hit)
                            showDownloadsSheet = true
                        },
                        onReport = { hit -> reportingHit = hit }
                    )
                }

                MainNavigationTab.LIBRARY -> {
                    LibraryScreen(
                        viewModel = viewModel,
                        onNavigateToPlayer = { showPlayerSheet = true }
                    )
                }
            }
        }
    }

    // Modal Bottom Sheets
    if (showDownloadsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDownloadsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DownloadsScreen(
                viewModel = viewModel,
                onNavigateToPlayer = {
                    showDownloadsSheet = false
                    showPlayerSheet = true
                }
            )
        }
    }

    if (showPlayerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlayerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            PlayerScreen(viewModel = viewModel)
        }
    }

    if (showNetworkSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNetworkSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            NetworkScreen(viewModel = viewModel)
        }
    }

    if (showLegalSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLegalSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            LegalScreen(viewModel = viewModel)
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SettingsScreen(viewModel = viewModel)
        }
    }

    // Report Dialog
    reportingHit?.let { hit ->
        ReportContentDialog(
            fileName = hit.fileName,
            fileSha256 = hit.fileSha256,
            onDismiss = { reportingHit = null },
            onConfirmReport = { reason ->
                viewModel.reportContent(hit.fileSha256, hit.fileName, reason)
                reportingHit = null
            }
        )
    }
}

@Composable
fun SearchExploreSection(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: SearchCategory,
    onCategorySelect: (SearchCategory) -> Unit,
    searchResults: List<SearchResultHit>,
    isSearching: Boolean,
    onPlayTrack: (SearchResultHit) -> Unit,
    onDownload: (SearchResultHit) -> Unit,
    onReport: (SearchResultHit) -> Unit
) {
    // Spotify-style Browse Category Cards
    val browseCards = listOf(
        BrowseCategoryCard(
            title = "Música & Frecuencias",
            category = SearchCategory.MUSIC,
            queryHint = "Ares",
            colorStart = 0xFF00E5FF,
            colorEnd = 0xFF2563EB,
            emoji = "🎵"
        ),
        BrowseCategoryCard(
            title = "Videos & Clips P2P",
            category = SearchCategory.VIDEOS,
            queryHint = "Demo",
            colorStart = 0xFFEC4899,
            colorEnd = 0xFF8B5CF6,
            emoji = "🎬"
        ),
        BrowseCategoryCard(
            title = "Podcasts Cripto & Tech",
            category = SearchCategory.PODCASTS,
            queryHint = "Podcast",
            colorStart = 0xFFF59E0B,
            colorEnd = 0xFFD97706,
            emoji = "🎙️"
        ),
        BrowseCategoryCard(
            title = "Audiolibros Sci-Fi",
            category = SearchCategory.AUDIOBOOKS,
            queryHint = "Manifesto",
            colorStart = 0xFF10B981,
            colorEnd = 0xFF059669,
            emoji = "📚"
        ),
        BrowseCategoryCard(
            title = "Eventos en Vivo P2P",
            category = SearchCategory.LIVE,
            queryHint = "Live",
            colorStart = 0xFFEF4444,
            colorEnd = 0xFFDC2626,
            emoji = "📡"
        ),
        BrowseCategoryCard(
            title = "Software & Archivos",
            category = SearchCategory.FILES,
            queryHint = "OpenP2P",
            colorStart = 0xFF6366F1,
            colorEnd = 0xFF4338CA,
            emoji = "💾"
        ),
        BrowseCategoryCard(
            title = "Imágenes & Gráficos 4K",
            category = SearchCategory.IMAGES,
            queryHint = "Wallpaper",
            colorStart = 0xFF14B8A6,
            colorEnd = 0xFF0D9488,
            emoji = "🖼️"
        ),
        BrowseCategoryCard(
            title = "Lo-Fi & Chill Sockets",
            category = SearchCategory.MUSIC,
            queryHint = "Beats",
            colorStart = 0xFF8B5CF6,
            colorEnd = 0xFF6D28D9,
            emoji = "☕"
        )
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        // Search Bar Input
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("mock_search_bar"),
                placeholder = {
                    Text("¿Qué quieres escuchar o buscar en la red P2P?")
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.testTag("clear_search_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }

        // Full Category Filter Chips: Todo, Música, Videos, Archivos, Imágenes, Audiolibros, Podcasts, Eventos en vivo
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SearchCategory.values()) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategorySelect(cat) },
                        label = { Text(cat.title) },
                        leadingIcon = {
                            Icon(imageVector = cat.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        // Swarm Results (if searching or query has hits)
        if (searchQuery.isNotEmpty() || searchResults.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedCategory.title.uppercase()} • ${searchResults.size} RESULTADOS P2P",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isSearching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Sondeando enjambre...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            items(searchResults, key = { it.fileSha256 }) { hit ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SearchResultCard(
                        hit = hit,
                        onDownload = { onDownload(hit) },
                        onReport = { onReport(hit) }
                    )
                }
            }
        }

        // SPOTIFY-STYLE: EXPLORAR TODO (BROWSE PLAYLISTS & CATEGORIES)
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = "Explorar todo",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Categorías y listas disponibles en el enjambre de AresNet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 2-Column Category Grid
                for (i in browseCards.indices step 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpotifyBrowseCard(
                            card = browseCards[i],
                            onClick = {
                                onCategorySelect(browseCards[i].category)
                                onQueryChange(browseCards[i].queryHint)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (i + 1 < browseCards.size) {
                            SpotifyBrowseCard(
                                card = browseCards[i + 1],
                                onClick = {
                                    onCategorySelect(browseCards[i + 1].category)
                                    onQueryChange(browseCards[i + 1].queryHint)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpotifyBrowseCard(
    card: BrowseCategoryCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(95.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("browse_card_${card.title.take(8)}"),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(card.colorStart), Color(card.colorEnd))
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(110.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = card.emoji,
                fontSize = 32.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun SpotifyMiniPlayer(
    title: String,
    artist: String,
    coverSymbol: String,
    isPlaying: Boolean,
    bufferedPosition: Long,
    duration: Long,
    currentPosition: Long,
    onTogglePlay: () -> Unit,
    onClick: () -> Unit
) {
    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    val bufferProgress = if (duration > 0) bufferedPosition.toFloat() / duration.toFloat() else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("spotify_mini_player"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = coverSymbol.ifEmpty { "🎵" }, fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$artist • Streaming P2P",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.testTag("mini_player_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Progressive P2P Streaming Buffer Progress Line
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
