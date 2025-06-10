package com.keyiflerolsun

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import kotlinx.coroutines.*
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl         = "https://dizipal953.com"
    override var name            = "DiziPal"
    override val hasMainPage     = true
    override var lang            = "tr"
    override val hasQuickSearch  = true
    override val supportedTypes  = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare bypass interceptor
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // Sadece ana sayfa ve koleksiyon gibi riskli URL'lerde bypass yapıyoruz
            if (!request.url.encodedPath.contains("/diziler") &&
                !request.url.encodedPath.contains("/filmler") &&
                !request.url.encodedPath.contains("/koleksiyon")) {
                return chain.proceed(request)
            }
            val response = chain.proceed(request)
            val body     = response.peekBody(1024 * 512).string()
            return if (body.contains("Just a moment")) {
                cloudflareKiller.intercept(chain)
            } else {
                response
            }
        }
    }

    // OkHttp cache kullanımı (örn. 10 MB)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File("/mnt/data/okhttp_cache"), 10L * 1024 * 1024))
            .addInterceptor(interceptor)
            .build()
    }

    override val mainPage = mainPageOf(
                "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"                            to "Netflix",
        "${mainUrl}/koleksiyon/exxen"                              to "Exxen",
        "${mainUrl}/koleksiyon/blutv"                              to "BluTV",
        "${mainUrl}/koleksiyon/disney"                             to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"                       to "Amazon Prime",
        "${mainUrl}/koleksiyon/tod-bein"                           to "TOD (beIN)",
        "${mainUrl}/koleksiyon/gain"                               to "Gain",
        "${mainUrl}/tur/mubi"                                      to "Mubi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = client.newCall(request.data.toRequest(getHeaders(mainUrl))).await()
        val document = Jsoup.parse(response.body!!.string())

        // Paralel element işleme ile beklemeleri gömülü coroutines ile azaltıyoruz
        val items = coroutineScope {
            document.select("article.type2 ul li")
                .map { el -> async { el.diziler() } }
                .awaitAll()
                .filterNotNull()
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Streaming parse’e geçiş yapılabilir; şimdilik cache’li client kullanıyoruz
        val responseRaw = client.newCall(
            "${mainUrl}/api/search-autocomplete".toPost(
                headers = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to "$mainUrl/"
                ),
                body = mapOf("query" to query)
            )
        ).await()

        val map: Map<String, SearchItem> =
            jacksonObjectMapper().readValue(responseRaw.body!!.string())

        return map.values.map { it.toPostSearchResult() }
    }

    // (load, loadLinks vs. aynı kalabilir; gereksiz Log.d’leri kaldırdık, JSoup.connect yerine client kullanıyoruz)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
            "Referer"    to referer
        )
    }
}
