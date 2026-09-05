package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** Theseus 活动模型的构造边界。快定位只读成员元数据，不初始化描述器或读取属性表。 */
internal object PgcAutoActivityPopupLocator {
    const val MODEL_CLASS = "com.bilibili.ship.theseus.ogv.activity.OgvActivityVo"
    private const val POPUP_CLASS = "com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"
    private const val DESCRIPTOR_CLASS = "${MODEL_CLASS}_JsonDescriptor"
    private const val PROPERTY_CLASS = "com.bilibili.bson.common.PojoPropertyDescriptor"
    private const val BASE_CLASS = "com.bilibili.bson.common.PojoClassDescriptor"
    private const val HALF_KEY = "play_half_container"

    internal data class Property(val name: String, val type: Type, val nonNull: Boolean)

    internal data class RuntimePoint(
        val construct: Method,
        val modelType: Class<*>,
        val popupType: Class<*>,
        val popupField: Field,
        val parameterCount: Int,
        val popupIndex: Int
    )

    private data class Metadata(
        val runtime: RuntimePoint,
        val constructorTypes: List<Class<*>>,
        val properties: Field,
        val propertyType: Class<*>,
        val keyGetter: Method,
        val typeGetter: Method,
        val nonNullGetter: Method
    ) {
        fun toPoints() = VersionAdapter.PgcAutoActivityPopupPoints(
            construct = VersionAdapter.HookPoint(
                runtime.construct.declaringClass.name,
                runtime.construct.name,
                runtime.construct.parameterTypes.map { it.name }
            ),
            constructorParameters = constructorTypes.map { it.name },
            popupIndex = runtime.popupIndex,
            propertiesField = properties.name
        )
    }

    fun locate(loader: ClassLoader): VersionAdapter.PgcAutoActivityPopupPoints? =
        runCatching { metadata(loader)?.toPoints() }.getOrNull()

    /**
     * 仅在已启用功能的安装期读取一次静态描述器表，并用当前 ClassLoader 重验缓存。
     * 不创建活动/半屏对象，不依赖混淆 getter，不在 Hook 回调里做成员查找。
     */
    fun resolveRuntime(
        loader: ClassLoader,
        expected: VersionAdapter.PgcAutoActivityPopupPoints
    ): RuntimePoint? = runCatching {
        val metadata = metadata(loader) ?: return null
        if (metadata.toPoints() != expected) return null
        val properties = metadata.properties.get(null) as? Array<*> ?: return null
        if (properties.size != metadata.constructorTypes.size) return null
        val descriptions = properties.map { value ->
            if (!metadata.propertyType.isInstance(value)) return null
            Property(
                metadata.keyGetter.invoke(value) as? String ?: return null,
                metadata.typeGetter.invoke(value) as? Type ?: return null,
                metadata.nonNullGetter.invoke(value) as? Boolean ?: return null
            )
        }
        if (!matchesNullableHalf(
                metadata.constructorTypes, metadata.runtime.popupType, descriptions
            )
        ) return null
        metadata.runtime
    }.getOrNull()

    /** 属性顺序、唯一语义键、唯一参数类型和可空性必须同时吻合，不能仅相信历史槽位。 */
    internal fun matchesNullableHalf(
        parameters: List<Class<*>>,
        popupType: Class<*>,
        properties: List<Property>
    ): Boolean {
        if (parameters.size != properties.size) return false
        val index = uniquePopupIndex(parameters, popupType) ?: return false
        if (properties.count { it.name == HALF_KEY } != 1) return false
        val half = properties[index]
        if (half.name != HALF_KEY || half.type != popupType || half.nonNull) return false
        return properties.indices.all { position ->
            val type = properties[position].type
            val rawType = if (type is ParameterizedType) type.rawType else type
            rawType == parameters[position]
        }
    }

    internal fun uniquePopupIndex(parameters: List<Class<*>>, popupType: Class<*>): Int? =
        parameters.indices.filter { parameters[it] == popupType }.singleOrNull()

    private fun metadata(loader: ClassLoader): Metadata? {
        val model = KavaMemberLookup.classOrNull(loader, MODEL_CLASS) ?: return null
        val popup = KavaMemberLookup.classOrNull(loader, POPUP_CLASS) ?: return null
        val descriptor = KavaMemberLookup.classOrNull(loader, DESCRIPTOR_CLASS) ?: return null
        val base = KavaMemberLookup.classOrNull(loader, BASE_CLASS) ?: return null
        val property = KavaMemberLookup.classOrNull(loader, PROPERTY_CLASS) ?: return null
        if (descriptor.superclass != base) return null
        val constructor = KavaMemberLookup.declaredConstructors(model) {
            !it.isSynthetic && uniquePopupIndex(it.parameterTypes.toList(), popup) != null
        }.singleOrNull() ?: return null
        val parameters = constructor.parameterTypes.toList()
        val index = uniquePopupIndex(parameters, popup) ?: return null
        val construct = KavaMemberLookup.declaredMethods(descriptor, makeAccessible = true) {
            it.name == "constructWith" && !it.isStatic && !it.isAbstract &&
                it.returnType == classOf<Any>() &&
                it.parameterTypes.contentEquals(arrayOf(classOf<Array<Any?>>()))
        }.singleOrNull() ?: return null
        val properties = KavaMemberLookup.fields(
            descriptor, includeSuperclasses = false, makeAccessible = true
        ) { it.isStatic && it.type.isArray && it.type.componentType == property }
            .singleOrNull() ?: return null
        val popupField = KavaMemberLookup.fields(
            model, includeSuperclasses = false, makeAccessible = true
        ) { !it.isStatic && it.type == popup }.singleOrNull() ?: return null
        fun getter(name: String, result: Class<*>): Method? =
            KavaMemberLookup.declaredMethods(property, makeAccessible = true) {
                it.name == name && !it.isStatic && it.parameterCount == 0 && it.returnType == result
            }.singleOrNull()
        return Metadata(
            RuntimePoint(construct, model, popup, popupField, parameters.size, index),
            parameters, properties, property,
            getter("getKeyName", classOf<String>()) ?: return null,
            getter("getType", classOf<Type>()) ?: return null,
            getter("getNonNull", classOf<Boolean>()) ?: return null
        )
    }
}
