package com.aansrepo.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MovieHaxProvider : MainAPI() {
    override var mainUrl = "https://moviehax.biz"
    override var name = "MovieHax"
    override val hasMainPage = true
    override var lang = "en"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/movies" to "Movies",
        "${mainUrl}/series" to "TV Series",
        "${mainUrl}/trending" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (request.data.contains("?")) "&page=$page" else "?page=$page").document
        val items = document.select("div.video-block, div.item, div.movie-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.title a, h3.title a, h2 a, h3 a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.poster, img.thumb, img")?.attr("src")
        val quality = this.selectFirst("span.quality, span.hd, span.tag")?.text()?.let { getQualityFromString(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search?q=${query}").document
        return document.select("div.video-block, div.item, div.movie-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.movie-title, h1.entry-title, h1.title")?.text() ?: return null
        val poster = document.selectFirst("img.movie-poster, img.poster, img")?.attr("src")
        val description = document.selectFirst("div.description, p.summary, div.plot")?.text()
        
        // Check if it's a series
        val episodes = document.select("div.episode-list a, div.episodes a").mapNotNull {
            val epTitle = it.text()
            val epUrl = it.attr("href")
            newEpisode(epUrl) {
                name = epTitle
                episode = epTitle.filter { it.isDigit() }.toIntOrNull()
            }
        }
        
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Try multiple selectors for iframe/embed
        val iframe = document.selectFirst("iframe.player-embed, iframe[src*='embed'], video source")?.attr("src") 
            ?: document.selectFirst("a.watch-btn, a.play-btn")?.attr("href")
        
        if (!iframe.isNullOrEmpty()) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            return true
        }
        
        // Try to find direct download links
        document.select("a.download-btn, a[href*='.m3u8'], a[href*='stream']").forEach { link ->
            callback.invoke(
                ExtractorLink(
                    name = name,
                    source = name,
                    url = link.attr("href"),
                    quality = Qualities.Unknown.value,
                    isM3u8 = link.attr("href").contains(".m3u8"),
                    headers = mapOf("Referer" to mainUrl)
                )
            )
        }
        
        return true
    }
}
