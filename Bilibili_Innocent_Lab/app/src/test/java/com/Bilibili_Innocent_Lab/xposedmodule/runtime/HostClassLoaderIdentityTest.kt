package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class HostClassLoaderIdentityTest {
    @Test
    fun `same named classes resolve and match only inside their host loader`() {
        val moduleType = Fixture::class.java
        val name = moduleType.name
        val bytes = requireNotNull(moduleType.getResourceAsStream("/" + name.replace('.', '/') + ".class"))
            .use { it.readBytes() }
        fun hostLoader() = object : ClassLoader(moduleType.classLoader) {
            override fun loadClass(requested: String, resolve: Boolean): Class<*> {
                if (requested != name) return super.loadClass(requested, resolve)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size).also {
                    if (resolve) resolveClass(it)
                }
            }
        }
        val firstLoader = hostLoader()
        val secondLoader = hostLoader()
        val first = requireNotNull(KavaMemberLookup.classOrNull(firstLoader, name))
        val second = requireNotNull(KavaMemberLookup.classOrNull(secondLoader, name))
        val hostView = first.getDeclaredConstructor().newInstance()

        assertNotSame(moduleType, first)
        assertNotSame(first, second)
        assertTrue(first.isInstance(hostView))
        assertFalse(moduleType.isInstance(hostView))
        assertFalse(second.isInstance(hostView))
        assertSame(first, KavaMemberLookup.classOrNull(firstLoader, name))
    }

    class Fixture
}
