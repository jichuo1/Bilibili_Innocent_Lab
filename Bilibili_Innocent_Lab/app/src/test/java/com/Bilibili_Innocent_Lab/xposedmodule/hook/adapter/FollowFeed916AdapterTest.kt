package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostOpusBackgroundAccess
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Modifier

/** Child-loader fixtures keep each exact host generation's names and local-owner shape isolated. */
class FollowFeed916AdapterTest {
    private class Host(private val localField: String = "b", private val hideOpus: Boolean = false,
        private val profile917: Boolean = false, private val ambientNewSeparator: Boolean = false,
        private val build300: Boolean = false) :
        ClassLoader(FollowFeed916AdapterTest::class.java.classLoader) {
        private val modern = profile917 || build300
        private val painterName = if (modern) "rU.d" else "mU.d"
        private val moduleName = if (modern) "CS.I" else "xS.G"
        private val itemName = if (modern) "CS.J" else "xS.H"
        // 9170300 keeps rU.d byte for byte and only re-ranks its subclasses: a0→X, F→D, T→P, opus.t→p.
        private val homeLeaf = if (build300) "X" else if (profile917) "a0" else "U"
        private val personalLeaf = if (build300) "D" else if (profile917) "F" else "I"
        private val separatorLeaf = if (build300) "P" else if (profile917) "T" else "W"
        private val opusName = "com.bilibili.bplus.followinglist.opus." +
            if (build300) "p" else if (profile917) "t" else "k"
        private val definitions = mapOf(
            painterName to follow916.Painter::class.java,
            moduleName to tS.G::class.java,
            itemName to tS.H::class.java,
            "xS.TestModule" to follow916.ConcreteModule::class.java,
            "com.bilibili.bplus.followinglist.home.$homeLeaf" to com.bilibili.bplus.followinglist.home.Q::class.java,
            "com.bilibili.bplus.followinglist.quick.consume.$personalLeaf" to followtest.roles.PersonalDecoration::class.java,
            "com.bilibili.bplus.followinglist.quick.consume.$separatorLeaf" to followtest.roles.PersonalSeparator::class.java,
            opusName to follow916.Opus::class.java
        )
        // The names the previous build used still exist in 9170300, now as unrelated classes.
        private val reassigned = if (build300) listOf(
            "com.bilibili.bplus.followinglist.home.U", "com.bilibili.bplus.followinglist.home.T",
            "com.bilibili.bplus.followinglist.quick.consume.F", "com.bilibili.bplus.followinglist.quick.consume.I",
            "com.bilibili.bplus.followinglist.quick.consume.T", "com.bilibili.bplus.followinglist.opus.t",
            "com.bilibili.bplus.followinglist.opus.k"
        ).associateWith { followtest.roles.Unrelated::class.java } else emptyMap()
        private val names = definitions.map { (name, template) ->
            template.name.replace('.', '/') to name.replace('.', '/')
        }.toMap() + ("iU/d" to painterName.replace('.', '/'))

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (ambientNewSeparator && !modern && name == "com.bilibili.bplus.followinglist.quick.consume.T")
                return@synchronized followtest.roles.PersonalSeparator::class.java
            if (name in setOf("gv1.c", "iU.d") || modern && name == "mU.d" || hideOpus && name == opusName)
                throw ClassNotFoundException(name)
            val template = definitions[name] ?: reassigned[name] ?: return@synchronized super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: run {
                val bytes = rewrite(template, names + (template.name.replace('.', '/') to name.replace('.', '/')),
                    if (name == moduleName) localField else null)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(type)
            type
        }

        fun item(): Any = loadClass(itemName).getConstructor().newInstance()
        fun module(item: Any): Any = loadClass("xS.TestModule").getConstructor(loadClass(itemName)).newInstance(item)

        private fun rewrite(template: Class<*>, names: Map<String, String>, field: String?): ByteArray {
            val bytes = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                .use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val buffer = ByteArrayOutputStream()
            val output = DataOutputStream(buffer)
            output.writeInt(input.readInt()); output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort(); output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte(); output.writeByte(tag)
                if (tag == 1) {
                    val original = input.readUTF()
                    val value = names.entries.fold(original) { text, (old, new) -> text.replace(old, new) }
                    output.writeUTF(if (field != null && value == "a") field else value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("unsupported classfile tag $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes()); output.flush()
            return buffer.toByteArray()
        }
    }

    @Test fun `new painter home personal and separator resolve with the b local owner`() {
        val loader = Host()
        val points = requireNotNull(FollowFeedAdapter.direct(loader))
        assertEquals("mU.d", points.painterOwnerClass)
        assertEquals("com.bilibili.bplus.followinglist.home.U", points.homeDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.I", points.personalDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.W", points.personalSeparatorClass)
        val resolved = requireNotNull(FollowFeedAdapter.resolve(loader, points))
        assertEquals(points, resolved.points)
        assertTrue(resolved.host.embeddedVideoSupported)
    }

    @Test fun `local quote test does not mistake the top owner or boolean O for the local item`() {
        val loader = Host()
        val resolved = requireNotNull(FollowFeedAdapter.resolve(loader, FollowFeedAdapter.direct(loader)))
        val item = loader.item()
        val module = loader.module(item)
        assertEquals(false, resolved.host.isQuotedModule(module))
        item.javaClass.getField("j").set(item, loader.item())
        assertEquals(true, resolved.host.isQuotedModule(module))
        assertNull(resolved.host.isQuotedModule(Any()))
    }

    @Test fun `static opus color accepts ordinary modules and preserves quoted or unknown modules`() {
        val loader = Host()
        val access = requireNotNull(HostOpusBackgroundAccess.resolve(loader))
        assertTrue(Modifier.isStatic(access.color.modifiers))
        val item = loader.item()
        val module = loader.module(item)
        assertTrue(access.accepts(null, module))
        item.javaClass.getField("j").set(item, loader.item())
        assertFalse(access.accepts(null, module))
        assertFalse(access.accepts(null, Any()))
        assertFalse(access.accepts(null, null))
    }

    @Test fun `unknown local ownership and absent opus painter remain unavailable`() {
        assertNull(FollowFeedAdapter.direct(Host(localField = "unknown")))
        assertNull(HostOpusBackgroundAccess.resolve(Host(localField = "unknown")))
        assertNull(HostOpusBackgroundAccess.resolve(Host(hideOpus = true)))
    }

    @Test fun `917 painter home personal separator and opus preserve the same ownership contract`() {
        val loader = Host(profile917 = true)
        val points = requireNotNull(FollowFeedAdapter.direct(loader))
        assertEquals("rU.d", points.painterOwnerClass)
        assertEquals("CS.I", points.moduleClass)
        assertEquals("CS.J", points.itemClass)
        assertEquals("com.bilibili.bplus.followinglist.home.a0", points.homeDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.F", points.personalDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.T", points.personalSeparatorClass)
        val host = requireNotNull(FollowFeedAdapter.resolve(loader, points)).host
        val item = loader.item()
        val module = loader.module(item)
        assertEquals(false, host.isQuotedModule(module))
        item.javaClass.getField("j").set(item, loader.item())
        assertEquals(true, host.isQuotedModule(module))
        val opus = requireNotNull(HostOpusBackgroundAccess.resolve(loader))
        assertFalse(opus.accepts(null, module))
    }

    @Test fun `917 direct resolution replaces old names without mixing the two generations`() {
        val old = requireNotNull(FollowFeedAdapter.direct(Host()))
        val loader = Host(profile917 = true)
        val refreshed = requireNotNull(FollowFeedAdapter.locate(loader, null, true, old, planned = false))
        assertEquals("rU.d", refreshed.painterOwnerClass)
        assertEquals("com.bilibili.bplus.followinglist.home.a0", refreshed.homeDecorationClass)
        assertNull(FollowFeedAdapter.direct(Host(localField = "unknown", profile917 = true)))
    }

    @Test fun `same named T decoration cannot change an older painter generation`() {
        val points = requireNotNull(FollowFeedAdapter.direct(Host(ambientNewSeparator = true)))
        assertEquals("mU.d", points.painterOwnerClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.W", points.personalSeparatorClass)
    }

    @Test fun `9170300 re-ranked home personal separator and opus decorations resolve under the unchanged painter`() {
        val loader = Host(localField = "a", build300 = true)
        val points = requireNotNull(FollowFeedAdapter.direct(loader))
        assertEquals("rU.d", points.painterOwnerClass)
        assertEquals("CS.I", points.moduleClass)
        assertEquals("CS.J", points.itemClass)
        assertEquals("com.bilibili.bplus.followinglist.home.X", points.homeDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.D", points.personalDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.P", points.personalSeparatorClass)
        val resolved = requireNotNull(FollowFeedAdapter.resolve(loader, points))
        assertEquals(points, resolved.points)
        assertTrue(resolved.host.embeddedVideoSupported)
        val item = loader.item()
        val module = loader.module(item)
        assertEquals(false, resolved.host.isQuotedModule(module))
        item.javaClass.getField("j").set(item, loader.item())
        assertEquals(true, resolved.host.isQuotedModule(module))
    }

    @Test fun `9170300 opus painter is p and the reused t and k names are ignored`() {
        val loader = Host(localField = "a", build300 = true)
        val access = requireNotNull(HostOpusBackgroundAccess.resolve(loader))
        assertTrue(Modifier.isStatic(access.color.modifiers))
        val item = loader.item()
        val module = loader.module(item)
        assertTrue(access.accepts(null, module))
        item.javaClass.getField("j").set(item, loader.item())
        assertFalse(access.accepts(null, module))
        assertNull(HostOpusBackgroundAccess.resolve(Host(localField = "a", build300 = true, hideOpus = true)))
    }

    @Test fun `9170300 cached 9170100 names are refreshed and the older build keeps its own names`() {
        val old = requireNotNull(FollowFeedAdapter.direct(Host(profile917 = true)))
        assertEquals("com.bilibili.bplus.followinglist.home.a0", old.homeDecorationClass)
        val refreshed = requireNotNull(FollowFeedAdapter.locate(Host(localField = "a", build300 = true), null, true, old, planned = false))
        assertEquals("com.bilibili.bplus.followinglist.home.X", refreshed.homeDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.D", refreshed.personalDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.P", refreshed.personalSeparatorClass)
        // The stale 9170100 cache must not validate on 9170300, where a0 no longer exists.
        assertNull(FollowFeedAdapter.resolve(Host(localField = "a", build300 = true), old))
        // And the 9170300 cache must not validate on 9170100, where X is not a rU.d subclass.
        assertNull(FollowFeedAdapter.resolve(Host(profile917 = true), refreshed))
    }

    @Test fun `9170300 unknown local ownership stays unavailable`() {
        assertNull(FollowFeedAdapter.direct(Host(localField = "unknown", build300 = true)))
        assertNull(HostOpusBackgroundAccess.resolve(Host(localField = "unknown", build300 = true)))
    }
}
