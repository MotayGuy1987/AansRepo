package com.aansrepo.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HindiLinks4UProvider : MainAPI() {
    override var mainUrl = "https://hindilinks4u.garden"
    override var name = "HindiLinks4U"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/category/bollywood-movies" to "Bollywood Movies",
        "${mainUrl}/category/hollywood-hindi-dubbed" to "Hollywood Hindi Dubbed",
        "${mainUrl}/category/web-series" to "Web Series",
        "${mainUrl}/category/south-indian" to "South Indian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (request.data.contains("?")) "&page=$page" else "?page=$page").document
        val items = document.select("article.post, .movie-item, .post-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h3.title a, .movie-title a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img.wp-post-image, .movie-poster img")?.attr("src")
        
        val quality = this.selectFirst(".quality, .hd-tag")?.text()?.let { getQualityFromString(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query.replace(" ", "+")}").document
        return document.select("article.post, .movie-item, .post-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title, .movie-title h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img.wp-post-image, .movie-poster img")?.attr("src")
        val description = document.selectFirst(".entry-content p, .movie-description")?.text()?.trim()
        
        val episodes = document.select(".episode-list a, .season-episodes a").mapNotNull {
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
        callback:
