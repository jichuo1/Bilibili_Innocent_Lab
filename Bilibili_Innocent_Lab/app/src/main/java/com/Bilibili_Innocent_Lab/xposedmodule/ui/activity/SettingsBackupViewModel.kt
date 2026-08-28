package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportPlan
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsApplyResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.YukiModuleSettingsStore
import java.util.concurrent.Executors

/** 已确认导入的进程内状态；配置变更不会中断正在进行的设置提交。 */
internal sealed interface SettingsImportApplyState {
    data object Idle : SettingsImportApplyState
    data class Running(val plan: ImportPlan) : SettingsImportApplyState
    data class Finished(
        val plan: ImportPlan,
        val result: SettingsApplyResult
    ) : SettingsImportApplyState

    data class Failed(
        val plan: ImportPlan,
        val throwable: Throwable
    ) : SettingsImportApplyState
}

internal class SettingsBackupViewModel : ViewModel() {

    val applyState = MutableLiveData<SettingsImportApplyState>(SettingsImportApplyState.Idle)

    var lastImportUri: Uri? = null
    var previewPlan: ImportPlan? = null

    private val applyWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "settings-import-apply").apply { isDaemon = true }
    }

    fun selectImport(uri: Uri) {
        if (applyState.value is SettingsImportApplyState.Running) return
        lastImportUri = uri
        previewPlan = null
        applyState.value = SettingsImportApplyState.Idle
    }

    fun setPreview(plan: ImportPlan) {
        if (applyState.value is SettingsImportApplyState.Running) return
        previewPlan = plan
        applyState.value = SettingsImportApplyState.Idle
    }

    fun clearSelection() {
        if (applyState.value is SettingsImportApplyState.Running) return
        lastImportUri = null
        previewPlan = null
        applyState.value = SettingsImportApplyState.Idle
    }

    fun apply(
        context: Context,
        store: YukiModuleSettingsStore,
        plan: ImportPlan
    ) {
        if (applyState.value is SettingsImportApplyState.Running) return
        previewPlan = plan
        applyState.value = SettingsImportApplyState.Running(plan)
        applyWorker.submit {
            try {
                val result = SettingsImportApplier(context.applicationContext, store).apply(plan)
                applyState.postValue(SettingsImportApplyState.Finished(plan, result))
            } catch (exception: Exception) {
                applyState.postValue(SettingsImportApplyState.Failed(plan, exception))
            }
        }
    }

    override fun onCleared() {
        applyWorker.shutdownNow()
        super.onCleared()
    }
}
