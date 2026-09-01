package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** GitHub Release Markdown 在进入 Android 排版层前的有界、纯文本预处理。 */
internal object ReleaseNotesSourcePolicy {
    internal const val MAX_MARKDOWN_LENGTH = 32 * 1024

    data class Result(
        val markdown: String,
        val truncated: Boolean
    )

    fun prepare(
        raw: String,
        maxLength: Int = MAX_MARKDOWN_LENGTH
    ): Result {
        require(maxLength >= MIN_LIMIT_LENGTH)
        val normalized = stripUnsafeControls(
            raw.replace("\r\n", "\n").replace('\r', '\n')
        ).trim()
        if (normalized.length <= maxLength) return Result(normalized, truncated = false)

        var end = maxLength
        if (end < normalized.length &&
            Character.isHighSurrogate(normalized[end - 1]) &&
            Character.isLowSurrogate(normalized[end])
        ) {
            end -= 1
        }
        val preferredFloor = maxLength * 3 / 4
        val paragraphBoundary = normalized.lastIndexOf("\n\n", startIndex = end - 1)
        val lineBoundary = normalized.lastIndexOf('\n', startIndex = end - 1)
        end = when {
            paragraphBoundary >= preferredFloor -> paragraphBoundary
            lineBoundary >= preferredFloor -> lineBoundary
            else -> end
        }

        val clipped = normalized.substring(0, end).trimEnd()
        val closingFence = findOpenFenceClosingMarker(clipped)
        return Result(
            markdown = buildString(clipped.length + (closingFence?.length ?: 0) + 1) {
                append(clipped)
                if (closingFence != null) append('\n').append(closingFence)
            },
            truncated = true
        )
    }

    private fun stripUnsafeControls(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    val next = value.getOrNull(index + 1)
                    if (next != null && Character.isLowSurrogate(next)) {
                        append(current).append(next)
                        index += 2
                        continue
                    }
                }
                Character.isLowSurrogate(current) -> Unit
                current == '\n' || current == '\t' || !Character.isISOControl(current) -> append(current)
            }
            index += 1
        }
    }

    /** 若安全截断发生在围栏代码块内部，补齐闭合标记，避免余下说明全部被当成代码。 */
    private fun findOpenFenceClosingMarker(markdown: String): String? {
        var openFence: Fence? = null
        markdown.lineSequence().forEach { line ->
            val marker = fenceAtLineStart(line) ?: return@forEach
            val current = openFence
            if (current == null) {
                openFence = marker
            } else if (current.character == marker.character &&
                marker.length >= current.length && !marker.hasTrailingContent
            ) {
                openFence = null
            }
        }
        return openFence?.let { fence -> fence.character.toString().repeat(fence.length) }
    }

    private fun fenceAtLineStart(line: String): Fence? {
        val trimmed = line.dropWhile { it == ' ' || it == '\t' }
        val character = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = trimmed.takeWhile { it == character }.length
        return if (length >= 3) {
            Fence(character, length, trimmed.drop(length).isNotBlank())
        } else {
            null
        }
    }

    private data class Fence(
        val character: Char,
        val length: Int,
        val hasTrailingContent: Boolean
    )

    private const val MIN_LIMIT_LENGTH = 64
}
