package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 只转换活动模型的可空半屏槽位；原数组和其余对象的身份保持不变。 */
internal object PgcAutoActivityPopupPolicy {
    sealed interface Decision {
        data object Absent : Decision
        data object InvalidShape : Decision
        data object UnexpectedType : Decision
        class Filtered(val values: Array<Any?>) : Decision
    }

    fun filter(
        input: Any?,
        parameterCount: Int,
        popupIndex: Int,
        popupType: Class<*>
    ): Decision {
        val values = input as? Array<*> ?: return Decision.InvalidShape
        if (values.size != parameterCount || popupIndex !in values.indices) {
            return Decision.InvalidShape
        }
        val popup = values[popupIndex] ?: return Decision.Absent
        if (!popupType.isInstance(popup)) return Decision.UnexpectedType
        @Suppress("UNCHECKED_CAST")
        val copy = values.copyOf() as Array<Any?>
        copy[popupIndex] = null
        return Decision.Filtered(copy)
    }
}
