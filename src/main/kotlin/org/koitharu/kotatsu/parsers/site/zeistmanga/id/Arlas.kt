package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.koitharu.kotatsu.parsers.Broken

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser

@Broken("Dead (DNS Error / Not Found)")
@MangaSourceParser("ARLAS", "Arlas", "id")
internal class Arlas(context: MangaLoaderContext) :
    ZeistMangaParser(context, org.koitharu.kotatsu.parsers.model.MangaParserSource.ARLAS, "arlas.my.id")
