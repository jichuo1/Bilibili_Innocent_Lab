package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 从宿主对象同步收敛出的纯数据种子；不会把 ReplyInfo/CommentItem 带出 Hook 回调。 */
internal data class ReplyTopologySeed(
    val key: ReplyTopologyThreadKey,
    val expectedReplyCount: Int?,
    val nodes: List<ReplyTopologyNodeSnapshot>
)

internal data class ReplyTopologyHostPage(
    val nodes: List<ReplyTopologyNodeSnapshot>,
    val nextOffset: String?,
    val expectedReplyCount: Int?
)

internal fun requireMatchingReplyTopologyThread(
    expectedKey: ReplyTopologyThreadKey,
    actualKey: ReplyTopologyThreadKey
) {
    check(actualKey == expectedKey) {
        "DetailListReply thread mismatch: expected=$expectedKey, actual=$actualKey"
    }
}

/** 动态代理未知回调的故障开放返回值；避免旧版宿主对 primitive 返回值拆箱时崩溃。 */
internal fun defaultReplyTopologyProxyValue(returnType: Class<*>): Any? = when (returnType) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Character.TYPE -> '\u0000'
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    else -> null
}

/**
 * 单次分页请求的逻辑取消句柄。取消只负责断开模块侧持有关系和抑制迟到回调，
 * 不假定不同宿主版本的 MOSS 客户端存在统一的网络取消 API。
 */
internal class ReplyTopologyPageCall(
    callback: (Result<ReplyTopologyHostPage>) -> Unit
) {
    private val finished = AtomicBoolean(false)
    private val callbackRef = AtomicReference<((Result<ReplyTopologyHostPage>) -> Unit)?>(callback)
    private val responseRef = AtomicReference<Any?>(null)
    private val clientRef = AtomicReference<Any?>(null)

    internal val isActive: Boolean
        get() = !finished.get()

    internal fun retainClient(client: Any) = retainWhileActive(clientRef, client)

    internal fun retainResponse(response: Any?) {
        if (response == null) {
            responseRef.set(null)
        } else {
            retainWhileActive(responseRef, response)
        }
    }

    internal fun responseOrNull(): Any? = responseRef.get()

    internal fun complete(result: Result<ReplyTopologyHostPage>) {
        if (!finished.compareAndSet(false, true)) return
        val callback = callbackRef.getAndSet(null)
        responseRef.getAndSet(null)
        clientRef.getAndSet(null)
        callback?.invoke(result)
    }

    fun cancel() {
        finished.set(true)
        callbackRef.getAndSet(null)
        responseRef.getAndSet(null)
        clientRef.getAndSet(null)
    }

    private fun retainWhileActive(reference: AtomicReference<Any?>, value: Any) {
        if (finished.get()) return
        reference.set(value)
        // 覆盖“先观察未完成、随后与 cancel 交错写入”的竞态，确保最终不残留宿主对象。
        if (finished.get()) reference.compareAndSet(value, null)
    }
}

/**
 * CommentItem 身份只作弱引用；同时保留有界 rpid -> 纯数据种子回退，兼容宿主 data class
 * copy 后对象身份变化。两条路径均不缓存宿主实例。
 */
internal class ReplyTopologySeedStore(
    private val maxRememberedRoots: Int = 192
) {
    private val queue = ReferenceQueue<Any>()
    private val maxIdentityEntries = when {
        maxRememberedRoots <= 0 -> 0
        maxRememberedRoots > Int.MAX_VALUE / IDENTITY_CAPACITY_MULTIPLIER -> Int.MAX_VALUE
        else -> maxRememberedRoots * IDENTITY_CAPACITY_MULTIPLIER
    }
    private val byIdentity =
        LinkedHashMap<IdentityWeakReference, ReplyTopologySeed>(32, 0.75f, true)
    private val byRpid = LinkedHashMap<Long, ReplyTopologySeed>(32, 0.75f, true)

    @Synchronized
    fun put(commentItem: Any, seed: ReplyTopologySeed) {
        drainCollectedKeys()
        byIdentity[IdentityWeakReference(commentItem, queue)] = seed
        byRpid[seed.key.rootRpid] = seed
        while (byIdentity.size > maxIdentityEntries) {
            val iterator = byIdentity.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        while (byRpid.size > maxRememberedRoots) {
            val iterator = byRpid.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun get(commentItem: Any, commentItemId: Long?): ReplyTopologySeed? {
        drainCollectedKeys()
        return byIdentity[IdentityWeakReference(commentItem, null)]
            ?: commentItemId?.takeIf { it > 0L }?.let(byRpid::get)
    }

    @Synchronized
    fun clear() {
        byIdentity.clear()
        byRpid.clear()
        while (queue.poll() != null) Unit
    }

    private fun drainCollectedKeys() {
        while (true) {
            val reference = queue.poll() as? IdentityWeakReference ?: break
            byIdentity.remove(reference)
        }
    }

    private class IdentityWeakReference(
        referent: Any,
        queue: ReferenceQueue<Any>?
    ) : WeakReference<Any>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakReference || identityHash != other.identityHash) return false
            val left = get()
            return left != null && left === other.get()
        }
    }

    private companion object {
        const val IDENTITY_CAPACITY_MULTIPLIER = 2
    }
}

/**
 * Adapter 结果的运行时调用封装。这里仅持有 Class/Method/Constructor；每个 MOSS 客户端
 * 都是单请求局部对象，完成或失败后立即释放。
 */
internal class ReplyTopologyHostAccess private constructor(
    val replyInfoClass: Class<*>,
    val commentItemClass: Class<*>,
    private val commentItemId: Method,
    private val replyId: Method,
    private val replyOid: Method,
    private val replyType: Method,
    private val replyRoot: Method,
    private val replyParent: Method,
    private val replyDialog: Method,
    private val replyCtime: Method,
    private val replyCount: Method,
    private val replyMid: Method,
    private val replyContent: Method,
    private val replyMember: Method?,
    private val replyMemberV2: Method,
    private val replyParentMember: Method?,
    private val replyChildren: Method,
    private val contentMessage: Method,
    private val memberName: Method?,
    private val memberV2Basic: Method,
    private val memberBasicName: Method,
    private val parentMemberName: Method?,
    private val paginationNewBuilder: Method,
    private val paginationSetOffset: Method,
    private val paginationBuild: Method,
    private val detailNewBuilder: Method,
    private val detailSetOid: Method,
    private val detailSetType: Method,
    private val detailSetMode: Method,
    private val detailSetPagination: Method,
    private val detailSetRoot: Method,
    private val detailSetRpid: Method,
    private val detailBuild: Method,
    private val mossConstructor: Constructor<*>,
    private val mossDetail: Method,
    private val detailRoot: Method,
    private val detailPagination: Method,
    private val paginationNextOffset: Method
) {

    fun readCommentItemId(commentItem: Any): Long? =
        invokeNumber(commentItemId, commentItem)?.toLong()?.takeIf { it > 0L }

    /** Mapper after-hook 的同步热路径：非根回复只读一个 getter 即返回。 */
    fun snapshotRoot(replyInfo: Any): ReplyTopologySeed? {
        return snapshotRoot(replyInfo, MAX_MAPPER_SEED_NODES)
    }

    private fun snapshotRoot(replyInfo: Any, nodeLimit: Int): ReplyTopologySeed? {
        if (!replyInfoClass.isInstance(replyInfo)) return null
        val rootValue = invokeNumber(replyRoot, replyInfo)?.toLong() ?: return null
        if (rootValue != 0L) return null
        val rpid = invokeNumber(replyId, replyInfo)?.toLong()?.takeIf { it > 0L } ?: return null
        val oid = invokeNumber(replyOid, replyInfo)?.toLong()?.takeIf { it > 0L } ?: return null
        val type = invokeNumber(replyType, replyInfo)?.toLong()?.takeIf { it >= 0L } ?: return null
        val key = ReplyTopologyThreadKey(oid = oid, type = type, rootRpid = rpid)
        if (!key.isValid) return null
        val expected = invokeNumber(replyCount, replyInfo)?.toLong()?.toBoundedCount()
        val nodes = snapshotTree(replyInfo, rootRpid = rpid, limit = nodeLimit)
        if ((expected ?: 0) <= 0 && nodes.size <= 1) return null
        return ReplyTopologySeed(key, expected, nodes)
    }

    /** 将一次 DetailListReply 完全转成纯数据后才回调，宿主 protobuf 不会逃逸。 */
    fun requestPage(
        key: ReplyTopologyThreadKey,
        offset: String?,
        callback: (Result<ReplyTopologyHostPage>) -> Unit
    ): ReplyTopologyPageCall {
        val call = ReplyTopologyPageCall(callback)
        runCatching {
            val request = buildDetailRequest(key, offset)
            val moss = mossConstructor.newInstance()
            call.retainClient(moss)
            val handlerType = mossDetail.parameterTypes[1]
            check(handlerType.isInterface) { "MossResponseHandler is not an interface" }
            val handler = Proxy.newProxyInstance(
                handlerType.classLoader ?: moss.javaClass.classLoader,
                arrayOf(handlerType)
            ) { proxy, method, args ->
                when (method.name) {
                    "onNext" -> {
                        call.retainResponse(args?.getOrNull(0))
                        null
                    }
                    "onError" -> {
                        val cause = args?.getOrNull(0) as? Throwable
                            ?: IllegalStateException("Unknown MOSS error")
                        call.complete(Result.failure(cause))
                        null
                    }
                    "onCompleted" -> {
                        if (call.isActive) {
                            val raw = call.responseOrNull()
                            if (raw == null) {
                                call.complete(
                                    Result.failure(IllegalStateException("Empty DetailListReply"))
                                )
                            } else {
                                call.complete(runCatching { parsePage(raw, key) })
                            }
                        }
                        null
                    }
                    "onNextForAck" -> {
                        call.retainResponse(args?.getOrNull(0))
                        0L
                    }
                    "toString" -> "ReplyTopologyMossHandler"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> defaultReplyTopologyProxyValue(method.returnType)
                }
            }
            mossDetail.invoke(moss, request, handler)
        }.onFailure { throwable ->
            call.complete(Result.failure(unwrapInvocation(throwable)))
        }
        return call
    }

    private fun buildDetailRequest(key: ReplyTopologyThreadKey, offset: String?): Any {
        val pageBuilder = paginationNewBuilder.invoke(null)
            ?: error("FeedPagination.newBuilder returned null")
        paginationSetOffset.invoke(pageBuilder, offset.orEmpty())
        val page = paginationBuild.invoke(pageBuilder)
            ?: error("FeedPagination.Builder.build returned null")

        val builder = detailNewBuilder.invoke(null)
            ?: error("DetailListReq.newBuilder returned null")
        detailSetOid.invoke(builder, key.oid)
        detailSetType.invoke(builder, key.type)
        // DEFAULT=0：交由服务端选择详情排序，不猜测当前页面的热度/时间状态。
        detailSetMode.invoke(builder, DEFAULT_DETAIL_MODE)
        detailSetPagination.invoke(builder, page)
        detailSetRoot.invoke(builder, key.rootRpid)
        detailSetRpid.invoke(builder, 0L)
        return detailBuild.invoke(builder) ?: error("DetailListReq.Builder.build returned null")
    }

    private fun parsePage(
        detailReplyObject: Any,
        expectedKey: ReplyTopologyThreadKey
    ): ReplyTopologyHostPage {
        val root = detailRoot.invoke(detailReplyObject)
            ?: error("DetailListReply.root is null")
        val seed = snapshotRoot(root, MAX_PAGE_NODES)
            ?: error("DetailListReply.root identity is invalid")
        requireMatchingReplyTopologyThread(expectedKey, seed.key)
        val pagination = detailPagination.invoke(detailReplyObject)
        val nextOffset = pagination?.let { paginationNextOffset.invoke(it)?.toString() }
            ?.takeIf(String::isNotBlank)
        return ReplyTopologyHostPage(
            nodes = seed.nodes,
            nextOffset = nextOffset,
            expectedReplyCount = seed.expectedReplyCount
        )
    }

    private fun snapshotTree(
        root: Any,
        rootRpid: Long,
        limit: Int
    ): List<ReplyTopologyNodeSnapshot> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<ReplyTopologyNodeSnapshot>(minOf(limit, 32))
        val stack = ArrayDeque<Any>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < limit && result.size < limit) {
            val current = stack.removeLast()
            visited++
            if (!replyInfoClass.isInstance(current)) continue
            snapshotNode(current, rootRpid)?.let(result::add)
            val children = runCatching { replyChildren.invoke(current) as? List<*> }.getOrNull()
                ?: continue
            // 已访问 + 已入栈合计不超过 limit；宽树不会瞬时强持全部宿主 ReplyInfo。
            val remainingScheduleBudget = (limit - visited - stack.size).coerceAtLeast(0)
            val lastSelectedIndex = minOf(children.lastIndex, remainingScheduleBudget - 1)
            for (index in lastSelectedIndex downTo 0) {
                val child = children[index]
                if (child != null && replyInfoClass.isInstance(child)) stack.addLast(child)
            }
        }
        return result
    }

    private fun snapshotNode(value: Any, expectedRoot: Long): ReplyTopologyNodeSnapshot? {
        val rpid = invokeNumber(replyId, value)?.toLong()?.takeIf { it > 0L } ?: return null
        val sourceRoot = invokeNumber(replyRoot, value)?.toLong() ?: 0L
        val isRoot = sourceRoot == 0L && rpid == expectedRoot
        val content = invokeCompatible(replyContent, value)
        val legacyMember = replyMember?.let { invokeCompatible(it, value) }
        val memberV2 = invokeCompatible(replyMemberV2, value)
        val memberBasic = invokeCompatible(memberV2Basic, memberV2)
        val parentMember = replyParentMember?.let { invokeCompatible(it, value) }
        return ReplyTopologyNodeSnapshot.fromRaw(
            rpid = rpid,
            rootRpid = if (isRoot) 0L else sourceRoot.takeIf { it > 0L } ?: expectedRoot,
            parentRpid = if (isRoot) 0L else
                invokeNumber(replyParent, value)?.toLong()?.takeIf { it > 0L } ?: expectedRoot,
            dialogId = invokeNumber(replyDialog, value)?.toLong() ?: 0L,
            ctime = invokeNumber(replyCtime, value)?.toLong() ?: 0L,
            authorMid = invokeNumber(replyMid, value)?.toLong() ?: 0L,
            authorName = (invokeCompatible(memberBasicName, memberBasic) as? CharSequence)
                ?.takeIf { it.isNotBlank() }
                ?: memberName?.let { invokeCompatible(it, legacyMember) as? CharSequence },
            repliedAuthorName = parentMemberName
                ?.let { invokeCompatible(it, parentMember) as? CharSequence },
            message = invokeCompatible(contentMessage, content) as? CharSequence,
            flags = if (isRoot) ReplyTopologyNodeFlags.ROOT else 0
        )
    }

    private fun invokeNumber(method: Method, target: Any): Number? =
        invokeCompatible(method, target) as? Number

    private fun invokeCompatible(method: Method, target: Any?): Any? {
        if (target == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun Long.toBoundedCount(): Int? = when {
        this < 0L -> null
        this > Int.MAX_VALUE -> Int.MAX_VALUE
        else -> toInt()
    }

    private fun unwrapInvocation(throwable: Throwable): Throwable =
        (throwable as? InvocationTargetException)?.targetException ?: throwable

    companion object {
        private const val DEFAULT_DETAIL_MODE = 0
        private const val MAX_MAPPER_PREVIEW_NODES = 3
        private const val MAX_MAPPER_SEED_NODES = 1 + MAX_MAPPER_PREVIEW_NODES
        private const val MAX_PAGE_NODES = 96

        fun resolve(
            environment: HookEnvironment,
            points: VersionAdapter.CommentTopologyPoints
        ): ReplyTopologyHostAccess? = runCatching {
            fun required(key: String): Method {
                val point = points.methods[key] ?: throw NoSuchMethodException(key)
                return environment.hookPoints.resolveAdapted(
                    "comment.topology.resolve.$key",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                ) ?: throw NoSuchMethodException(key)
            }

            fun optional(key: String): Method? {
                val point = points.methods[key] ?: return null
                return environment.hookPoints.resolveAdapted(
                    "comment.topology.resolve.$key",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )
            }

            val resolvedMappers = points.mapperMethods.mapIndexed { index, point ->
                environment.hookPoints.resolveAdapted(
                    "comment.topology.resolve.mapper.$index",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                ) ?: throw NoSuchMethodException("comment.topology.mapper.$index")
            }
            val commentItemClass = resolvedMappers.firstOrNull()?.returnType
                ?: throw NoSuchMethodException("comment.topology.mapper.empty")
            check(resolvedMappers.all { it.returnType == commentItemClass }) {
                "Reply topology mapper return types differ"
            }
            val mossConstructor = environment.hookPoints.resolveConstructor(
                "comment.topology.resolve.moss.constructor",
                points.replyMossClassName,
                emptyList()
            ) ?: throw NoSuchMethodException("ReplyMoss.<init>")

            ReplyTopologyHostAccess(
                replyInfoClass = required(VersionAdapter.CommentTopologyPoints.REPLY_ID).declaringClass,
                commentItemClass = commentItemClass,
                commentItemId = required(VersionAdapter.CommentTopologyPoints.COMMENT_ITEM_ID),
                replyId = required(VersionAdapter.CommentTopologyPoints.REPLY_ID),
                replyOid = required(VersionAdapter.CommentTopologyPoints.REPLY_OID),
                replyType = required(VersionAdapter.CommentTopologyPoints.REPLY_TYPE),
                replyRoot = required(VersionAdapter.CommentTopologyPoints.REPLY_ROOT),
                replyParent = required(VersionAdapter.CommentTopologyPoints.REPLY_PARENT),
                replyDialog = required(VersionAdapter.CommentTopologyPoints.REPLY_DIALOG),
                replyCtime = required(VersionAdapter.CommentTopologyPoints.REPLY_CTIME),
                replyCount = required(VersionAdapter.CommentTopologyPoints.REPLY_COUNT),
                replyMid = required(VersionAdapter.CommentTopologyPoints.REPLY_MID),
                replyContent = required(VersionAdapter.CommentTopologyPoints.REPLY_CONTENT),
                replyMember = optional(VersionAdapter.CommentTopologyPoints.REPLY_MEMBER),
                replyMemberV2 = required(VersionAdapter.CommentTopologyPoints.REPLY_MEMBER_V2),
                replyParentMember = optional(VersionAdapter.CommentTopologyPoints.REPLY_PARENT_MEMBER),
                replyChildren = required(VersionAdapter.CommentTopologyPoints.REPLY_CHILDREN),
                contentMessage = required(VersionAdapter.CommentTopologyPoints.CONTENT_MESSAGE),
                memberName = optional(VersionAdapter.CommentTopologyPoints.MEMBER_NAME),
                memberV2Basic = required(VersionAdapter.CommentTopologyPoints.MEMBER_V2_BASIC),
                memberBasicName = required(VersionAdapter.CommentTopologyPoints.MEMBER_BASIC_NAME),
                parentMemberName = optional(VersionAdapter.CommentTopologyPoints.PARENT_MEMBER_NAME),
                paginationNewBuilder = required(VersionAdapter.CommentTopologyPoints.PAGINATION_NEW_BUILDER),
                paginationSetOffset = required(VersionAdapter.CommentTopologyPoints.PAGINATION_SET_OFFSET),
                paginationBuild = required(VersionAdapter.CommentTopologyPoints.PAGINATION_BUILD),
                detailNewBuilder = required(VersionAdapter.CommentTopologyPoints.DETAIL_NEW_BUILDER),
                detailSetOid = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_OID),
                detailSetType = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_TYPE),
                detailSetMode = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_MODE),
                detailSetPagination = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_PAGINATION),
                detailSetRoot = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_ROOT),
                detailSetRpid = required(VersionAdapter.CommentTopologyPoints.DETAIL_SET_RPID),
                detailBuild = required(VersionAdapter.CommentTopologyPoints.DETAIL_BUILD),
                mossConstructor = mossConstructor,
                mossDetail = required(VersionAdapter.CommentTopologyPoints.MOSS_DETAIL),
                detailRoot = required(VersionAdapter.CommentTopologyPoints.DETAIL_ROOT),
                detailPagination = required(VersionAdapter.CommentTopologyPoints.DETAIL_PAGINATION),
                paginationNextOffset = required(
                    VersionAdapter.CommentTopologyPoints.PAGINATION_NEXT_OFFSET
                )
            )
        }.onFailure { throwable ->
            environment.logError(
                "comment_topology_resolve",
                "[BIL] 回复脉络成员解析失败: $throwable"
            )
        }.getOrNull()
    }
}
