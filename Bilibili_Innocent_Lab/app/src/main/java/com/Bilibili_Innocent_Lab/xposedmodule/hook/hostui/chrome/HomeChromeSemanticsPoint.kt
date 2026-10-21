package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.json.JSONObject

/** Exact canonical property field, tied to the existing host/version/DEX cache fingerprint. */
data class HomeChromeSemanticsPoint(val holderClass: String, val keyField: String) {
    fun isStructurallyValid(): Boolean = holderClass.length in 3..512 && keyField.length in 1..128 &&
        CLASS_NAME.matches(holderClass) && FIELD_NAME.matches(keyField) &&
        !holderClass.startsWith("java.") && !holderClass.startsWith("kotlin.") && !holderClass.startsWith("android.")

    fun toJson(): JSONObject = JSONObject().put("holder", holderClass).put("key", keyField)

    companion object {
        private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        private val FIELD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        fun fromJson(value: JSONObject): HomeChromeSemanticsPoint? = runCatching {
            HomeChromeSemanticsPoint(value.getString("holder"), value.getString("key")).takeIf { it.isStructurallyValid() }
        }.getOrNull()
    }
}
