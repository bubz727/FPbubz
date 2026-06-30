package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.util.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@MangaSourceParser("YURIBASE", "YuriBase", "id")
internal class YuriBase(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YURIBASE, 8) {

	override val configKeyDomain = ConfigKey.Domain("yuribase.id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet()
	)

	private fun extractJsonArray(jsonStr: String, key: String): JSONArray? {
		val searchStr = "\"$key\":["
		val startIdx = jsonStr.indexOf(searchStr)
		if (startIdx == -1) return null

		var bracketCount = 0
		var inString = false
		var escapeNext = false
		val arrayStart = startIdx + searchStr.length - 1

		for (i in arrayStart until jsonStr.length) {
			val c = jsonStr[i]
			if (escapeNext) {
				escapeNext = false
				continue
			}
			if (c == '\\') {
				escapeNext = true
				continue
			}
			if (c == '"') {
				inString = !inString
				continue
			}
			if (!inString) {
				if (c == '[') bracketCount++
				else if (c == ']') {
					bracketCount--
					if (bracketCount == 0) {
						val arrayStr = jsonStr.substring(arrayStart, i + 1)
						return arrayStr.toJSONArrayOrNull()
					}
				}
			}
		}
		return null
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/manga/update?page=$page"
		val doc = webClient.httpGet(url).parseHtml()
		val scripts = doc.select("script")

		val jsonLines = StringBuilder()
		for (script in scripts) {
			val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { jsonLines.append(it) }
			}
		}

		val jsonStr = jsonLines.toString()
		val array = extractJsonArray(jsonStr, "initialMangas") ?: return emptyList()

		val list = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			val obj = array.optJSONObject(i) ?: continue
			val slug = obj.optString("mangaSlug")
			if (slug.isEmpty()) continue
			if (obj.optBoolean("comingSoon", false)) continue
			
			list.add(
				Manga(
					id = generateUid(slug),
					title = obj.optString("title"),
					altTitles = emptySet(),
					url = "/manga/$slug",
					publicUrl = "https://$domain/manga/$slug",
					rating = RATING_UNKNOWN,
					contentRating = if (obj.optBoolean("nsfw", false)) ContentRating.ADULT else ContentRating.SAFE,
					coverUrl = obj.optString("bannerImage"),
					tags = emptySet(),
					state = when (obj.optString("status").lowercase()) {
						"ongoing" -> MangaState.ONGOING
						"complete" -> MangaState.FINISHED
						else -> null
					},
					authors = emptySet(),
					source = source,
				)
			)
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()

		val description = doc.select("[class*=group/synopsis] p").first()?.text()
			?: doc.select("p").firstOrNull { it.text().length > 50 }?.text()

		val tags = doc.select("a[href^=/genre/] span").mapNotNullToSet {
			val text = it.text().trim()
			if (text.isEmpty()) null else MangaTag(text, text.lowercase(), source)
		}

		val authors = doc.select("a[href^=/author/] span").mapNotNullToSet {
			it.text().trim().takeIf { t -> t.isNotEmpty() }
		}

		val stateStr = doc.selectFirst("span.text-violet-400")?.text()
		val state = when (stateStr?.lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"complete" -> MangaState.FINISHED
			else -> null
		}

		val scripts = doc.select("script")
		val jsonLines = StringBuilder()
		for (script in scripts) {
			val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { jsonLines.append(it) }
			}
		}
		val jsonStr = jsonLines.toString()

		val chaptersArray = extractJsonArray(jsonStr, "chapters")
		val chapters = ArrayList<MangaChapter>()

		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val obj = chaptersArray.optJSONObject(i) ?: continue
				val chapterId = obj.optString("id")
				val chapterNumber = obj.optDouble("chapterNumber", 0.0).toFloat()
				if (chapterId.isEmpty()) continue

				chapters.add(
					MangaChapter(
						id = generateUid(chapterId),
						title = "Chapter ${chapterNumber.toString().removeSuffix(".0")}",
						number = chapterNumber,
						volume = 0,
						url = "${manga.url}/chapter/$chapterId", // TODO: adjust if needed
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				)
			}
		}

		return manga.copy(
			description = description ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			authors = authors.ifEmpty { manga.authors },
			state = state ?: manga.state,
			chapters = chapters.reversed()
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val payload = JSONObject().apply {
			put("chapterId", chapterId)
			put("isHD", true)
		}

		val response = webClient.httpPost(
			"https://www.yuribase.id/api/chapter/pages".toHttpUrl(),
			payload
		).parseJson()

		val pagesArray = response.optJSONArray("pages") ?: return emptyList()

		val pages = ArrayList<MangaPage>()
		for (i in 0 until pagesArray.length()) {
			val url = pagesArray.optString(i)
			if (url.isEmpty()) continue
			pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
		}

		return pages
	}
}