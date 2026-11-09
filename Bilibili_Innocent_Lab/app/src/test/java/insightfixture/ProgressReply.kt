package insightfixture

class ProgressReply(val videoGuide: Guide = Guide(), val unrelated: String = "preserved", val fail: Boolean = false) {
    class Builder(private val original: ProgressReply) {
        private var guide = original.videoGuide
        fun setVideoGuide(value: Guide) = apply { guide = value }
        fun build(): ProgressReply { check(!original.fail); return ProgressReply(guide, original.unrelated) }
    }
    companion object {
        private val DEFAULT = ProgressReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(value: ProgressReply) = Builder(value)
    }
}
class Guide(val videoPoint: Points = Points(), val unrelated: Int = 77) {
    class Builder(private val original: Guide) {
        private var points = original.videoPoint
        fun setVideoPoint(value: Points) = apply { points = value }
        fun build() = Guide(points, original.unrelated)
    }
    companion object { @JvmStatic fun newBuilder(value: Guide) = Builder(value) }
}
class Points(val pointsList: List<Point> = emptyList(), val unrelated: Int = 88, val pointPermanent: Boolean = false) {
    fun getPoints(index: Int) = pointsList[index]
    class Builder(private val original: Points) {
        private val points = original.pointsList.toMutableList()
        private var permanent = original.pointPermanent
        fun addAllPoints(values: Iterable<Point>) = apply { points.addAll(values) }
        fun setPointPermanent(value: Boolean) = apply { permanent = value }
        fun build() = Points(points.toList(), original.unrelated, permanent)
    }
    companion object { @JvmStatic fun newBuilder(value: Points) = Builder(value) }
}
data class Point(val from: Long = 0, val to: Long = 0, val content: String = "", val type: Int = 0, val cover: String = "") {
    class Builder(value: Point) {
        private var value = value
        fun setFrom(from: Long) = apply { value = value.copy(from = from) }
        fun setTo(to: Long) = apply { value = value.copy(to = to) }
        fun setContent(content: String) = apply { value = value.copy(content = content) }
        fun setCover(cover: String) = apply { value = value.copy(cover = cover) }
        fun setType(type: Int) = apply { value = value.copy(type = type) }
        fun build() = value
    }
    companion object {
        private val DEFAULT = Point()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(value: Point) = Builder(value)
    }
}
