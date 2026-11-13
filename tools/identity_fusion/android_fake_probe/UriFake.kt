package android.net
class Uri(val scheme: String?, val authority: String?) {
    companion object {
        fun parse(src: String): Uri {
            val s = src.substringBefore("://", "")
            val rest = src.substringAfter("://", "")
            return Uri(s, rest.substringBefore('/'))
        }
    }
}
