package nl.atlas.gallery

/**
 * Guard raw, untrusted MediaStore epoch fields before promoting them to a
 * timeline date. MediaStore DATE_TAKEN is milliseconds; DATE_MODIFIED and
 * DATE_ADDED are seconds. Keep the 2100 upper year consistent with ATLAS's
 * strictly parsed camera filename hints; do not let Long.MAX_VALUE become a
 * 292-million-year gallery header or overflow the seconds conversion.
 *
 * This bounds implausible provider data, not the age of photographed subjects.
 * Existing pre-1970 source dates are still represented by provenance hints,
 * not fabricated as epoch-zero or negative MediaStore timestamps.
 */
private const val EXCLUSIVE_MEDIA_DATE_MILLIS_2101 = 4_133_980_800_000L
private const val EXCLUSIVE_MEDIA_DATE_SECONDS_2101 =
    EXCLUSIVE_MEDIA_DATE_MILLIS_2101 / 1000L

internal fun isPlausibleMediaTakenMillis(value: Long): Boolean =
    value > 0L && value < EXCLUSIVE_MEDIA_DATE_MILLIS_2101

internal fun isPlausibleMediaEpochSeconds(value: Long): Boolean =
    value > 0L && value < EXCLUSIVE_MEDIA_DATE_SECONDS_2101
