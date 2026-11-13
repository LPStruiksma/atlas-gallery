import nl.atlas.gallery.*

fun main() {
    val a = (0 until 1000).map { i ->
        MediaRecord(i.toLong(), "content://image/$i", "Photo_$i.jpg",
            if (i % 2 == 0) "DCIM/Black and White/" else "DCIM/Other/",
            i.toLong() * 20, 1_000_000L + i, false)
    }
    check(sortMedia(a, SortOrder.NEWEST, 0).first().id == 999L)
    check(sortMedia(a, SortOrder.OLDEST, 0).first().id == 0L)
    check(sortMedia(a, SortOrder.LARGEST, 0).first().id == 999L)
    val shuffled = sortMedia(a, SortOrder.RANDOM, 55)
    check(shuffled == sortMedia(a, SortOrder.RANDOM, 55))
    check(shuffled != sortMedia(a, SortOrder.RANDOM, 56))
    check(shuffled.map { it.uri }.toSet().size == 1000)
    check(selectAlbum(a, "DCIM/Black and White/").size == 500)
    check(filterMedia(a, "photo_12").size == 11)
    check(filterMedia(a, "missing").isEmpty())
    check(filterMedia(a, "Sophia", setOf(a[5].absolutePath)).single().id == 5L)
    check(selectionIndex(a, "content://image/80") == 80)
    check(selectionIndex(a, "not-present") == -1)
    val csv = "person_id,person_name,image_path,face_id,confidence\r\n" +
        "1,\"Suus, Jr.\",\"/storage/emulated/0/DCIM/Photo 1.jpg\",12,0.8\r\n" +
        "2,\"Sophia \"\"S\"\"\",\"/storage/emulated/0/DCIM/another.jpg\",13,0.9\r\n"
    val rows = parseCsv(csv)
    check(rows.size == 3)
    check(rows[1][1] == "Suus, Jr.")
    check(rows[2][1] == "Sophia \"S\"")
    check(parseCsv("a,b\n1,\"line1\nline2\"")[1][1] == "line1\nline2")
    runCatching { parseCsv("x,\"not closed") }.onSuccess { error("Expected malformed CSV") }
    println("PASS: deterministic random/album/search/index/CSV cases")
}
