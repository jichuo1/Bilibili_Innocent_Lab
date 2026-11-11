package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import android.content.Context
import android.os.ParcelFileDescriptor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameModelSpec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.PulledRemoteFiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.RemoteFileService
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.asRemoteFileService
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.FrameworkServiceCall
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isFrameworkServiceDeath
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块设置中的「下载插帧模型」：仅在用户主动点击后执行，从固定提交的官方仓库下载 RIFE v4.6 两个文件，
 * 逐文件校验大小与 SHA-256，全部通过后写入 LSPosed 远程文件供宿主进程读取。模块本身不分发权重。
 * 必须在后台线程调用；进度通过回调报告，失败不会留下半个模型。
 */
internal object FrameModelDownloader {
    sealed interface Progress {
        data class Downloading(val part: String, val downloaded: Long, val total: Long) : Progress
        data object Publishing : Progress
        data object Done : Progress
        data class Failed(val reason: String, val state: State? = null) : Progress
    }

    enum class State { MISSING, READY, INVALID, FRAMEWORK_UNAVAILABLE, FRAMEWORK_UNSUPPORTED, FRAMEWORK_PENDING, REMOTE_FAILED, LOCAL_FAILED }
    private val files = FrameModelFiles()
    private val executor = com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire.executor("bil-frame-model")

    fun execute(work: () -> Unit): Boolean = runCatching { executor.execute(work) }.isSuccess

    private fun failureState(reason: FrameworkServiceCall.Reason): State = when (reason) {
        FrameworkServiceCall.Reason.NOT_CONNECTED, FrameworkServiceCall.Reason.SERVICE_DIED -> State.FRAMEWORK_UNAVAILABLE
        FrameworkServiceCall.Reason.CONNECTION_CHANGED, FrameworkServiceCall.Reason.METADATA_UNAVAILABLE -> State.FRAMEWORK_PENDING
        FrameworkServiceCall.Reason.UNSUPPORTED -> State.FRAMEWORK_UNSUPPORTED
        FrameworkServiceCall.Reason.OPERATION_FAILED -> State.REMOTE_FAILED
    }

    /**
     * 按当前选中的通道执行一次文件操作。NPatch 通道已选时只走 Manager 认证的拉取通道：推送来的
     * `XposedService` 不校验来源，不能拿来写模型文件；嵌入模式没有设置 App 可达的存储。
     * 连接发生在调用线程上（有界，最多数秒），必须在后台线程调用。
     */
    private fun <T> withFiles(context: Context, action: (RemoteFileService) -> T): FrameworkServiceCall<T> {
        val framework = RemoteHookConfigStore.status()
        val route = FrameFileRoute.select(
            noRootSelected = NoRootSupportStore.isDesiredEnabled(context),
            mode = NPatchRuntimeProfiles.read(context).mode,
            standardFrameworkConnected = framework.capable && !isNpatchConfigChannel(framework)
        )
        return when (route) {
            FrameFileRoute.PUSHED_SERVICE ->
                RemoteHookConfigStore.withServiceResult { action(it.asRemoteFileService()) }
            FrameFileRoute.UNSUPPORTED_EMBEDDED ->
                FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.UNSUPPORTED)
            FrameFileRoute.PULLED_NPATCH -> when (val pulled = NPatchRemoteGateway.connectRemoteFiles(context)) {
                is PulledRemoteFiles.Unavailable ->
                    FrameworkServiceCall.Failure(FrameFileRoute.failureReason(pulled.result))
                is PulledRemoteFiles.Ready -> try {
                    FrameworkServiceCall.Success(action(pulled.files), connectionId = 0L)
                } catch (failure: Exception) {
                    FrameworkServiceCall.Failure(
                        if (isFrameworkServiceDeath(failure)) FrameworkServiceCall.Reason.SERVICE_DIED
                        else FrameworkServiceCall.Reason.OPERATION_FAILED
                    )
                }
            }
        }
    }

    /** 检查远程文件是否齐全且校验通过（读两个文件约 10 MB，需在后台线程调用）。 */
    fun state(context: Context): State = when (val result = withFiles(context) { service ->
        files.inspect(service.listRemoteFiles().toSet()) { name ->
            ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name))
        }
    }) {
        is FrameworkServiceCall.Success -> when (result.value) {
            FrameModelFiles.State.MISSING -> State.MISSING
            FrameModelFiles.State.INVALID -> State.INVALID
            FrameModelFiles.State.READY -> State.READY
        }
        is FrameworkServiceCall.Failure -> failureState(result.reason)
    }

    /** Reconnection may publish a prior explicit download; it never starts a network request. */
    fun refresh(context: Context): State {
        val current = state(context)
        if (current != State.MISSING && current != State.INVALID) return current
        val cached = files.cached(cacheDirectory(context)) ?: return current
        return when (val published = publish(context, cached)) {
            is FrameworkServiceCall.Success -> if (published.value) State.READY else State.INVALID
            is FrameworkServiceCall.Failure -> failureState(published.reason)
        }
    }

    private fun cacheDirectory(context: Context) = File(context.filesDir, "frame-model-download/${FrameModelSpec.NAME}")

    private fun publish(context: Context, downloaded: Map<FrameModelSpec.Part, File>): FrameworkServiceCall<Boolean> =
        withFiles(context) { service ->
            files.publish(downloaded, service.listRemoteFiles().toSet(),
                open = { name -> ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)) },
                write = { name, source ->
                    ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
                        output.channel.truncate(0)
                        source.inputStream().use { it.copyTo(output, 64 * 1024) }
                        output.fd.sync()
                    }
                })
        }

    fun download(context: Context, progress: (Progress) -> Unit): Boolean {
        val folder = cacheDirectory(context)
        try {
            val downloaded = files.prepare(folder) { part, target -> fetch(part, target, progress) }
                ?: run { progress(Progress.Failed("download")); return false }
            progress(Progress.Publishing)
            val published = publish(context, downloaded)
            return when (published) {
                is FrameworkServiceCall.Success -> if (published.value) { progress(Progress.Done); true }
                    else { progress(Progress.Failed("verify", State.INVALID)); false }
                is FrameworkServiceCall.Failure -> { progress(Progress.Failed("publication", failureState(published.reason))); false }
            }
        } catch (_: Exception) {
            progress(Progress.Failed("local_storage", State.LOCAL_FAILED))
            return false
        }
    }

    private fun fetch(part: FrameModelSpec.Part, target: File, progress: (Progress) -> Unit): Boolean {
        val connection = URL(part.sourceUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "BilibiliInnocentLab")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            var total = 0L
            connection.inputStream.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    FrameModelSpec.matches(part, input) { bytes, count ->
                        output.write(bytes, 0, count)
                        total += count
                        progress(Progress.Downloading(part.fileName, total, part.size.toLong()))
                    }
                }
            }.also { if (!it) target.delete() }
        } catch (failure: Exception) {
            target.delete()
            false
        } finally {
            connection.disconnect()
        }
    }
}
