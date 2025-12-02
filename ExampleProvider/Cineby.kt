package com.aansrepo.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URI

class CinebyProvider : MainAPI() {
    override var mainUrl = "https://cineby.gd"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    // Custom headers to bypass anti-scraping
    override val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/movies" to "Movies",
        "${mainUrl}/tv-series" to "TV Series", 
        "${mainUrl}/anime" to "Anime",
        "${mainUrl}/trending" to "Trending",
        "${mainUrl}/latest" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "${request.data}&page=$page"
        } else {
            "${request.data}?page=$page"
        }
        
        val document = app.get(url, headers = headers).document
        val items = document.select("div.content-item, div.movie-card, div.video-item, article.post").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple selectors for title and link
        val titleElement = this.selectFirst("h2.title a, h3.title a, h2 a, h3 a, .movie-title a, .content-title a") 
            ?: return null
        
        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        
        // Ensure we have a full URL
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Try multiple selectors for poster
        val posterUrl = this.selectFirst("img.poster, img.thumb, img.content-image, img.wp-post-image, img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        // Extract quality from various indicators
        val qualityText = this.selectFirst(".quality, .hd-tag, .resolution, span.badge")?.text()
            ?: title.let { t ->
                when {
                    t.contains("4K", true) || t.contains("2160p", true) -> "4K"
                    t.contains("1080p", true) || t.contains("FHD", true) -> "1080p"
                    t.contains("720p", true) || t.contains("HD", true) -> "720p"
                    t.contains("480p", true) -> "480p"
                    t.contains("360p", true) -> "360p"
                    else -> null
                }
            }
        
        val quality = qualityText?.let { getQualityFromString(it) }
        
        // Determine content type
        val type = when {
            this.selectFirst(".series-tag, .tv-tag, .episode-count") != null -> TvType.TvSeries
            this.selectFirst(".anime-tag, .animation-tag") != null -> TvType.Anime
            else -> TvType.Movie
        }

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, fullUrl, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            TvType.Anime -> newAnimeSearchResponse(title, fullUrl, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            else -> newMovieSearchResponse(title, fullUrl, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Try multiple search endpoints
        val searchUrls = listOf(
            "${mainUrl}/search?q=${query.replace(" ", "+")}",
            "${mainUrl}/?s=${query.replace(" ", "+")}",
            "${mainUrl}/search/${query.replace(" ", "-")}"
        )
        
        for (url in searchUrls) {
            try {
                val document = app.get(url, headers = headers).document
                val results = document.select("div.content-item, div.movie-card, div.video-item, article.post").mapNotNull { 
                    it.toSearchResult() 
                }
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                // Try next URL
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        
        // Extract title with multiple fallbacks
        val title = document.selectFirst("h1.movie-title, h1.entry-title, h1.content-title, h1")?.text()?.trim()
            ?: return null
        
        // Extract poster
        val poster = document.selectFirst("img.movie-poster, img.content-poster, img.wp-post-image, meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.movie-poster, img.content-poster, img.wp-post-image")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        // Extract description
        val description = document.selectFirst("div.movie-description, div.content-description, .entry-content p, .plot-summary")?.text()?.trim()
            ?: document.select("meta[name='description']").attr("content")
        
        // Extract year
        val year = document.selectFirst(".year, .release-year, span.date")?.text()?.toIntOrNull()
        
        // Extract genres
        val genres = document.select(".genre a, .categories a").map { it.text().trim() }
        
        // Check for series/episodes
        val episodes = mutableListOf<Episode>()
        
        // Try different episode selectors
        val episodeElements = document.select(".episode-list a, .season-episodes a, .episode-item a")
        if (episodeElements.isNotEmpty()) {
            episodes.addAll(episodeElements.mapNotNull { ep ->
                val epTitle = ep.text().trim()
                val epUrl = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                val epNum = epTitle.filter { it.isDigit() }.toIntOrNull()
                
                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                    }
                } else null
            })
        }
        
        // Try to find season structure
        val seasons = document.select(".season-item, .season-tab").mapNotNull { season ->
            val seasonTitle = season.text().trim()
            val seasonEpisodes = season.select("a").mapNotNull { ep ->
                val epTitle = ep.text().trim()
                val epUrl = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epTitle.filter { it.isDigit() }.toIntOrNull()
                    }
                } else null
            }
            if (seasonEpisodes.isNotEmpty()) seasonTitle to seasonEpisodes else null
        }.toMap()
        
        return if (episodes.isNotEmpty() || seasons.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.seasons = seasons
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        // Method 1: Look for direct download/stream
