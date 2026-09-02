package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 设置页启动兼容判断。
 *
 * 仅识别 Android 框架访问已失效 XML 文档的已知故障；调用方不得用它吞掉其他界面异常。
 */
internal object SettingsUiCompatibility {

    private const val INVALID_DOCUMENT_MESSAGE = "Null document"
    private const val XML_PARSER_CLASS = "android.content.res.XmlBlock\$Parser"
    private const val STYLE_ATTRIBUTE_METHOD = "getStyleAttribute"
    private const val MAX_CAUSE_DEPTH = 16

    /** 判断异常链中是否包含已确认的失效 [android.util.AttributeSet] 特征。 */
    fun isInvalidXmlDocumentFailure(throwable: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (
                current is NullPointerException &&
                current.message == INVALID_DOCUMENT_MESSAGE &&
                current.stackTrace.any { frame ->
                    frame.className == XML_PARSER_CLASS &&
                        frame.methodName == STYLE_ATTRIBUTE_METHOD
                }
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
