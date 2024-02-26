package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class KKPhimProvider : MainAPI() {
    override var name = API_NAME
    override var mainUrl = "https://phimapi.com"
    override var name = "KKPhim"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    companion object {
        const val DOMAIN = "https://phimapi.com"
        const val DOMAIN_IMAGE = "https://img.phimapi.com/upload/vod"
        const val API_NAME = "KK Phim"
        const val PREFIX_GENRE = "/the-loai"
        const val PREFIX_COUNTRY = "/quoc-gia"
        const val DOMAIN_DETAIL_MOVIE = "$DOMAIN//phim"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi-cap-nhat?page=" to "Phim Mới cập nhật",
        "$mainUrl/v1/api/danh-sach/phim-le?page=" to "Phim Lẻ",
        "$mainUrl/v1/api/danh-sach/phim-bo?page=" to "Phim Bộ",
        "$mainUrl/v1/api/danh-sach/hoat-hinh?page=" to "Phim Hoạt Hình",
        "$mainUrl/v1/api/danh-sach/tv-shows?page=" to "Tv Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = app.get("${request.data}?page=${page}")
            .parsedSafe<Home>()?.data?.items?.mapNotNull { itemData ->
                val phim18 = itemData.category.find { cate -> cate.slug == "phim-18" }
                if (settingsForProvider.enableAdult) {
                    itemData.toSearchResponse()
                } else {
                    if (phim18 != null) {   // Contain 18+ in movie
                        null
                    } else {
                        itemData.toSearchResponse()
                    }
                }
            }
        return newHomePageResponse(request.name,list ?: emptyList(),true)
    }

    private fun decode(input: String): String? = URLDecoder.decode(input, "utf-8")

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("p,h3")?.text()?.trim().toString()
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = decode(this.selectFirst("img")!!.attr("src").substringAfter("url="))
        val temp = this.select("span.label").text()
        return if (temp.contains(Regex("\\d"))) {
            val episode = Regex("(\\((\\d+))|(\\s(\\d+))").find(temp)?.groupValues?.map { num ->
                num.replace(Regex("\\(|\\s"), "")
            }?.distinct()?.firstOrNull()?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else if (temp.contains(Regex("Trailer"))) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            val quality =
                temp.replace(Regex("(-.*)|(\\|.*)|(?i)(VietSub.*)|(?i)(Thuyết.*)"), "").trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/tim-kiem/$query"
        val document = app.get(link).document

        return document.select("ul.list-film li").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim().toString()
        val link = document.select("ul.list-button li:last-child a").attr("href")
        val poster = document.selectFirst("div.image img[itemprop=image]")?.attr("src")
        val tags = document.select("ul.entry-meta.block-film li:nth-child(4) a")
            .map { it.text().substringAfter("Phim") }
        val year = document.select("ul.entry-meta.block-film li:nth-child(2) a").text().trim()
            .toIntOrNull()
        val tvType = if (document.select("div.latest-episode").isNotEmpty()
        ) TvType.TvSeries else TvType.Movie
        val description =
            document.select("div#film-content").text().substringAfter("Full HD Vietsub Thuyết Minh")
                .substringBefore("@phimmoi").trim()
        val trailer = document.select("body script")
            .find { it.data().contains("youtube.com") }?.data()?.substringAfterLast("file: \"")
            ?.substringBefore("\",")
        val rating =
            document.select("ul.entry-meta.block-film li:nth-child(7) span").text().toRatingInt()
        val actors = document.select("ul.entry-meta.block-film li:last-child a").map { it.text() }
        val recommendations = document.select("ul#list-film-realted li.item").map {
            it.toSearchResult().apply {
                this.posterUrl =
                    decode(it.selectFirst("img")!!.attr("data-src").substringAfter("url="))
            }
        }

        return if (tvType == TvType.TvSeries) {
            val docEpisodes = app.get(link).document
            val episodes = docEpisodes.select("ul#list_episodes > li").map {
                val href = it.select("a").attr("href")
                val episode =
                    it.select("a").text().replace(Regex("[^0-9]"), "").trim().toIntOrNull()
                val name = "Episode $episode"
                Episode(
                    data = href,
                    name = name,
                    episode = episode,
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
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

        val key = document.select("div#content script")
            .find { it.data().contains("filmInfo.episodeID =") }?.data()?.let { script ->
                val id = script.substringAfter("parseInt('").substringBefore("'")
                app.post(
                    url = "$directUrl/chillsplayer.php",
                    data = mapOf("qcao" to id),
                    referer = data,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                ).text.substringAfterLast("iniPlayers(\"")
                    .substringBefore("\"")
            }

        listOf(
            Pair("https://sotrim.topphimmoi.org/raw/$key/index.m3u8", "PMFAST"),
            Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "PMHLS"),
            Pair("https://so-trym.phimchill.net/dash/$key/index.m3u8", "PMPRO"),
            Pair("https://dash.megacdn.xyz/dast/$key/index.m3u8", "PMBK")
        ).map { (link, source) ->
            callback.invoke(
                ExtractorLink(
                    source,
                    source,
                    link,
                    referer = "$directUrl/",
                    quality = Qualities.P1080.value,
                    INFER_TYPE,
                )
            )
        }
        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

}
