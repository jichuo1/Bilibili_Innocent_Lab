package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 一条云控键覆盖规则；只描述"读到这个 key 时该返回什么"，不碰宿主存储的云控数据本身。 */
internal sealed interface CloudKeyRule {
    /** 布尔读取（FF / 设备决策布尔位）固定返回该值；字符串读取不受影响。 */
    data class Bool(val value: Boolean) : CloudKeyRule

    /** 忽略云控下发的值，按宿主调用处自己写的默认值返回；布尔与字符串读取都适用。 */
    data object UseDefault : CloudKeyRule

    /** 字符串读取（`dd` / `config`）固定返回该文本；布尔读取不受影响。 */
    data class Text(val value: String) : CloudKeyRule
}

internal data class CloudKeyRuleSet(
    val rules: Map<String, CloudKeyRule>,
    /** 格式不合法、重复超限或超出条数上限而被丢弃的条目数。 */
    val rejected: Int,
    /** 因属于网络 / 登录 / 风控 / 初始化类 key 而被拒绝的条目数。 */
    val protectedRejected: Int
) {
    val isEmpty: Boolean get() = rules.isEmpty()

    /** 以 [overrides] 为准的并集：同一个键两边都有时，[overrides] 的规则生效。 */
    fun overlaidWith(overrides: CloudKeyRuleSet): CloudKeyRuleSet = CloudKeyRuleSet(
        LinkedHashMap(rules).also { it.putAll(overrides.rules) },
        rejected + overrides.rejected,
        protectedRejected + overrides.protectedRejected
    )

    companion object {
        val EMPTY = CloudKeyRuleSet(emptyMap(), 0, 0)
    }
}

/**
 * 「冻结云控键」规则文本的解析。
 *
 * 一行（或以 `;` 分隔）一条，`键名=取值`：
 * - `true` / `false`：布尔读取固定为该值；
 * - `default`：忽略云控值，回到宿主调用处的默认值；
 * - `s:文本`：字符串读取固定为该文本（文本里不能含 `;` 与换行）；
 * - 以 `#` 开头的行是注释，空行忽略；同一个键重复时后者生效。
 *
 * 键名按**整串精确**匹配且区分大小写（宿主的云控 key 区分大小写）；不做通配，避免一条
 * 写坏的规则同时改掉一整片云控位。网络、登录、风控与初始化类前缀（[PROTECTED_PREFIXES]）
 * 一律拒绝：冻结它们可能让宿主无法登录、联网或完成初始化，且用户没有办法判断后果。
 */
internal object CloudKeyFreezeRules {
    const val MAX_RULES = 64

    /** 扫描勾选面板最多固定的键数；手填规则另有 [MAX_RULES]，两边互不挤占。 */
    const val MAX_PICKED = 256
    const val MAX_KEY_LENGTH = 96
    const val MAX_TEXT_LENGTH = 512

    /** 小写前缀；与云控键名按小写比较，宁可多拒不可漏放。 */
    val PROTECTED_PREFIXES: List<String> = listOf(
        "infra.",
        "grpc.",
        "net.",
        "network.",
        "okhttp.",
        "blrouter.",
        "flowcontrol.",
        "login.",
        "passport.",
        "risk.",
        "security."
    )

    private val separators = Regex("[;；\\r\\n]+")
    private val keyPattern = Regex("[A-Za-z0-9_.\\-]+")

    fun isProtected(key: String): Boolean {
        val lowered = key.lowercase()
        return PROTECTED_PREFIXES.any(lowered::startsWith)
    }

    /** 键名是否能写成规则：字符集、长度合法，且不属于受保护前缀。扫描面板只列这样的键。 */
    fun isFreezable(key: String): Boolean =
        key.isNotEmpty() && key.length <= MAX_KEY_LENGTH && keyPattern.matches(key) && !isProtected(key)

    /**
     * 扫描勾选面板写出的固定值：每行 `键名=true|false`，按键名排序，同样的集合得到同样的文本。
     * 与手填规则同一套语法，所以备份、导入和解析都复用 [parse]。
     */
    fun formatPins(pins: Map<String, Boolean>): String =
        pins.entries.filter { isFreezable(it.key) }.sortedBy { it.key }
            .take(MAX_PICKED)
            .joinToString("\n") { "${it.key}=${it.value}" }

    /** 读回面板写出的固定值；只认布尔规则，其它写法（被手工改过的）忽略。 */
    fun parsePins(raw: String?): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        parse(raw, MAX_PICKED).rules.forEach { (key, rule) ->
            if (rule is CloudKeyRule.Bool) result[key] = rule.value
        }
        return result
    }

    fun parse(raw: String?, maxRules: Int = MAX_RULES): CloudKeyRuleSet {
        if (raw.isNullOrBlank()) return CloudKeyRuleSet.EMPTY
        val rules = LinkedHashMap<String, CloudKeyRule>()
        var rejected = 0
        var protectedRejected = 0
        for (segment in raw.split(separators)) {
            val entry = segment.trim()
            if (entry.isEmpty() || entry.startsWith('#')) continue
            val rule = parseEntry(entry)
            if (rule == null) {
                rejected += 1
                continue
            }
            val (key, value) = rule
            when {
                isProtected(key) -> protectedRejected += 1
                key !in rules && rules.size >= maxRules -> rejected += 1
                else -> {
                    // 重复键以后者为准；先移除再放入，保证迭代顺序与书写顺序一致。
                    rules.remove(key)
                    rules[key] = value
                }
            }
        }
        return CloudKeyRuleSet(rules, rejected, protectedRejected)
    }

    private fun parseEntry(entry: String): Pair<String, CloudKeyRule>? {
        val separator = entry.indexOf('=')
        if (separator <= 0) return null
        val key = entry.substring(0, separator).trim()
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH || !keyPattern.matches(key)) return null
        val value = entry.substring(separator + 1).trim()
        val rule = when {
            value.equals("true", ignoreCase = true) -> CloudKeyRule.Bool(true)
            value.equals("false", ignoreCase = true) -> CloudKeyRule.Bool(false)
            value.equals("default", ignoreCase = true) -> CloudKeyRule.UseDefault
            value.startsWith("s:") -> {
                val text = value.substring(2)
                if (text.length > MAX_TEXT_LENGTH) return null
                CloudKeyRule.Text(text)
            }
            else -> return null
        }
        return key to rule
    }
}

/** 一个被挂钩的读取入口返回什么类型的云控值，决定哪些规则对它有意义。 */
internal enum class CloudKeyReadKind {
    /** 返回 boolean 的入口（FF、设备决策布尔位）。 */
    BOOL,

    /** 返回 String 的入口（`dd` / `config` / `getConfig`）。 */
    TEXT,

    /** 泛型 `Contract.get(String, T)`：运行时看原结果与默认值的类型再决定。 */
    OBJECT
}

/**
 * 把一条规则落到一次读取上，纯函数、无分配（装箱值走缓存）。
 *
 * 规则只在**类型对得上**时生效：`true` / `false` 不会改写字符串读取，`s:` 文本不会改写
 * 布尔读取。同一个 key 被两种类型读到时，只有对得上的那一种被改写，另一种保持宿主原样。
 */
internal object CloudKeyRuleResolver {
    /** 哨兵：保持宿主算出的原结果。 */
    val UNCHANGED = Any()

    fun resolve(
        rule: CloudKeyRule,
        kind: CloudKeyReadKind,
        original: Any?,
        default: Any?,
        hasDefault: Boolean
    ): Any? = when (rule) {
        is CloudKeyRule.Bool ->
            if (kind == CloudKeyReadKind.BOOL ||
                (kind == CloudKeyReadKind.OBJECT && (original is Boolean || default is Boolean))
            ) rule.value else UNCHANGED
        is CloudKeyRule.Text ->
            if (kind == CloudKeyReadKind.TEXT ||
                (kind == CloudKeyReadKind.OBJECT && (original is String || default is String))
            ) rule.value else UNCHANGED
        CloudKeyRule.UseDefault -> when {
            !hasDefault -> UNCHANGED
            kind == CloudKeyReadKind.BOOL -> if (default is Boolean) default else UNCHANGED
            // 字符串入口的返回值可空，调用处写 null 就是"没有云控时用 null"。
            kind == CloudKeyReadKind.TEXT -> if (default == null || default is String) default else UNCHANGED
            // 泛型入口的返回值不可空：默认值是 null 时没有安全的"默认"可还，保持原结果。
            else -> default ?: UNCHANGED
        }
    }
}
