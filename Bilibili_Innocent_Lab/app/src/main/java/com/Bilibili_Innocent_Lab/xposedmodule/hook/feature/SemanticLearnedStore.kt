package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.LatestValuePublisher
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/** Shared network workers supply one consumer; updates during a save are drained afterwards. */
internal class SemanticLearnedStore(
    schedule: (Runnable) -> Boolean,
    snapshot: () -> String,
    replace: (File, String) -> Boolean = { file, text -> replaceAtomically(file, text) }
) {
    private val lock = Any()
    private enum class State { NOT_LOADED, LOADING, READY }
    private var target: File? = null
    private var state = State.NOT_LOADED
    private var dirty = false
    private val revision = AtomicLong()
    private val publisher = LatestValuePublisher<File, Long>(
        schedule = { task -> check(schedule(Runnable { task() })) { "semantic save queue full" } },
        publish = { file, _ -> replace(file, snapshot()) }
    )

    /** Read/merge once before permitting writes. Failed reads retry only the first attached file. */
    fun attach(file: File, load: (File) -> Boolean): Boolean {
        val absolute = file.absoluteFile.normalize()
        synchronized(lock) {
            if (target == null) target = absolute
            if (target != absolute) return false
            when (state) {
                State.READY -> {
                    if (dirty) saveReady()
                    return true
                }
                State.LOADING -> return false
                State.NOT_LOADED -> state = State.LOADING
            }
        }
        val success = runCatching { load(absolute) }.getOrDefault(false)
        return synchronized(lock) {
            state = if (success) State.READY else State.NOT_LOADED
            if (success && dirty) saveReady()
            success
        }
    }

    /** Updates before/during initialization are coalesced and saved after the merge. */
    fun save(): Boolean = synchronized(lock) {
        dirty = true
        if (state != State.READY) true else saveReady()
    }

    /** Failure is unacknowledged: retry once now, then retain it for the next update. */
    private fun saveReady(): Boolean {
        val accepted = publisher.submit(checkNotNull(target), revision.incrementAndGet())
        dirty = !accepted
        return accepted
    }

    internal companion object {
        fun replaceAtomically(
            file: File,
            text: String,
            move: (Path, Path) -> Unit = { source, target ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            }
        ): Boolean {
            var temporary: File? = null
            return try {
                val parent = file.absoluteFile.parentFile ?: return false
                check(parent.isDirectory || parent.mkdirs())
                val output = File.createTempFile(file.name + ".", ".tmp", parent)
                temporary = output
                output.writeText(text, StandardCharsets.UTF_8)
                // Same-directory replacement preserves the previous file if the move fails.
                move(output.toPath(), file.toPath())
                true
            } catch (_: Exception) {
                false
            } finally {
                temporary?.delete()
            }
        }
    }
}
