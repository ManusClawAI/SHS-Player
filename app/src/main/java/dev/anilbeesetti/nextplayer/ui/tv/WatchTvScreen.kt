package dev.anilbeesetti.nextplayer.ui.tv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 8 — Redesigned Watch TV screen.
 *
 * Top-level tabs are now the five categories requested by the spec:
 * Bangladesh · Sports · News · Popular · Free Channels.
 *
 * Within each category, channels are loaded in parallel from every playlist
 * that maps to that category (e.g. Bangladesh → bd.m3u only; Popular →
 * Movies + Kids + Music + India).
 *
 * Channels are then grouped by their M3U `group-title` attribute (or "Other")
 * for secondary organisation within the category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchTvScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedCategory by remember {
        mutableStateOf(DefaultIptvPlaylists.availableCategories.first())
    }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Reload channels whenever the user picks a different category tab.
    LaunchedEffect(selectedCategory) {
        loading = true
        errorMessage = null
        channels = emptyList()
        scope.launch {
            val playlists = DefaultIptvPlaylists.forCategory(selectedCategory)
            if (playlists.isEmpty()) {
                loading = false
                return@launch
            }
            // Fetch all playlists for this category in parallel
            val results = withContext(Dispatchers.IO) {
                playlists.map { playlist ->
                    async { M3uParser.parse(context, playlist.url) }
                }.awaitAll()
            }
            val merged = results.flatten().distinctBy { it.url }
            channels = merged
            loading = false
            if (merged.isEmpty()) {
                errorMessage = "No channels found. Check your connection."
            }
        }
    }

    val filteredChannels = remember(channels, searchQuery) {
        if (searchQuery.isBlank()) channels
        else channels.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    // Within a category, group by M3U `group-title` (or "Other") for sub-organisation.
    val groupedChannels = remember(filteredChannels) {
        filteredChannels.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Other" }
            .toSortedMap()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Watch TV & More",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Free live TV, sports, news, movies — categorised",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // ── Category tabs (Bangladesh · Sports · News · Popular · Free) ─────────
        ScrollableTabRow(
            selectedTabIndex = DefaultIptvPlaylists.availableCategories.indexOf(selectedCategory).coerceAtLeast(0),
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            DefaultIptvPlaylists.availableCategories.forEachIndexed { index, category ->
                Tab(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    text = { Text(category.displayName) },
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search ${selectedCategory.displayName} channels...") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true,
        )

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Loading ${selectedCategory.displayName} channels...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupedChannels.forEach { (group, channelsInGroup) ->
                        item {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(channelsInGroup) { channel ->
                            ChannelItem(channel = channel) {
                                val intent = Intent(context, PlayerActivity::class.java).apply {
                                    action = Intent.ACTION_VIEW
                                    data = Uri.parse(channel.url)
                                    putExtra("title", channel.name)
                                }
                                context.startActivity(intent)
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(
    channel: IptvChannel,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_tv),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(coreUiR.drawable.ic_play),
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
