package dev.anilbeesetti.nextplayer.ui.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * M3U/M3U8 playlist parser for live IPTV streams.
 */
data class IptvChannel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
) {
    /**
     * Phase 8 — derive a stable top-level category for the channel based on
     * its name + group + country code. Used to bucket channels into the five
     * front-page tabs (Bangladesh, Sports, News, Popular, Free Channels).
     */
    val category: IptvCategory get() = IptvCategoryResolver.resolve(this)
}

/**
 * Phase 8 — Top-level Live TV categories shown as front-page tabs.
 *
 * Order matters — it's the order shown in the UI. "Bangladesh" first because
 * the app's primary audience is Bangla-speaking users.
 */
enum class IptvCategory(val displayName: String) {
    BANGLADESH("Bangladesh"),
    SPORTS("Sports"),
    NEWS("News"),
    POPULAR("Popular"),
    FREE("Free Channels"),
    OTHER("Other"),
}

object IptvCategoryResolver {

    /**
     * Heuristic mapping from (channel name + group) → top-level category.
     *
     * Rules (in order):
     *  1. Bangladesh country code (.bd) or known BD channel names → BANGLADESH
     *  2. Group / name contains sports keywords → SPORTS
     *  3. Group / name contains news keywords → NEWS
     *  4. Country code in BD_NEIGHBOURS list (IN, PK) and not already categorised
     *     → POPULAR (regional popular)
     *  5. Everything else → FREE (since all our default playlists are free iptv-org)
     *
     * The "POPULAR" bucket is reserved for channels that appear in multiple
     * categories or are widely-watched regional channels.
     */
    fun resolve(channel: IptvChannel): IptvCategory {
        val haystack = listOfNotNull(channel.name, channel.group, channel.tvgName)
            .joinToString(" ")
            .lowercase()

        // 1. Bangladesh — by channel name keyword or .bd country code in URL
        val bdName = BD_CHANNEL_KEYWORDS.any { haystack.contains(it) }
        val bdUrl = channel.url.contains(".bd", ignoreCase = true) ||
            channel.tvgId?.endsWith(".bd", ignoreCase = true) == true
        if (bdName || bdUrl) return IptvCategory.BANGLADESH

        // 2. Sports
        if (SPORTS_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.SPORTS

        // 3. News
        if (NEWS_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.NEWS

        // 4. Popular — regional heavy-hitters (BD already filtered out above)
        if (POPULAR_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.POPULAR

        // 5. Everything else is free
        return IptvCategory.FREE
    }

    /** Known Bangladeshi channel name fragments (lowercase). */
    private val BD_CHANNEL_KEYWORDS = listOf(
        "atn", "bangla", "bd", "bangladesh", "boishakhi", "channel i", "chattagram",
        "desh", "ekattor", "gtv", "jamuna", "maasranga", "nagorik", "news24",
        "rtv", "sangsad", "somoy", "swift", "tara", "tvsomoy", "zainga",
        "deepto", "asian tv", "ntv", "rtv", "mytv", "boomerang", "bijoy",
        "bongo", "bongobd", "rb tv", "dhaka", "duronto", "gazi", "islam",
        "peoples tv", "real tv", "sk tv", " times",
    )

    private val SPORTS_KEYWORDS = listOf(
        "sport", "sports", "espn", "fox sport", "sky sport", "tnt sport",
        "bein", "nba", "nfl", "nhl", "mlb", "ufc", "wwe", "f1", "motogp",
        "epl", "la liga", "serie a", "bundesliga", "champions league",
        "cricket", "football", "soccer", "wrestling", "boxing", "golf",
        "tennis", "olympic", "red bull", "supercross", "live sport",
    )

    private val NEWS_KEYWORDS = listOf(
        "news", "cnn", "bbc", "al jazeera", "ndtv", "abp", "republic",
        "france 24", "dw", "abc news", "nbc news", "cbs news", "sky news",
        "fox news", "msnbc", "rt news", "euro news", "aaj tak", "india today",
        "times now", "wion", "press tv", "trt world", "cnbc", "bloomberg",
        "ary news", "geo news", "samaa", "dawn news", "ndtv",
    )

    private val POPULAR_KEYWORDS = listOf(
        // Regional major networks (non-news, non-sports)
        "star plus", "star gold", "sony", "zee tv", "zee cinema", "colors tv",
        "set max", "sab tv", "&tv", "star jalsha", "star pravah", "star vijay",
        "hotstar", "hbo", "showtime", "cinemax", "axn", "fox life",
        "discovery", "national geographic", "animal planet", "history",
        "cartoon", "nickelodeon", "disney", "pogo", "tnt", "tbs", "amc",
        "fx", "syfy", "usa network", "bravo", "mtv", "vh1",
        // Generic "popular" markers
        "popular", "trending", "top", "hit", "best",
    )
}

object M3uParser {

    suspend fun parse(context: Context, source: String): List<IptvChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val content = when {
                source.startsWith("http://") || source.startsWith("https://") -> fetchRemote(source)
                source.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
                        BufferedReader(InputStreamReader(input)).readText()
                    } ?: ""
                }
                source.startsWith("file://") -> {
                    java.io.File(Uri.parse(source).path ?: "").readText()
                }
                else -> {
                    val f = java.io.File(source)
                    if (f.exists()) f.readText() else ""
                }
            }
            parseContent(content)
        }.getOrDefault(emptyList())
    }

    fun parseContent(content: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentTvgId: String? = null
        var currentTvgName: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                val commaIndex = trimmed.indexOf(',')
                val attributesPart = if (commaIndex > 0) trimmed.substring(0, commaIndex) else trimmed
                val namePart = if (commaIndex > 0) trimmed.substring(commaIndex + 1).trim() else "Unknown"

                currentName = namePart
                currentLogo = extractAttribute(attributesPart, "tvg-logo")
                currentGroup = extractAttribute(attributesPart, "group-title")
                currentTvgId = extractAttribute(attributesPart, "tvg-id")
                currentTvgName = extractAttribute(attributesPart, "tvg-name")
            } else if (trimmed.startsWith("#")) {
                continue
            } else {
                if (currentName != null && (trimmed.startsWith("http") || trimmed.startsWith("rtmp") ||
                        trimmed.startsWith("rtsp") || trimmed.startsWith("udp"))
                ) {
                    channels.add(
                        IptvChannel(
                            name = currentName,
                            url = trimmed,
                            logoUrl = currentLogo,
                            group = currentGroup,
                            tvgId = currentTvgId,
                            tvgName = currentTvgName,
                        ),
                    )
                }
                currentName = null
                currentLogo = null
                currentGroup = null
                currentTvgId = null
                currentTvgName = null
            }
        }
        return channels
    }

    private fun extractAttribute(text: String, attrName: String): String? {
        val key = "$attrName=\""
        val start = text.indexOf(key, ignoreCase = true)
        if (start < 0) return null
        val valueStart = start + key.length
        val end = text.indexOf('"', valueStart)
        if (end < 0) return null
        return text.substring(valueStart, end).takeIf { it.isNotBlank() }
    }

    private fun fetchRemote(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "SHSPlayer/1.4")
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Phase 8 — Default IPTV playlists organised by category.
 *
 * Each category maps to one or more iptv-org playlist URLs. The WatchTv UI
 * shows categories as tabs; selecting one loads all playlists for that category
 * in parallel and merges the results.
 */
object DefaultIptvPlaylists {
    data class Playlist(val name: String, val url: String, val category: IptvCategory)

    val playlists = listOf(
        // Bangladesh — primary audience
        Playlist("Bangladesh TV", "https://iptv-org.github.io/iptv/countries/bd.m3u", IptvCategory.BANGLADESH),

        // India (large Bangla-adjacent audience + lots of regional channels)
        Playlist("India TV", "https://iptv-org.github.io/iptv/countries/in.m3u", IptvCategory.POPULAR),

        // Sports
        Playlist("Sports", "https://iptv-org.github.io/iptv/categories/sports.m3u", IptvCategory.SPORTS),

        // News
        Playlist("News", "https://iptv-org.github.io/iptv/categories/news.m3u", IptvCategory.NEWS),

        // Movies + Kids + Music → "Popular" bucket
        Playlist("Movies", "https://iptv-org.github.io/iptv/categories/movies.m3u", IptvCategory.POPULAR),
        Playlist("Kids", "https://iptv-org.github.io/iptv/categories/kids.m3u", IptvCategory.POPULAR),
        Playlist("Music", "https://iptv-org.github.io/iptv/categories/music.m3u", IptvCategory.POPULAR),

        // Other countries — broad free channels pool
        Playlist("USA", "https://iptv-org.github.io/iptv/countries/us.m3u", IptvCategory.FREE),
        Playlist("UK", "https://iptv-org.github.io/iptv/countries/uk.m3u", IptvCategory.FREE),
        Playlist("Pakistan", "https://iptv-org.github.io/iptv/countries/pk.m3u", IptvCategory.FREE),
    )

    /** All playlists that contribute to a given category. */
    fun forCategory(category: IptvCategory): List<Playlist> =
        playlists.filter { it.category == category }

    /** Categories that have at least one playlist (UI shows only these). */
    val availableCategories: List<IptvCategory>
        get() = IptvCategory.entries.filter { cat -> playlists.any { it.category == cat } }
}
