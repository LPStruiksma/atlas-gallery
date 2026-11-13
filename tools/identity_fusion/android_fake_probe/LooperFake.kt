package android.os
class Looper {
    companion object {
        val main = Looper()
        var current: Looper? = null
        fun myLooper() = current
        fun getMainLooper() = main
    }
}
