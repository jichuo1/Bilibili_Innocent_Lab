package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

internal object PrivacyVideoMetadata {
    /** An explicit add can target an unseen recommendation. Read archive data, never invent a cid or create history. */
    fun fetch(host: PrivacyHostAccess, aid: Long): LocalVirtualLibrary.Video {
        require(aid in 1..LocalVirtualLibrary.MAX_ID)
        val request = requireNotNull(host.static(host.type("com.bapis.bilibili.app.view.v1.ViewReq"), "newBuilder"))
        host.call(request, "setAid", aid)
        val reply = requireNotNull(host.call(host.create(host.type("com.bapis.bilibili.app.view.v1.ViewMoss")),
            "executeView", host.call(request, "build")))
        return read(host, reply).also { require(it.aid == aid) }
    }

    fun read(host: PrivacyHostAccess, reply: Any): LocalVirtualLibrary.Video {
        val arc = host.call(reply, "getArc") ?: error("Missing arc")
        val aid = (host.call(arc, "getAid") as Number).toLong()
        val cid = ((Lookup.methodOrNull(arc.javaClass, "getCid")?.invoke(arc)
            ?: host.call(arc, "getFirstCid")) as Number).toLong()
        val owner = if (Lookup.methodOrNull(reply.javaClass, "getOwner") != null)
            host.call(reply, "getOwner") else host.call(arc, "getAuthor")
        val cover = if (Lookup.methodOrNull(arc.javaClass, "getCover") != null)
            host.call(arc, "getCover") else host.call(arc, "getPic")
        return LocalVirtualLibrary.Video(aid, cid,
            host.call(arc, "getTitle") as String,
            (host.call(arc, "getDuration") as Number).toLong() * 1000L, 0, 0,
            cover as? String, owner?.let { (host.call(it, "getMid") as Number).toLong() } ?: 0L,
            owner?.let {
                // 8.90.2 viewunite Owner calls the author name "title"; archive Author uses "name".
                val getter = if (Lookup.methodOrNull(it.javaClass, "getTitle") != null) "getTitle" else "getName"
                host.call(it, getter) as String
            } ?: "", owner?.let { Lookup.methodOrNull(it.javaClass, "getFace")?.invoke(it) as? String }.orEmpty())
    }
}
