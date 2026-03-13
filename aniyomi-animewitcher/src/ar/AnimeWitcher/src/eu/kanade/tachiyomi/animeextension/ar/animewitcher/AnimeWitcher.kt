package eu.kanade.tachiyomi.animeextension.ar.animewitcher

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeWitcher : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeWitcher"
    override val baseUrl = "https://1we323-witcher.hf.space"
    override val lang = "ar"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ══════════════════════════════════════════
    // الصفحة الرئيسية
    // ══════════════════════════════════════════
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/main?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<MainResponse>(response.body.string())
        val animes = data.hits.map { hit ->
            SAnime.create().apply {
                url           = hit.id
                title         = hit.name
                thumbnail_url = hit.poster
                status        = SAnime.UNKNOWN
            }
        }
        return AnimesPage(animes, data.page < data.nbPages)
    }

    // ══════════════════════════════════════════
    // البحث
    // ══════════════════════════════════════════
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        if (query.isNotBlank())
            GET("$baseUrl/api/search?q=${query.trim()}", headers)
        else
            GET("$baseUrl/api/main?page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        return if (url.contains("/api/search")) {
            val data = json.decodeFromString<SearchResponse>(response.body.string())
            val animes = data.hits.map { hit ->
                SAnime.create().apply {
                    url           = hit.id
                    title         = hit.name
                    thumbnail_url = hit.poster
                    description   = hit.story
                    status        = SAnime.UNKNOWN
                }
            }
            AnimesPage(animes, false)
        } else {
            popularAnimeParse(response)
        }
    }

    // ══════════════════════════════════════════
    // تفاصيل الأنمي
    // ══════════════════════════════════════════
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/api/search?q=${anime.title}", headers)

    override fun animeDetailsParse(response: Response): SAnime =
        SAnime.create().apply { status = SAnime.UNKNOWN }

    // ══════════════════════════════════════════
    // الحلقات
    // ══════════════════════════════════════════
    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/api/episodes?id=${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.request.url.queryParameter("id") ?: ""
        val data    = json.decodeFromString<EpisodesResponse>(response.body.string())
        return data.episodes.map { ep ->
            SEpisode.create().apply {
                url            = "$animeId||${ep.id}"
                name           = ep.name
                episode_number = ep.num.toFloat()
                date_upload    = System.currentTimeMillis()
            }
        }.reversed()
    }

    // ══════════════════════════════════════════
    // رابط التشغيل
    // ══════════════════════════════════════════
    override fun videoListRequest(episode: SEpisode): Request {
        val (animeId, epId) = episode.url.split("||")
        return GET("$baseUrl/api/servers_resolved?anime=$animeId&ep=$epId", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data   = json.decodeFromString<ServersResponse>(response.body.string())
        val videos = mutableListOf<Video>()

        // أولاً: Pixeldrain فقط
        for (sv in data.servers) {
            if (!sv.playable) continue
            if (!sv.url.contains("pixeldrain") && !sv.proxy_url.contains("pixeldrain")) continue
            val proxyUrl = if (sv.proxy_url.startsWith("/")) "$baseUrl${sv.proxy_url}" else sv.proxy_url
            val label = buildString {
                append("PD")
                if (sv.quality.isNotBlank()) append(" ${sv.quality}p")
                if (sv.lang.isNotBlank()) append(" [${sv.lang}]")
            }
            videos.add(Video(proxyUrl, label, proxyUrl))
        }

        // إذا ما في PD، استخدم أي سيرفر شغال
        if (videos.isEmpty()) {
            for (sv in data.servers) {
                if (!sv.playable) continue
                val proxyUrl = if (sv.proxy_url.startsWith("/")) "$baseUrl${sv.proxy_url}" else sv.proxy_url
                val label = "${sv.name} ${sv.quality}p".trim()
                videos.add(Video(proxyUrl, label, proxyUrl))
            }
        }

        return videos
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ══════════════════════════════════════════
    // الإعدادات
    // ══════════════════════════════════════════
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key         = PREF_QUALITY
            title       = "الجودة المفضلة"
            entries     = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary     = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
    }

    // ══════════════════════════════════════════
    // Data Classes
    // ══════════════════════════════════════════
    @Serializable
    data class MainResponse(
        val hits: List<AnimeHit> = emptyList(),
        val nbPages: Int = 1,
        val page: Int = 1,
    )

    @Serializable
    data class AnimeHit(
        val id: String = "",
        val name: String = "",
        val poster: String = "",
        val type: String = "",
    )

    @Serializable
    data class SearchResponse(
        val hits: List<SearchHit> = emptyList(),
        val total: Int = 0,
    )

    @Serializable
    data class SearchHit(
        val id: String = "",
        val name: String = "",
        val poster: String = "",
        val story: String = "",
        val type: String = "",
    )

    @Serializable
    data class EpisodesResponse(
        val episodes: List<Episode> = emptyList(),
        val total: Int = 0,
    )

    @Serializable
    data class Episode(
        val id: String = "",
        val name: String = "",
        val num: Double = 0.0,
    )

    @Serializable
    data class ServersResponse(
        val servers: List<Server> = emptyList(),
        val total: Int = 0,
    )

    @Serializable
    data class Server(
        val name: String = "",
        val url: String = "",
        val proxy_url: String = "",
        val quality: String = "",
        val lang: String = "",
        val playable: Boolean = false,
    )
}
