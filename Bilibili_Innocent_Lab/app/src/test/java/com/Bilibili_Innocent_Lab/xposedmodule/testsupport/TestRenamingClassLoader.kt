package com.Bilibili_Innocent_Lab.xposedmodule.testsupport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 单元测试用轻量类加载器：从模板类字节码按常量池 UTF-8 替换重命名宿主类，
 * 并屏蔽指定类名/包前缀以模拟旧版本缺失或脱管环境。
 */
internal class TestRenamingClassLoader(
    parent: ClassLoader,
    private val templates: Map<String, Class<*>> = emptyMap(),
    private val replacements: Map<String, String> = emptyMap(),
    private val exactReplacements: Map<String, String> = emptyMap(),
    private val hiddenNames: Set<String> = emptySet(),
    private val hiddenPrefixes: Set<String> = emptySet(),
    private val rawClasses: Map<String, ByteArray> = emptyMap()
) : ClassLoader(parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        if (name in hiddenNames || hiddenPrefixes.any { name.startsWith(it) }) {
            throw ClassNotFoundException(name)
        }
        val rawBytes = rawClasses[name]
        if (rawBytes != null) {
            val loaded = findLoadedClass(name) ?: defineClass(name, rawBytes, 0, rawBytes.size)
            if (resolve) resolveClass(loaded)
            return@synchronized loaded
        }
        val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
        val loaded = findLoadedClass(name) ?: run {
            val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class")) {
                "Missing class resource for ${template.name}"
            }.use { it.readBytes() }
            val selfInternal = template.name.replace('.', '/')
            val targetInternal = name.replace('.', '/')
            val fullMap = replacements + (selfInternal to targetInternal)
            val rewritten = rewriteUtf8Constants(raw, fullMap, exactReplacements)
            defineClass(name, rewritten, 0, rewritten.size)
        }
        if (resolve) resolveClass(loaded)
        loaded
    }

    companion object {
        fun rewriteUtf8Constants(
            raw: ByteArray,
            replacements: Map<String, String>,
            exactReplacements: Map<String, String> = emptyMap()
        ): ByteArray {
            if (replacements.isEmpty() && exactReplacements.isEmpty()) return raw
            val pattern = replacements.keys
                .filter(String::isNotEmpty)
                .sortedByDescending { it.length }
                .takeIf { it.isNotEmpty() }
                ?.let { Regex(it.joinToString("|", transform = Regex::escape)) }
            val input = DataInputStream(ByteArrayInputStream(raw))
            val outBuffer = ByteArrayOutputStream(raw.size)
            val output = DataOutputStream(outBuffer)
            output.writeInt(input.readInt()) // magic
            output.writeShort(input.readUnsignedShort()) // minor
            output.writeShort(input.readUnsignedShort()) // major
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    val exact = exactReplacements[value]
                    if (exact != null) {
                        value = exact
                    } else if (pattern != null) {
                        value = pattern.replace(value) { match -> replacements.getValue(match.value) }
                    }
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported constant pool tag: $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes())
            output.flush()
            return outBuffer.toByteArray()
        }
    }
}
