package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@MangaSourceParser("DIVASCANS", "DivaScans", "en", ContentType.HENTAI)
internal class DivaScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DIVASCANS, 24) {

    override val configKeyDomain = ConfigKey.Domain("divascans.org")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrBlank()
        val url = if (isSearch) {
            "https://$domain/api/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", filter.query)
                addQueryParameter("page", page.toString())
            }.build()
        } else {
            "https://$domain/api/series".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                addQueryParameter("sort", when (order) {
                    SortOrder.UPDATED -> ""
                    SortOrder.NEWEST -> "latest"
                    SortOrder.ALPHABETICAL -> "az"
                    SortOrder.POPULARITY -> "popular"
                    else -> ""
                })
                filter.tags.forEach { addQueryParameter("genre", it.key) }
                filter.tagsExclude.forEach { addQueryParameter("exgenre", it.key) }
            }.build()
        }

        val jsonStr = webClient.httpGet(url).body?.string() ?: return emptyList()
        val json = jsonStr.toJSONObjectOrNull()
        val dataArray = json?.optJSONArray("data") ?: jsonStr.toJSONArrayOrNull() ?: return emptyList()

        val list = mutableListOf<Manga>()
        for (i in 0 until dataArray.length()) {
            val obj = dataArray.getJSONObject(i)
            val slug = obj.optString("urlSlug", "").ifEmpty { obj.optString("slug", "") }
            val title = obj.optString("title", "").ifEmpty { obj.optString("name", "") }
            val cover = obj.optString("coverImage", "").ifEmpty { obj.optString("thumbnail", "") }
            if (slug.isEmpty() || title.isEmpty()) continue
            
            val type = obj.optString("type", "comic").lowercase()
            val urlType = if (type.contains("novel")) "novel" else "comic"

            list.add(
                Manga(
                    id = generateUid(slug),
                    url = "/series/$urlType/$slug",
                    publicUrl = "https://$domain/series/$urlType/$slug",
                    coverUrl = if (cover.startsWith("http")) cover else "https://media.divascans.org${cover.removePrefix("/uploads")}",
                    title = title,
                    altTitles = emptySet<String>(),
                    rating = RATING_UNKNOWN,
                    contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                    tags = emptySet<MangaTag>(),
                    state = null,
                    authors = emptySet<String>(),
                    source = source,
                )
            )
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val absoluteUrl = manga.url.toAbsoluteUrl(domain)
        
        // We use evaluateJs to wait for the page to load and extract the chapters
        val script = """
            new Promise((resolve) => {
                let attempts = 0;
                const check = () => {
                    attempts++;
                    const chapterLinks = Array.from(document.querySelectorAll('a[href*="/chapter/"]'));
                    if (chapterLinks.length > 0 || attempts > 20) {
                        const chapters = chapterLinks.map(a => {
                            const spans = Array.from(a.querySelectorAll('span'));
                            const titleSpan = spans.find(s => s.className && s.className.includes('font-medium'));
                            const dateSpan = spans.find(s => s.className && s.className.includes('text-white/40'));
                            return {
                                url: a.getAttribute('href'),
                                title: titleSpan ? titleSpan.innerText : a.innerText,
                                date: dateSpan ? dateSpan.innerText : ''
                            };
                        });
                        resolve(JSON.stringify(chapters));
                    } else {
                        setTimeout(check, 500);
                    }
                };
                check();
            });
        """.trimIndent()
        
        val chaptersJsonString = context.evaluateJs(absoluteUrl, script, 30000) ?: "[]"
        val chaptersArray = chaptersJsonString.toJSONArrayOrNull()
        val chapters = mutableListOf<MangaChapter>()
        
        if (chaptersArray != null) {
            for (i in 0 until chaptersArray.length()) {
                val obj = chaptersArray.getJSONObject(i)
                val chUrl = obj.getString("url")
                val chTitle = obj.optString("title", "").trim()
                val chDate = obj.optString("date", "").trim()
                
                val chapterNumber = Regex("""(\d+(?:\.\d+)?)""").find(chTitle)?.value?.toFloatOrNull() ?: 0f
                val uploadDate = parseChapterDate(chDate)
                
                chapters.add(
                    MangaChapter(
                        id = generateUid(chUrl),
                        title = chTitle.ifEmpty { "Chapter ${chapterNumber.roundToInt()}" },
                        number = chapterNumber,
                        volume = 0,
                        url = chUrl,
                        uploadDate = uploadDate,
                        source = source,
                        scanlator = null,
                        branch = null,
                    )
                )
            }
        }
        
        val doc = webClient.httpGet(absoluteUrl).parseHtml()
        
        val scriptLd = doc.select("script[type=application/ld+json]").map { it.data() }.firstOrNull { it.contains("\"@type\":\"Book\"") }
        var description = ""
        var authors = setOf<String>()
        var tags = setOf<MangaTag>()
        var coverUrl = manga.coverUrl
        
        if (scriptLd != null) {
            val bookJson = JSONObject(scriptLd)
            description = bookJson.optString("description", "")
            val authorObj = bookJson.optJSONObject("author")
            if (authorObj != null) {
                authors = setOf(authorObj.optString("name", ""))
            }
            coverUrl = "https://media.divascans.org" + bookJson.optString("image", "").removePrefix("/uploads")
            val genreArray = bookJson.optJSONArray("genre")
            if (genreArray != null) {
                tags = (0 until genreArray.length()).mapNotNull { i ->
                    val tagStr = genreArray.optString(i)
                    if (tagStr.isNotEmpty()) MangaTag(tagStr, tagStr, source) else null
                }.toSet()
            }
        }
        
        // Description fallback
        if (description.isEmpty()) {
            val scripts = doc.select("script")
            val sb = StringBuilder()
            for (s in scripts) {
                val raw = s.data().substringBetween("self.__next_f.push(", ")", "").trim()
                if (raw.isEmpty()) continue
                val ja = raw.toJSONArrayOrNull() ?: continue
                for (i in 0 until ja.length()) {
                    (ja.opt(i) as? String)?.let { sb.append(it) }
                }
            }
            val fullData = sb.toString()
            val descMatch = Regex("""description":"(.*?)",""").find(fullData)
            if (descMatch != null) {
                description = descMatch.groupValues[1].replace("\\n", "\n").replace("\\u003c", "<").replace("\\u003e", ">")
            }
        }

        return manga.copy(
            description = org.jsoup.Jsoup.parseBodyFragment(description).text(),
            authors = authors.filter { it.isNotBlank() }.toSet(),
            tags = tags,
            coverUrl = coverUrl,
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val absoluteUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(absoluteUrl).parseHtml()
        
        val scripts = doc.select("script")
        val sb = StringBuilder()
        for (script in scripts) {
            val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
            if (raw.isEmpty()) continue
            val ja = raw.toJSONArrayOrNull() ?: continue
            for (i in 0 until ja.length()) {
                (ja.opt(i) as? String)?.let { sb.append(it) }
            }
        }
        val fullData = sb.toString()
        
        // The images are typically inside an array like ["https://...", "https://..."]
        // We will look for "images":["..."]
        val imagesRegex = Regex(""""images":\[(.*?)\]""")
        val match = imagesRegex.find(fullData)
        if (match != null) {
            val imagesStr = "[" + match.groupValues[1] + "]"
            val imagesArray = imagesStr.toJSONArrayOrNull()
            if (imagesArray != null) {
                return (0 until imagesArray.length()).map { i ->
                    val url = imagesArray.getString(i)
                    MangaPage(
                        id = generateUid(url),
                        url = url.replace("\\u0026", "&"),
                        preview = null,
                        source = source
                    )
                }
            }
        }
        
        // Fallback: search for any URL that looks like a manga image
        val imageMatches = Regex("""https://media\.divascans\.org[^\s"'\\]+\.(?:jpg|jpeg|png|webp)""").findAll(fullData)
        val urls = imageMatches.map { it.value.replace("\\u0026", "&") }.distinct().toList()
        
        return urls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    private fun parseChapterDate(dateText: String): Long {
        if (dateText.isEmpty()) return 0L
        val lower = dateText.lowercase(Locale.ENGLISH)
        return try {
            when {
                lower.contains("minute") || lower.contains("min") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.MINUTE, -number) }.timeInMillis
                }
                lower.contains("hour") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.HOUR, -number) }.timeInMillis
                }
                lower.contains("day") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
                }
                lower.contains("week") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
                }
                lower.contains("month") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.MONTH, -number) }.timeInMillis
                }
                lower.contains("year") -> {
                    val number = lower.filter { it.isDigit() }.toIntOrNull() ?: return 0L
                    Calendar.getInstance().apply { add(Calendar.YEAR, -number) }.timeInMillis
                }
                else -> {
                    // Try parsing "July 10, 2024" or similar
                    val format = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                    format.parse(dateText)?.time ?: 0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    private var availableTagsCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        var tags = availableTagsCache
        if (tags == null) {
            val list = mutableListOf<MangaTag>()
            try {
                // Fetch Genres
                val genreJson = webClient.httpGet("https://$domain/api/genres").body?.string()?.toJSONObjectOrNull()
                val genreArray = genreJson?.optJSONArray("genres")
                if (genreArray != null) {
                    for (i in 0 until genreArray.length()) {
                        val obj = genreArray.getJSONObject(i)
                        val slug = obj.optString("slug")
                        val name = obj.optString("name")
                        if (slug.isNotEmpty() && name.isNotEmpty()) {
                            list.add(MangaTag(key = slug, title = name, source = source))
                        }
                    }
                }
                
                // Fetch Tags
                val tagJson = webClient.httpGet("https://$domain/api/tags").body?.string()?.toJSONObjectOrNull()
                val tagArray = tagJson?.optJSONArray("tags")
                if (tagArray != null) {
                    for (i in 0 until tagArray.length()) {
                        val obj = tagArray.getJSONObject(i)
                        val slug = obj.optString("slug")
                        val name = obj.optString("name")
                        if (slug.isNotEmpty() && name.isNotEmpty()) {
                            list.add(MangaTag(key = slug, title = name, source = source))
                        }
                    }
                }
                
                tags = list.toSet()
                if (tags.isNotEmpty()) {
                    availableTagsCache = tags
                }
            } catch (e: Exception) {
                // Ignore network errors and fallback to empty set if it fails
                tags = emptySet()
            }
        }
        
        return MangaListFilterOptions(
            availableTags = tags ?: emptySet(),
            availableStates = EnumSet.noneOf(MangaState::class.java),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.HENTAI),
        )
    }
}
