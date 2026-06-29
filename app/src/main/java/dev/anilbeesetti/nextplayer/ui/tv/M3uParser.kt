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
 * Top-level Live TV categories shown as front-page tabs.
 * Country-wise dedicated tabs + special content categories.
 * Order matters — shown left-to-right in the UI.
 */
enum class IptvCategory(val displayName: String) {
    BANGLADESH("🇧🇩 Bangladesh"),
    INDIA("🇮🇳 India"),
    PAKISTAN("🇵🇰 Pakistan"),
    USA("🇺🇸 USA"),
    UK("🇬🇧 UK"),
    TURKEY("🇹🇷 Turkey"),
    ARABIC("🌍 Arabic"),
    SPORTS("⚽ Sports"),
    NEWS("📰 News"),
    MOVIES("🎬 Movies"),
    KIDS("🧒 Kids"),
    MUSIC("🎵 Music"),
    INTERNATIONAL("🌐 International"),
    OTHER("Other"),
}

object IptvCategoryResolver {

    /**
     * Heuristic mapping from (channel name + group) → top-level category.
     *
     * Rules (in order):
     *  1. Bangladesh (.bd tvgId / known BD name keywords) → BANGLADESH
     *  2. India (.in tvgId / Star, Zee, Sony, NDTV keywords)  → INDIA
     *  3. Pakistan (.pk tvgId / ARY, GEO keywords)            → PAKISTAN
     *  4. USA (.us tvgId / CNN, Fox, NBC keywords)            → USA
     *  5. UK (.gb / .uk tvgId / BBC, ITV keywords)            → UK
     *  6. Turkey (.tr tvgId / TRT, ATV keywords)              → TURKEY
     *  7. Arabic language keywords                            → ARABIC
     *  8. Sports category keywords                            → SPORTS
     *  9. News category keywords                              → NEWS
     * 10. Movie keywords                                      → MOVIES
     * 11. Kids keywords                                       → KIDS
     * 12. Music keywords                                      → MUSIC
     * 13. Everything else                                     → INTERNATIONAL
     */
    fun resolve(channel: IptvChannel): IptvCategory {
        val haystack = listOfNotNull(channel.name, channel.group, channel.tvgName)
            .joinToString(" ")
            .lowercase()
        val tvgId = channel.tvgId?.lowercase() ?: ""

        // 1. Bangladesh
        val bdName = BD_CHANNEL_KEYWORDS.any { haystack.contains(it) }
        val bdUrl = channel.url.contains(".bd", ignoreCase = true) || tvgId.endsWith(".bd")
        if (bdName || bdUrl) return IptvCategory.BANGLADESH

        // 2. India
        val inUrl = tvgId.endsWith(".in")
        val inName = INDIA_KEYWORDS.any { haystack.contains(it) }
        if (inUrl || inName) return IptvCategory.INDIA

        // 3. Pakistan
        val pkUrl = tvgId.endsWith(".pk")
        val pkName = PAKISTAN_KEYWORDS.any { haystack.contains(it) }
        if (pkUrl || pkName) return IptvCategory.PAKISTAN

        // 4. USA
        val usUrl = tvgId.endsWith(".us")
        val usName = USA_KEYWORDS.any { haystack.contains(it) }
        if (usUrl || usName) return IptvCategory.USA

        // 5. UK
        val ukUrl = tvgId.endsWith(".gb") || tvgId.endsWith(".uk")
        val ukName = UK_KEYWORDS.any { haystack.contains(it) }
        if (ukUrl || ukName) return IptvCategory.UK

        // 6. Turkey
        val trUrl = tvgId.endsWith(".tr")
        val trName = TURKEY_KEYWORDS.any { haystack.contains(it) }
        if (trUrl || trName) return IptvCategory.TURKEY

        // 7. Arabic / Middle-East
        val arName = ARABIC_KEYWORDS.any { haystack.contains(it) }
        if (arName) return IptvCategory.ARABIC

        // 8. Sports
        if (SPORTS_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.SPORTS

        // 9. News
        if (NEWS_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.NEWS

        // 10. Movies
        if (MOVIE_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.MOVIES

        // 11. Kids
        if (KIDS_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.KIDS

        // 12. Music
        if (MUSIC_KEYWORDS.any { haystack.contains(it) }) return IptvCategory.MUSIC

        // 13. International catch-all
        return IptvCategory.INTERNATIONAL
    }

    private val BD_CHANNEL_KEYWORDS = listOf(
        "atn", "bangla", "bangladesh", "boishakhi", "channel i", "chattagram",
        "desh", "ekattor", "gtv", "jamuna", "maasranga", "nagorik",
        "rtv", "sangsad", "somoy", "tvsomoy", "zainga", "deepto",
        "asian tv", "ntv", "mytv", "bijoy", "bongo", "bongobd", "rb tv",
        "dhaka", "duronto", "gazi", "peoples tv", "real tv", "sk tv",
        "news24 bangla", "independent tv", "channel 9",
    )

    private val INDIA_KEYWORDS = listOf(
        "star plus", "star gold", "star jalsha", "star pravah", "star vijay",
        "sony", "zee tv", "zee cinema", "colors tv", "set max", "sab tv", "&tv",
        "ndtv", "aaj tak", "india today", "times now", "wion", "republic",
        "sun tv", "colors kannada", "zee kannada", "maa tv",
        "dd national", "doordarshan", "india", " in ",
    )

    private val PAKISTAN_KEYWORDS = listOf(
        "ary news", "geo news", "samaa", "dawn news", "hum tv",
        "express news", "92 news", "bol news", "duniya news",
        "ptv", "pakistan", "urdu 1", "urdu one", "a plus",
    )

    private val USA_KEYWORDS = listOf(
        "cnn", "fox news", "msnbc", "abc news", "nbc news", "cbs news",
        "hbo", "showtime", "cinemax", "amc", "tnt", "tbs", "fx", "syfy",
        "usa network", "bravo", "mtv usa", "espn",
    )

    private val UK_KEYWORDS = listOf(
        "bbc one", "bbc two", "bbc three", "bbc four", "itv", "channel 4",
        "channel 5", "sky one", "sky atlantic", "sky sports", "bt sport",
        "british", "uk tv", " uk ",
    )

    private val TURKEY_KEYWORDS = listOf(
        "trt", "show tv", "atv", "kanal d", "star tv turkish",
        "fox turkey", "cnn turk", "ntv turkey", "haber turk",
        "türkiye", "turkey", "turk", "türk",
    )

    private val ARABIC_KEYWORDS = listOf(
        "al jazeera", "mbc", "rotana", "osn", "beout", "al arabiya",
        "al mayadeen", "lbc", "future tv", "mtv arabic", "nile tv",
        "egypt", "saudi", "dubai", "qatar", "kuwait", "jordan tv",
        "arabic", "arab", "عربي",
    )

    private val SPORTS_KEYWORDS = listOf(
        "sport", "sports", "espn", "bein", "fox sport", "sky sport", "tnt sport",
        "nba", "nfl", "nhl", "mlb", "ufc", "wwe", "f1", "motogp",
        "epl", "la liga", "serie a", "bundesliga", "champions league",
        "cricket", "football", "soccer", "wrestling", "boxing", "golf",
        "tennis", "olympic", "supercross", "live sport",
    )

    private val NEWS_KEYWORDS = listOf(
        "news", "france 24", "dw", "rt news", "euro news",
        "press tv", "trt world", "cnbc", "bloomberg",
        "breaking", "news channel",
    )

    private val MOVIE_KEYWORDS = listOf(
        "movie", "movies", "cinema", "film", "films", "cine",
        "hollywood", "bollywood", "action", "thriller",
    )

    private val KIDS_KEYWORDS = listOf(
        "kids", "children", "cartoon", "nickelodeon", "disney", "pogo",
        "nick jr", "cartoon network", "baby", "junior",
    )

    private val MUSIC_KEYWORDS = listOf(
        "music", "mtv", "vh1", "radio", "hits", "beats", "melody",
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
        // ── Country-specific dedicated tabs ───────────────────────────────────
        Playlist("Bangladesh TV", "https://iptv-org.github.io/iptv/countries/bd.m3u", IptvCategory.BANGLADESH),
        Playlist("India TV", "https://iptv-org.github.io/iptv/countries/in.m3u", IptvCategory.INDIA),
        Playlist("Pakistan TV", "https://iptv-org.github.io/iptv/countries/pk.m3u", IptvCategory.PAKISTAN),
        Playlist("USA TV", "https://iptv-org.github.io/iptv/countries/us.m3u", IptvCategory.USA),
        Playlist("UK TV", "https://iptv-org.github.io/iptv/countries/gb.m3u", IptvCategory.UK),
        Playlist("Turkey TV", "https://iptv-org.github.io/iptv/countries/tr.m3u", IptvCategory.TURKEY),
        Playlist("Arabic TV", "https://iptv-org.github.io/iptv/languages/ara.m3u", IptvCategory.ARABIC),

        // ── Content-type categories ───────────────────────────────────────────
        Playlist("Sports", "https://iptv-org.github.io/iptv/categories/sports.m3u", IptvCategory.SPORTS),
        Playlist("News", "https://iptv-org.github.io/iptv/categories/news.m3u", IptvCategory.NEWS),
        Playlist("Movies", "https://iptv-org.github.io/iptv/categories/movies.m3u", IptvCategory.MOVIES),
        Playlist("Kids", "https://iptv-org.github.io/iptv/categories/kids.m3u", IptvCategory.KIDS),
        Playlist("Music", "https://iptv-org.github.io/iptv/categories/music.m3u", IptvCategory.MUSIC),

        // ── International catch-all ───────────────────────────────────────────
        Playlist("France TV", "https://iptv-org.github.io/iptv/countries/fr.m3u", IptvCategory.INTERNATIONAL),
        Playlist("Germany TV", "https://iptv-org.github.io/iptv/countries/de.m3u", IptvCategory.INTERNATIONAL),
        Playlist("Canada TV", "https://iptv-org.github.io/iptv/countries/ca.m3u", IptvCategory.INTERNATIONAL),
        Playlist("Australia TV", "https://iptv-org.github.io/iptv/countries/au.m3u", IptvCategory.INTERNATIONAL),
        Playlist("Russia TV", "https://iptv-org.github.io/iptv/countries/ru.m3u", IptvCategory.INTERNATIONAL),
    )

    /** All playlists that contribute to a given category. */
    fun forCategory(category: IptvCategory): List<Playlist> =
        playlists.filter { it.category == category }

    /** Categories that have at least one playlist (UI shows only these). */
    val availableCategories: List<IptvCategory>
        get() = IptvCategory.entries.filter { cat -> playlists.any { it.category == cat } }
}
