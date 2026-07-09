package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MANGA_DISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGA_DISTRICT, "mangadistrict.com", pageSize = 30) {

	override val tagPrefix = "publication-genre/"
	override val withoutAjax: Boolean = true
	override val datePattern: String = "MMMM d, yyyy"
	override val stylePage: String = "?style=list"

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = doc.body()
			.select("li.wp-manga-chapter")
			.mapNotNull { li ->
				val a = li.selectFirstOrThrow("a")
				val href = a.attrAsRelativeUrl("href")
				val link = href + stylePage

				val name = a.selectFirst("p")?.text()
					?: a.ownText()

				val dateText =
					li.selectFirst("a.c-new-tag")?.attr("title")
						?: li.selectFirst("span.chapter-release-date i")?.text()

				MangaChapter(
					id = generateUid(href),
					title = name,
					number = extractChapterNumber(name) ?: 0f,
					volume = 0,
					url = link,
					uploadDate = parseChapterDate(dateFormat, dateText),
					scanlator = null,
					branch = null,
					source = source,
				)
			}
			.sortedWith(
				compareBy<MangaChapter> { it.number }
					.thenBy { it.title }
			)

		return chapters
	}

	private fun extractChapterNumber(name: String): Float? {
		val match = Regex(
			"""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""",
			RegexOption.IGNORE_CASE
		).find(name)

		return match
			?.groupValues
			?.getOrNull(1)
			?.toFloatOrNull()
	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = mapOf(
			"3d" to "3D",
			"action" to "Action",
			"adapted-to-anime" to "Adapted to Anime",
			"adventure" to "Adventure",
			"aliens" to "Aliens",
			"animal-characteristics" to "Animal Characteristics",
			"based-on-another-work" to "Based on Another Work",
			"bl" to "BL",
			"bl-uncensored" to "BL Uncensored",
			"borderline-h" to "Borderline H",
			"cohabitation" to "Cohabitation",
			"collection-of-stories" to "Collection of Stories",
			"comedy" to "Comedy",
			"comics" to "Comics",
			"cooking" to "Cooking",
			"coworkers" to "Coworkers",
			"crime" to "Crime",
			"crossdressing" to "Crossdressing",
			"delinquents" to "Delinquents",
			"demons" to "Demons",
			"detectives" to "Detectives",
			"doujinshi" to "Doujinshi",
			"drama" to "Drama",
			"ecchi" to "Ecchi",
			"explicit-sex" to "Explicit Sex",
			"fantasy" to "Fantasy",
			"fetish" to "Fetish",
			"full-color" to "Full Color",
			"gender-bender" to "Gender Bender",
			"ghosts" to "Ghosts",
			"gl" to "GL",
			"gyaru" to "Gyaru",
			"harem" to "Harem",
			"historical" to "Historical",
			"horror" to "Horror",
			"incest" to "Incest",
			"isekai" to "Isekai",
			"japanese-webtoons" to "Japanese Webtoons",
			"josei" to "Josei",
			"light-novels" to "Light Novels",
			"mafia" to "Mafia",
			"magic" to "Magic",
			"magical-girl" to "Magical Girl",
			"manhua" to "Manhua",
			"manhwa" to "Manhwa",
			"martial-arts" to "Martial Arts",
			"mature-romance" to "Mature Romance",
			"mecha" to "Mecha",
			"medical" to "Medical",
			"military" to "Military",
			"monster-girls" to "Monster Girls",
			"monsters" to "Monsters",
			"music" to "Music",
			"mystery" to "Mystery",
			"ninja" to "Ninja",
			"nudity" to "Nudity",
			"one-shot" to "One Shot",
			"person-in-a-strange-world" to "Person in a Strange World",
			"police" to "Police",
			"psychological" to "Psychological",
			"reincarnation" to "Reincarnation",
			"reverse-harem" to "Reverse Harem",
			"romance" to "Romance",
			"salaryman" to "Salaryman",
			"samurai" to "Samurai",
			"school-life" to "School Life",
			"sci-fi" to "Sci Fi",
			"seinen" to "Seinen",
			"sexual-abuse" to "Sexual Abuse",
			"sexual-content" to "Sexual Content",
			"shoujo" to "Shoujo",
			"shoujo-ai" to "Shoujo-ai",
			"shounen" to "Shounen",
			"shounen-ai" to "Shounen-ai",
			"siblings" to "Siblings",
			"slice-of-life" to "Slice of Life",
			"smut" to "Smut",
			"sports" to "Sports",
			"summoned-into-another-world" to "Summoned Into Another World",
			"superheroes" to "Superheroes",
			"supernatural" to "Supernatural",
			"survival" to "Survival",
			"thriller" to "Thriller",
			"time-travel" to "Time Travel",
			"transfer-students" to "Transfer Students",
			"uncensored" to "Uncensored",
			"vampires" to "Vampires",
			"violence" to "Violence",
			"virtual-reality" to "Virtual Reality",
			"web-novels" to "Web Novels",
			"webtoons" to "Webtoons",
			"western" to "Western",
			"work-life" to "Work Life",
			"yaoi" to "Yaoi",
			"yuri" to "Yuri",
			"zombies" to "Zombies"
		)
		return MangaListFilterOptions(
			availableTags = tags.map { (key, title) ->
				MangaTag(key = key, title = title, source = source)
			}.toSet()
		)
	}
}
