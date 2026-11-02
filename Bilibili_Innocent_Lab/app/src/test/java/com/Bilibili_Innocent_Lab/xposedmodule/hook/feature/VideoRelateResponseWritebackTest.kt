package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.google.protobuf.Internal
import org.junit.Assert.*
import org.junit.Test

class VideoRelateResponseWritebackTest {
    @Test fun protobufWritebackPreservesTypedImmutableSourceAndInvalidatesMessageCaches() {
        val reply = Reply()
        val original = reply.items
        assertEquals(8, reply.serializedSize())
        val oldHash = reply.hashCode()
        assertTrue(write(reply, listOf(original[1])))
        assertNotSame(original, reply.items)
        assertEquals(listOf("drop", "kept"), original)
        assertEquals(listOf("kept"), reply.items)
        assertFalse(original.isModifiable)
        assertFalse(reply.items.isModifiable)
        assertEquals(4, reply.serializedSize())
        assertEquals(listOf("kept").hashCode(), reply.hashCode())
        assertNotEquals(oldHash, reply.hashCode())
        assertTrue(runCatching { reply.items.clear() }.isFailure)
    }

    @Test fun removingEveryItemStillWritesACompatibleImmutableEmptyList() {
        val reply = Reply()
        val original = reply.items
        assertEquals(8, reply.serializedSize())
        assertTrue(write(reply, emptyList<String>()))
        assertTrue(reply.items.isEmpty())
        assertFalse(reply.items.isModifiable)
        assertEquals(0, reply.serializedSize())
        assertEquals(emptyList<String>().hashCode(), reply.hashCode())
        assertEquals(listOf("drop", "kept"), original)
    }

    @Test fun unknownMessageShapeAndAliasedCopyDoNotMutateTheOriginal() {
        val unknown = UnknownReply()
        val originalUnknown = unknown.items
        assertFalse(write(unknown, emptyList<String>()))
        assertSame(originalUnknown, unknown.items)
        val aliased = Reply(aliasedCopy = true)
        val originalAliased = aliased.items
        assertEquals(8, aliased.serializedSize())
        assertFalse(write(aliased, emptyList<String>()))
        assertSame(originalAliased, aliased.items)
        assertEquals(listOf("drop", "kept"), aliased.items)
        assertEquals(8, aliased.serializedSize())
    }

    @Test fun failedCacheResetRollsBackTheOriginalFieldAndCachedValues() {
        val reply = FailingReply()
        val original = reply.items
        val originalSize = reply.serializedSize()
        val originalHash = reply.hashCode()
        assertFalse(write(reply, emptyList<String>()))
        assertSame(original, reply.items)
        assertEquals(originalSize, reply.serializedSize())
        assertEquals(originalHash, reply.hashCode())
    }

    private fun write(reply: Any, items: List<*>) = writeBackVideoRelateItems(reply,
        requireNotNull(Lookup.fieldOrNull(reply.javaClass, "items", includeSuperclasses = true)), items)

    private open class Caches {
        @JvmField var memoizedHashCode = 0
        private var serializedSizeCache = -1
        fun getMemoizedSerializedSize(): Int = serializedSizeCache
        open fun setMemoizedSerializedSize(value: Int) { serializedSizeCache = value }
    }

    private open class Reply(aliasedCopy: Boolean = false) : Caches() {
        @JvmField var items: Internal.ProtobufList<String> = ProtoItems(listOf("drop", "kept"), aliasedCopy)
        fun serializedSize(): Int {
            if (getMemoizedSerializedSize() == -1) setMemoizedSerializedSize(items.sumOf(String::length))
            return getMemoizedSerializedSize()
        }
        override fun hashCode(): Int {
            if (memoizedHashCode == 0) memoizedHashCode = items.hashCode()
            return memoizedHashCode
        }
    }

    private class FailingReply : Reply() {
        override fun setMemoizedSerializedSize(value: Int) {
            if (value == -1) error("fixture cache reset failure")
            super.setMemoizedSerializedSize(value)
        }
    }

    private class UnknownReply {
        @JvmField var items: Internal.ProtobufList<String> = ProtoItems(listOf("drop", "kept"))
    }

    private class ProtoItems(initial: List<String>, private val aliasedCopy: Boolean = false,
        private var writable: Boolean = false) : AbstractMutableList<String>(), Internal.ProtobufList<String> {
        private val values = initial.toMutableList()
        override val size get() = values.size
        override fun get(index: Int) = values[index]
        override fun add(index: Int, element: String) { check(writable); values.add(index, element) }
        override fun removeAt(index: Int): String { check(writable); return values.removeAt(index) }
        override fun set(index: Int, element: String): String { check(writable); return values.set(index, element) }
        override fun isModifiable() = writable
        override fun makeImmutable() { writable = false }
        override fun mutableCopyWithCapacity(capacity: Int): Internal.ProtobufList<String> {
            require(capacity >= size)
            return if (aliasedCopy) this else ProtoItems(values, writable = true)
        }
    }
}
