package com.aansrepo.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HDHub4UProvider : MainAPI() {
    override var mainUrl = "https://hdhub4u.rehab"
    override var name = "HDHub4U"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/category/bollywood-movies" to "Bollywood Movies",
        "${mainUrl}/category/hollywood-movies" to "Hollywood Movies", 
        "${mainUrl}/category/web-series" to "Web Series",
        "${mainUrl}/category/dual-audio" to "Dual Audio"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "/page/$page").document
        val items = document.select("article.post, div.post-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h3.title a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.wp-post-image, img.attachment-medium")?.attr("src")
        
        // Extract quality from title or span
        val qualityText = this.selectFirst("span.quality, .post-quality")?.text() 
            ?: title.let { t ->
                when {
                    t.contains("1080p", true) -> "1080p"
                    t.contains("720p", true) -> "720p" 
                    t.contains("480p", true) -> "480p"
                    t.contains("HD", true) -> "HD"
                    else -> null
                }
            }
        
        val quality = qualityText?.let { getQualityFromString(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query.replace(" ", "+")}").document
        return document.select("article.post, div.post-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img.wp-post-image, img.attachment-large")?.attr("src")
        val description = document.selectFirst("div.entry-content p, .post-description")?.text()?.trim()
        
        // Check for series content
        val episodes = document.select("div.episode-list a, .season-episodes a").mapNotNull {
            val epTitle = it.text().trim()
            val epUrl = it.attr("href")
            if (epUrl.isNotEmpty()) {
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epTitle.filter { it.isDigit() }.toIntOrNull()
                }
            } else null
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
        
        // Look for download links in various containers
        document.select("div.download-links a, .watch-button a, a[href*='download'], a[href*='stream']").forEach { link ->
            val href = link.attr("href")
            val qualityText = link.text().trim()
            
            if (href.isNotEmpty() && !href.contains("#")) {
                val quality = when {
                    qualityText.contains("1080", true) -> Qualities.P1080.value
                    qualityText.contains("720", true) -> Qualities.P720.value
                    qualityText.contains("480", true) -> Qualities.P480.value
                    qualityText.contains("360", true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                callback.invoke(
                    ExtractorLink(
                        name = name,
                        source = name,
                        url = href,
                        quality = quality,
                        isM3u8 = href.contains(".m3u8"),
                        headers = mapOf("Referer" to mainUrl)
                    )
                )
            }
        }
        
        // Try to find iframe players
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("ads")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
