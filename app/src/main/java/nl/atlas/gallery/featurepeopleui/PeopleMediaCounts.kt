package nl.atlas.gallery.featurepeopleui

import java.text.NumberFormat
import java.util.Locale

/** Only distinct, grant-scoped media counts; face observations are never counted here. */
data class PeopleMediaCounts(val photos: Int, val videos: Int) {
    init {
        require(photos >= 0 && videos >= 0 &&
            photos.toLong() + videos.toLong() <= Int.MAX_VALUE)
    }
    val mediaCount: Int get() = photos + videos
}

/** An empty profile shows "0 photos"; absent media types do not create a second label. */
fun peopleMediaCountLabel(photos: Int, videos: Int, locale: Locale): String {
    PeopleMediaCounts(photos, videos)
    val number = NumberFormat.getInstance(locale)
    val parts = ArrayList<String>(2)
    if (photos > 0 || videos == 0) {
        parts += "${number.format(photos)} ${if (photos == 1) "photo" else "photos"}"
    }
    if (videos > 0) {
        parts += "${number.format(videos)} ${if (videos == 1) "video" else "videos"}"
    }
    return parts.joinToString(" · ")
}

/** TalkBack gets both media types as separate clauses, not a spoken middle dot. */
fun peopleMediaCountContentDescription(
    name: String, photos: Int, videos: Int, locale: Locale
): String {
    require(name.isNotBlank())
    return "${name}, ${peopleMediaCountLabel(photos, videos, locale).replace(" · ", ", ")}"
}
