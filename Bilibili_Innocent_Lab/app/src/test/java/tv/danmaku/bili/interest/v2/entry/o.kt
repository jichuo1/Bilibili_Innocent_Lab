package tv.danmaku.bili.interest.v2.entry

/** Test fixture: the 9.15.2+ entry decision, `(int, String, ref, boolean, ref): boolean`. */
class o {
    var started = 0
    fun a(action: Int, source: String?, params: Any?, firstResponseOnly: Boolean, step: Any?): Boolean {
        started++
        return true
    }
    fun other(x: Int): Boolean = x > 0
}
