package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostGetter
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostRead
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightRpc
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Method
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject

/** 从已加载的空间动态提取发布频率和正文样式，内存只保留结构化特征。 */
internal class FishAccountActivity {
    private data class Post(
        val id: String, val day: Long, val textOnly: Boolean, val style: FishDynamicStyle.Evidence?
    )
    private class Account {
        var archiveZeroAt: Long? = null
        var hidden = false
        val posts = LinkedHashMap<String, Post>()
    }

    private val accounts = LinkedHashMap<Long, Account>(16, 0.75f, true)

    @Synchronized
    fun observeSpace(uid: Long, data: JSONObject, nowMillis: Long): Int? {
        if (uid <= 0 || identity(data.optJSONObject("card")?.opt("mid")) != uid) return null
        val archive = data.optJSONObject("archive")
        val count = number(archive?.opt("count"))
        val hidden = flag(data.optJSONObject("hidden_attribute")?.opt("is_space_hidden"))
        val visible = flag(data.optJSONObject("setting")?.opt("submited_video"))
        val hasUploads = count?.let { it > 0L } == true || (archive?.optJSONArray("item")?.length() ?: 0) > 0
        val qualifies = hidden != true && visible == true && count == 0L && !hasUploads
        val account = accounts[uid] ?: if (qualifies || hidden == true) account(uid) else return null
        account.hidden = hidden == true
        if (account.hidden) account.posts.clear()
        account.archiveZeroAt = nowMillis.takeIf { qualifies }
        return points(account, nowMillis)
    }

    @Synchronized
    fun observe(host: String, path: String, uid: Long, data: JSONObject, nowMillis: Long): Int? {
        if (host != "api.bilibili.com" || path != DYNAMIC_PATH || uid <= 0) return null
        val list = data.optJSONArray("list") ?: return null
        val today = dateAt(nowMillis)
        val posts = (0 until minOf(list.length(), MAX_PAGE)).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val extend = item.optJSONObject("extend") ?: return@mapNotNull null
            val modules = item.optJSONArray("modules") ?: return@mapNotNull null
            if (modules.length() > MAX_MODULES) return@mapNotNull null
            val entries = (0 until modules.length()).mapNotNull(modules::optJSONObject)
            val author = entries.mapNotNull { it.optJSONObject("module_author") }.singleOrNull()
                ?: return@mapNotNull null
            if (identity(author.opt("mid")) != uid || !matchesOptionalUid(extend.opt("uid"), uid)) return@mapNotNull null
            val type = number(item.opt("card_type")) ?: return@mapNotNull null
            val itemType = if (item.has("item_type")) number(item.opt("item_type")) ?: return@mapNotNull null else 0L
            val ownText = entries.mapNotNull { readText(it.optJSONObject("module_desc")) }.singleOrNull()
            val textOnly = type == WORD_TYPE && itemType in WORD_ITEM_TYPES && ownText != null &&
                entries.none { it.optJSONObject("module_dynamic") != null }
            post(extend.opt("dyn_id_str"), author.opt("ptime_label_text") as? String, today, textOnly, ownText,
                flag(author.opt("is_top")) == true)
        }
        return observePosts(uid, posts, nowMillis)
    }

    fun install(environment: HookEnvironment, onScore: (uid: Long, points: Int) -> Unit) {
        val loader = environment.classLoader ?: return
        val routes = runCatching {
            val request = KavaMemberLookup.classOrNull(loader, BASE + "DynSpaceReq") ?: return@runCatching 0
            val reply = KavaMemberLookup.classOrNull(loader, BASE + "DynSpaceRsp") ?: return@runCatching 0
            val uid = hostGetter(request, "getHostUid", classOf<Long>()) ?: return@runCatching 0
            val reader = NativePosts.resolve(loader, reply) ?: return@runCatching 0
            InsightRpc.install(environment, BASE + "DynamicMoss", "dynSpace", request, reply) { req, rsp ->
                val owner = (hostRead(uid, req) as? Long)?.takeIf { it > 0L } ?: return@install rsp
                val now = System.currentTimeMillis()
                val posts = reader.read(rsp, owner, dateAt(now))
                synchronized(this) {
                    observePosts(owner, posts, now)?.let { onScore(owner, it) }
                }
                rsp
            }
        }.getOrDefault(0)
        environment.logInfo("comment.filter.activity.dynamic", "[BIL] 评论账号动态观察: native=$routes")
    }

    private fun observePosts(uid: Long, posts: List<Post>, nowMillis: Long): Int? {
        if (posts.isEmpty()) return accounts[uid]?.let { points(it, nowMillis) }
        val account = account(uid)
        if (account.hidden) return 0
        val oldest = dateAt(nowMillis).toEpochDay() - LOOKBACK_DAYS
        account.posts.entries.removeAll { it.value.day < oldest }
        for (post in posts) {
            val previous = account.posts[post.id]
            if (previous == null || (previous.textOnly && !post.textOnly) ||
                (previous.style == null && post.style != null && previous.textOnly == post.textOnly)
            ) account.posts[post.id] = post
        }
        if (account.posts.size > MAX_POSTS) {
            val keep = account.posts.values.sortedByDescending(Post::day).take(MAX_POSTS).mapTo(HashSet()) { it.id }
            account.posts.keys.retainAll(keep)
        }
        return points(account, nowMillis)
    }

    private fun account(uid: Long): Account = accounts[uid] ?: Account().also {
        if (accounts.size >= MAX_ACCOUNTS) accounts.remove(accounts.keys.first())
        accounts[uid] = it
    }

    private fun points(account: Account, nowMillis: Long): Int {
        if (account.hidden) return 0
        val today = dateAt(nowMillis).toEpochDay()
        val recent = account.posts.values.filter { it.day in (today - LOOKBACK_DAYS)..today }
        val style = stylePoints(recent)
        val zeroAt = account.archiveZeroAt ?: return style
        if (nowMillis - zeroAt !in 0L..ARCHIVE_TTL_MILLIS || recent.any { !it.textOnly }) return style
        val byDay = recent.groupingBy(Post::day).eachCount()
        for (end in (today - LATEST_STREAK_DAYS)..today) {
            val days = (end - STREAK_DAYS + 1..end).map { byDay[it] ?: 0 }
            if (days.all { it > 0 } && days.sum() >= MIN_POSTS) return POINTS + style
        }
        return style
    }

    private fun stylePoints(recent: List<Post>): Int {
        val styles = recent.mapNotNull(Post::style)
        if (styles.isEmpty()) return 0
        val repeatedIntro = styles.mapNotNull(FishDynamicStyle.Evidence::introFingerprint)
            .groupingBy { it }.eachCount().values.any { it >= 2 }
        val composite = styles.count { it.composedIntro || it.browsingIntro }
        val timelines = styles.count(FishDynamicStyle.Evidence::narratedTimeline)
        return when {
            repeatedIntro && composite >= 1 -> 6
            composite >= 2 || composite == 1 && timelines >= 2 -> 4
            timelines >= 3 || styles.any(FishDynamicStyle.Evidence::composedIntro) -> 2
            else -> 0
        }
    }

    private class NativePosts(
        private val list: Method, private val type: Method, private val itemType: Method,
        private val hasExtend: Method, private val extend: Method, private val id: Method, private val owner: Method,
        private val modules: Method, private val hasAuthor: Method, private val author: Method, private val mid: Method,
        private val time: Method, private val hasDesc: Method, private val desc: Method, private val text: Method,
        private val hasDynamic: Method, private val pinned: Method?, private val wordType: Int
    ) {
        fun read(reply: Any, uid: Long, today: LocalDate): List<Post> {
            val items = hostRead(list, reply) as? List<*> ?: return emptyList()
            return items.take(MAX_PAGE).mapNotNull { item ->
                if (hostRead(hasExtend, item) != true) return@mapNotNull null
                val extra = hostRead(extend, item) ?: return@mapNotNull null
                if (!matchesOptionalUid(hostRead(owner, extra), uid)) return@mapNotNull null
                val parts = hostRead(modules, item) as? List<*> ?: return@mapNotNull null
                if (parts.size > MAX_MODULES) return@mapNotNull null
                val authorPart = parts.filter { hostRead(hasAuthor, it) == true }.singleOrNull()
                val user = hostRead(author, authorPart) ?: return@mapNotNull null
                if (hostRead(mid, user) != uid) return@mapNotNull null
                val cardType = hostRead(type, item) as? Int ?: return@mapNotNull null
                val contentType = hostRead(itemType, item) as? Int ?: return@mapNotNull null
                val ownText = parts.filter { hostRead(hasDesc, it) == true }.singleOrNull()
                    ?.let { hostRead(text, hostRead(desc, it)) as? String }?.takeIf(String::isNotBlank)
                val textOnly = cardType == wordType && (contentType == 0 || contentType == wordType) && ownText != null &&
                    parts.none { hostRead(hasDynamic, it) != false }
                post(hostRead(id, extra), hostRead(time, user) as? String, today, textOnly, ownText,
                    hostRead(pinned, user) == true)
            }
        }

        companion object {
            fun resolve(loader: ClassLoader, response: Class<*>): NativePosts? {
                val item = KavaMemberLookup.classOrNull(loader, BASE + "DynamicItem") ?: return null
                val module = KavaMemberLookup.classOrNull(loader, BASE + "Module") ?: return null
                val dynamicType = KavaMemberLookup.classOrNull(loader, BASE + "DynamicType") ?: return null
                val wordType = dynamicType.getField("word_VALUE").getInt(null).takeIf { it > 0 } ?: return null
                val extend = hostGetter(item, "getExtend") ?: return null
                val author = hostGetter(module, "getModuleAuthor") ?: return null
                val desc = hostGetter(module, "getModuleDesc") ?: return null
                return NativePosts(
                    hostGetter(response, "getListList", classOf<List<*>>()) ?: return null,
                    hostGetter(item, "getCardTypeValue", classOf<Int>()) ?: return null,
                    hostGetter(item, "getItemTypeValue", classOf<Int>()) ?: return null,
                    hostGetter(item, "hasExtend", classOf<Boolean>()) ?: return null, extend,
                    hostGetter(extend.returnType, "getDynIdStr", classOf<String>()) ?: return null,
                    hostGetter(extend.returnType, "getUid", classOf<Long>()) ?: return null,
                    hostGetter(item, "getModulesList", classOf<List<*>>()) ?: return null,
                    hostGetter(module, "hasModuleAuthor", classOf<Boolean>()) ?: return null, author,
                    hostGetter(author.returnType, "getMid", classOf<Long>()) ?: return null,
                    hostGetter(author.returnType, "getPtimeLabelText", classOf<String>()) ?: return null,
                    hostGetter(module, "hasModuleDesc", classOf<Boolean>()) ?: return null, desc,
                    hostGetter(desc.returnType, "getText", classOf<String>()) ?: return null,
                    hostGetter(module, "hasModuleDynamic", classOf<Boolean>()) ?: return null,
                    hostGetter(author.returnType, "getIsTop", classOf<Boolean>()), wordType
                )
            }
        }
    }

    private companion object {
        const val BASE = "com.bapis.bilibili.app.dynamic.v2."
        const val DYNAMIC_PATH = "/x/v2/dynamic/dyn/space"
        const val WORD_TYPE = 6L
        val WORD_ITEM_TYPES = setOf(0L, WORD_TYPE)
        const val POINTS = 6
        const val STREAK_DAYS = 7
        const val MIN_POSTS = 10
        const val LOOKBACK_DAYS = 30L
        const val LATEST_STREAK_DAYS = 7L
        const val ARCHIVE_TTL_MILLIS = 30 * 60 * 1000L
        const val MAX_ACCOUNTS = 64
        const val MAX_POSTS = 128
        const val MAX_PAGE = 40
        const val MAX_MODULES = 16
        const val MAX_STYLE_TEXT = 1_200
        val ZONE = ZoneOffset.ofHours(8)
        val ID = Regex("[1-9][0-9]{0,19}")
        val ISO_DATE = Regex("([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?: (?:[01][0-9]|2[0-3]):[0-5][0-9])?")
        val CHINESE_DATE = Regex("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日(?: (?:[01][0-9]|2[0-3]):[0-5][0-9])?")
        val MONTH_DAY = Regex("([0-9]{1,2})月([0-9]{1,2})日")

        fun post(rawId: Any?, label: String?, today: LocalDate, textOnly: Boolean, ownText: String?, pinned: Boolean): Post? {
            val id = (rawId as? String)?.takeIf { ID.matches(it) } ?: return null
            val day = label?.let { dateFromLabel(it, today, pinned) }?.toEpochDay() ?: return null
            val end = today.toEpochDay()
            if (day !in (end - LOOKBACK_DAYS)..end) return null
            val style = ownText?.let(FishDynamicStyle::analyze)
            return Post(id, day, textOnly, style?.takeIf {
                it.introFingerprint != null || it.composedIntro || it.narratedTimeline || it.browsingIntro
            })
        }

        fun dateFromLabel(label: String, today: LocalDate, pinned: Boolean): LocalDate? {
            if (label.length > 24) return null
            val full = ISO_DATE.matchEntire(label) ?: CHINESE_DATE.matchEntire(label)
            if (full != null) {
                val (year, month, day) = full.destructured
                return date(year.toInt(), month.toInt(), day.toInt())
            }
            if (pinned) return null
            val partial = MONTH_DAY.matchEntire(label) ?: return null
            val (month, day) = partial.destructured
            val thisYear = date(today.year, month.toInt(), day.toInt())
            if (thisYear != null && thisYear <= today) return thisYear
            return date(today.year - 1, month.toInt(), day.toInt())
        }

        fun date(year: Int, month: Int, day: Int): LocalDate? {
            return try {
                LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                null
            }
        }

        fun dateAt(nowMillis: Long): LocalDate = Instant.ofEpochMilli(nowMillis).atOffset(ZONE).toLocalDate()

        fun matchesOptionalUid(raw: Any?, uid: Long): Boolean =
            raw == null || identity(raw)?.let { it == 0L || it == uid } == true

        fun readText(desc: JSONObject?): String? {
            val plain = (desc?.opt("text") as? String)?.take(MAX_STYLE_TEXT)
            if (!plain.isNullOrBlank()) return plain
            val nodes = desc?.optJSONArray("desc") ?: return null
            return buildString {
                for (index in 0 until minOf(nodes.length(), 32)) {
                    val text = nodes.optJSONObject(index)?.opt("text") as? String ?: continue
                    append(text, 0, minOf(text.length, MAX_STYLE_TEXT - length))
                    if (length == MAX_STYLE_TEXT) break
                }
            }.takeIf(String::isNotBlank)
        }

        fun flag(raw: Any?): Boolean? = when {
            raw is Boolean -> raw
            number(raw) == 0L -> false
            number(raw) == 1L -> true
            else -> null
        }

        fun identity(raw: Any?): Long? = number(raw) ?: (raw as? String)?.takeIf(ID::matches)?.toLongOrNull()

        fun number(raw: Any?): Long? = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }
    }
}
