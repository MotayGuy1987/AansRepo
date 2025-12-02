package com.aansrepo.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmyzillaProvider : MainAPI() {
    override var mainUrl = "https://filmyzilla27.com"
    override var name = "Filmyzilla27"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Enhanced headers to mimic a real browser and bypass basic anti-scraping
    override val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/category/bollywood-movies" to "Bollywood Movies",
        "${mainUrl}/category/hollywood-movies" to "Hollywood Movies",
        "${mainUrl}/category/south-indian-hindi-dubbed" to "South Hindi Dubbed",
        "${mainUrl}/category/web-series" to "Web Series",
        "${mainUrl}/category/hindi-dubbed-movies" to "Hindi Dubbed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("/page/")) {
            request.data.replace(Regex("/page/\\d+"), "/page/$page")
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url, headers = headers).document
        val items = document.select("article.post, div.post-item, div.movie-box, .movi-box").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h3.entry-title a, .movi-title a, .title a")
            ?: return null

        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        val cleanTitle = title.replace(Regex("\$$\\d{4}\$$"), "").replace(Regex("\$$.*?]"), "").trim()

        var posterUrl = this.selectFirst("img.wp-post-image, .movi-img img, .poster img, img")?.attr("src")
            ?: this.selectFirst("div[data-bg]")?.attr("data-bg")
        posterUrl = posterUrl?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val qualityText = this.selectFirst(".quality, .hd-tag, .movi-quality, span.label")?.text()
            ?: title.let { t ->
                when {
                    t.contains("4K", true) || t.contains("2160p", true) -> "4K"
                    t.contains("1080p", true) || t.contains("FHD", true) || t.contains("BluRay", true) -> "1080p"
                    t.contains("720p", true) || t.contains("HD", true) || t.contains("HDRip", true) -> "720p"
                    t.contains("480p", true) || t.contains("DVDRip", true) -> "480p"
                    else -> null
                }
            }
        val quality = qualityText?.let { getQualityFromString(it) }

        val type = if (this.selectFirst(".series-tag, .episodes-list, .season-list") != null) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, type) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query.replace(" ", "+")}"
        return try {
            val document = app.get(searchUrl, headers = headers).document
            document.select("article.post, div.post-item, div.movie-box").mapNotNull {
                it.toSearchResult()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.entry-title, h1.page-title, h1.movititle, h1")?.text()?.trim()
            ?.replace(Regex("\$$\\d{4}\$$"), "")?.replace(Regex("\$$.*?]"), "")?.trim()
            ?: return null

        var poster = document.selectFirst("img.wp-post-image, .movi-img img, .poster img")?.attr("src")
            ?: document.select("meta[property='og:image']").attr("content")
        poster = poster?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        val description = document.selectFirst("div.entry-content p, .movi-desc, .description, .plot-summary")?.text()?.trim()
            ?: document.select("meta[name='description']").attr("content")

        val year = document.selectFirst(".year, .release-year, span.date, .meta-date")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }
            ?: title.let { t -> Regex("(\\d{4})").find(t)?.value?.toIntOrNull() }

        val genres = document.select(".genre a, .categories a, .tags a").map { it.text().trim() }

        val episodes = document.select(".episodes-list a, .season-episodes a").mapNotNull {
            val epTitle = it.text().trim()
            val epUrl = it.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val epNum = epTitle.filter { it.isDigit() }.toIntOrNull()
            if (epUrl.isNotEmpty()) {
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                }
            } else null
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
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
        val processedLinks = mutableSetOf<String>() // To avoid duplicates
        val baseUrl = URI(data).let { "${it.scheme}://${it.host}" }

        // Helper function to process a found link
        fun processLink(href: String, sourceName: String, quality: Qualities = Qualities.Unknown) {
            val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            if (fullUrl !in processedLinks && fullUrl.isNotEmpty()) {
                processedLinks.add(fullUrl)
                // Check if it's a direct video file or a page that needs further extraction
                if (fullUrl.contains(".m3u8") || fullUrl.contains(".mp4") || fullUrl.contains("streamtape") || fullUrl.contains("doodstream")) {
                    callback.invoke(
                        ExtractorLink(
                            name = this.name,
                            source = sourceName,
                            url = fullUrl,
                            quality = quality.value,
                            isM3u8 = fullUrl.contains(".m3u8"),
                            headers = this.headers
                        )
                    )
                                } else {
                    // It's likely a page with another link, so we need to fetch it.
                    // We use a helper function to recursively find the final streamable link.
                    findFinalLink(fullUrl, sourceName, quality, callback, headers)
                }
            }
        }

        // Helper function to recursively follow redirects and find the final video link
        suspend fun findFinalLink(
            url: String,
            sourceName: String,
            quality: Qualities,
            callback: (ExtractorLink) -> Unit,
            baseHeaders: Map<String, String>
        ) {
            try {
                // We need to follow potential redirects, so we allow them
                val linkDoc = app.get(url, headers = baseHeaders, allowRedirects = true).document

                // Method A: Look for iframes on the intermediate page
                val iframe = linkDoc.selectFirst("iframe")
                if (iframe != null) {
                    val src = iframe.attr("src")
                    if (src.isNotEmpty()) {
                        // This iframe might contain the video player, so we use the extractor
                        loadExtractor(src, url, subtitleCallback, callback)
                        return
                    }
                }

                // Method B: Look for a final download button on the intermediate page
                val finalButton = linkDoc.selectFirst("a.download-btn, a.btn-primary, a[href*='.m3u8'], a[href*='streamtape.com'], a[href*='doodstream']")
                if (finalButton != null) {
                    val finalHref = finalButton.attr("href")
                    val finalUrl = if (finalHref.startsWith("http")) finalHref else "$baseUrl$finalHref"
                    
                    // If it's a known host, use the extractor. Otherwise, pass it as a direct link.
                    if (finalUrl.contains("streamtape") || finalUrl.contains("doodstream") || finalUrl.contains("gdflix")) {
                        loadExtractor(finalUrl, url, subtitleCallback, callback)
                    } else {
                        callback.invoke(
                            ExtractorLink(
                                name = this.name,
                                source = sourceName,
                                url = finalUrl,
                                quality = quality.value,
                                isM3u8 = finalUrl.contains(".m3u8"),
                                headers = baseHeaders
                            )
                        )
                    }
                    return
                }

                // Method C: Check for JavaScript-based redirects (common on these sites)
                // Look for scripts that contain a window.location or similar redirect
                linkDoc.select("script").forEach { script ->
                    val scriptText = script.data()
                    // Regex to find URLs in JS redirects like window.location = "url"
                    val jsUrl = Regex("window\\.location\\s*=\\s*[\"']([^^\"']+)[\"']").find(scriptText)?.groupValues?.get(1)
                        ?: Regex("href\\s*=\\s*[\"']([^^\"']+)[\"']").find(scriptText)?.groupValues?.get(1)
                    
                    if (jsUrl != null && jsUrl.isNotEmpty()) {
                        val redirectUrl = if (jsUrl.startsWith("http")) jsUrl else "$baseUrl$jsUrl"
                        // Recursively call the function on the new URL found in the script
                        findFinalLink(redirectUrl, sourceName, quality, callback, baseHeaders)
                        return@forEach
                    }
                }

            } catch (e: Exception) {
                // If fetching the intermediate page fails, we can't do much.
                // It's better to fail silently than to crash the whole process.
            }
        }


        // --- Main Logic for finding links on the initial page ---

        // Method 1: Look for structured download tables (most reliable)
        document.select("table.download-table tr, .download-box tr, .links-table tr, .server-row").forEach { row ->
            // Skip header rows
            if (row.select("th").isNotEmpty()) return@forEach

            val qualityText = row.selectFirst("td:first-child, .quality, .label, .server-name")?.text()?.trim()
            val link = row.selectFirst("a[href*='download'], a[href*='watch'], a[href*='play']")?.attr("href")

            if (!link.isNullOrEmpty()) {
                val quality = when {
                    qualityText?.contains("1080", true) == true -> Qualities.P1080
                    qualityText?.contains("720", true) == true -> Qualities.P720
                    qualityText?.contains("480", true) == true -> Qualities.P480
                    qualityText?.contains("360", true) == true -> Qualities.P360
                    else -> Qualities.Unknown
                }
                processLink(link, name, quality)
            }
        }

        // Method 2: Look for button-based links if no table was found
        if (processedLinks.isEmpty()) {
            document.select("a.download-btn, a.watch-btn, .btn a, .button a, .gobtn a").forEach { btn ->
                val text = btn.text().trim()
                val href = btn.attr("href")
                if (href.isNotEmpty() && !href.contains("#") && !href.contains("javascript")) {
                    val quality = when {
                        text.contains("1080", true) -> Qualities.P1080
                        text.contains("720", true) -> Qualities.P720
                        text.contains("480", true) -> Qualities.P480
                        text.contains("360", true) -> Qualities.P360
                        else -> Qualities.Unknown
                    }
                    processLink(href, name, quality)
                }
            }
        }

        // Method 3: If still no links, look for any direct iframe or video source
        if (processedLinks.isEmpty()) {
            val iframe = document.selectFirst("iframe.player-embed, iframe[src*='embed'], video source")
            if (iframe != null) {
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
                   
