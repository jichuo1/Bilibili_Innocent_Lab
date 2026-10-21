package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccountMineV2's Kotlin serializer is the semantic contract even when R8 renames every field.
 * Prune the constructed model before either the ViewModel or Compose can read its fields directly.
 * Getter hooks remain independent fallbacks; their mere presence does not prove consumer coverage.
 */
internal class MineModelFilter(
    private val owner: Class<*>,
    private val matches: (String, String?, String?, String?) -> Boolean,
    private val onScan: (Set<String>, Collection<MineComponentScanEntry>) -> Unit,
    private val environment: HookEnvironment,
    private val path: String = "model"
) {
    private val slots = MineSerializedFields(owner, setOf("sectionlist", "sectionlistv2", "livetip"))
    private val groups = GROUPS.mapNotNull { name -> Lookup.classOrNull(owner.classLoader, name)?.let(Group::resolve) }
    private val tips = HashMap<Class<*>, MineSerializedFields>()
    private val buttons = HashMap<Class<*>, Button>()
    private val depth = ThreadLocal<IntArray>()
    private val observed = AtomicBoolean()
    private val applied = AtomicBoolean()
    private val unknown = AtomicBoolean()
    private val settingsUnknown = AtomicBoolean()
    private val writeUnknown = AtomicBoolean()

    fun enter() {
        val count = depth.get() ?: IntArray(1).also(depth::set)
        count[0]++
    }

    fun exit(value: Any?) {
        val count = depth.get() ?: return
        count[0]--
        if (count[0] > 0) return
        depth.remove()
        if (value != null) process(value)
    }

    internal fun process(value: Any) {
        if (!owner.isInstance(value)) return
        processFields(value, slots.resolve(value))
    }

    fun processNamed(value: Any, sections: Field, liveTip: Field?): Int {
        return processFields(value, buildMap {
            put("sectionlistv2", sections)
            if (liveTip != null) put("livetip", liveTip)
        })
    }

    private fun processFields(value: Any, fields: Map<String, Field>): Int {
        val sectionFields = listOfNotNull(fields["sectionlist"], fields["sectionlistv2"])
            .filter { List::class.java.isAssignableFrom(it.type) }
        if (sectionFields.isEmpty()) {
            if (unknown.compareAndSet(false, true)) environment.logInfo(
                "mine_model_unknown", "[BIL] mine_components path=$path stage=UNKNOWN sections=0"
            )
            if (fields["livetip"] == null) return 0
        }
        evidence(FeatureRuntimeStage.OBSERVED, observed, 1)
        val entries = ArrayList<MineComponentScanEntry>()
        val capabilities = linkedSetOf<String>()
        var changed = 0
        sectionFields.forEach { field ->
            val source = runCatching { field.get(value) as? List<*> }.getOrNull() ?: return@forEach
            if (source is PrunedSections) {
                entries += source.entries
                capabilities += source.capabilities
                return@forEach
            }
            var supported = false
            val entryStart = entries.size
            val sectionCapabilities = linkedSetOf<String>()
            val originals = java.util.IdentityHashMap<Any, List<*>>()
            val removalRanges = java.util.IdentityHashMap<Any, IntRange>()
            var filtered = CopyOnFilter.list(source) { raw ->
                val group = groups.firstOrNull { it.owner.isInstance(raw) } ?: return@list false
                supported = true
                (runCatching { group.items.get(raw) }.getOrNull() as? List<*>)?.let { originals[raw] = it }
                val result = pruneGroup(raw, group)
                val start = entries.size
                entries += result.entries
                if (result.remove) removalRanges[raw] = start until entries.size
                changed += result.changed
                sectionCapabilities += "item_filter"
                if (group.title != null) sectionCapabilities += "group_filter"
                if (group.buttonShape?.text != null || result.entries.any { it.kind == "button" }) {
                    sectionCapabilities += "button_filter"
                }
                result.remove
            }
            if (supported) capabilities += sectionCapabilities
            // Hosts remove empty normal groups before appending their local settings item. Older
            // consumers index the last group directly; even newer lastOrNull consumers lose the
            // settings entry if we hand them no container. Keep a real, populated settings group.
            if (source.isNotEmpty() && filtered.isEmpty()) {
                val last = source.lastOrNull { raw -> raw != null && groups.any { it.owner.isInstance(raw) } }
                val group = last?.let { raw -> groups.firstOrNull { it.owner.isInstance(raw) } }
                if (last != null && group != null) {
                    val settings = group.item.settings()
                    if (settings != null && write(group.items, last, arrayListOf(settings))) {
                        filtered = arrayListOf(last)
                        MineComponentScanEntry.create("item", "设置", "10", MineSettingsEntryGuard.SETTINGS_URI,
                            showing = true, selectable = false)?.let(entries::add)
                        val key = MineComponentSelector.key("group", group.title?.readString(last), null, null)
                        for (index in entryStart until entries.size) {
                            if (entries[index].key == key) entries[index] = entries[index].copy(showing = true)
                        }
                    } else {
                        // No safe standard settings item ABI: restore the original group, including
                        // its populated list. Safety takes precedence over pretending full coverage.
                        val original = originals[last]
                        val current = runCatching { group.items.get(last) as? List<*> }.getOrNull()
                        if (original != null && write(group.items, last, original)) {
                            changed -= (original.size - (current?.size ?: original.size)).coerceAtLeast(0)
                            val restoredKeys = original.mapNotNull { item ->
                                item?.takeIf(group.item.owner::isInstance)?.let {
                                    MineComponentSelector.key("item", group.item.title.readString(it),
                                        group.item.id?.readValue(it), group.item.uri?.readString(it))
                                }
                            }.toSet() + listOfNotNull(MineComponentSelector.key("group", group.title?.readString(last), null, null))
                            for (index in entryStart until entries.size) {
                                if (entries[index].key in restoredKeys) entries[index] = entries[index].copy(showing = true)
                            }
                        }
                        filtered = arrayListOf(last)
                        sectionCapabilities.removeAll(setOf("group_filter", "item_filter"))
                        capabilities.removeAll(setOf("group_filter", "item_filter"))
                        if (settingsUnknown.compareAndSet(false, true)) environment.logInfo(
                            "mine_model_settings_unknown", "[BIL] mine_components path=$path stage=UNKNOWN settings_container=1"
                        )
                    }
                } else filtered = source
            }
            if (filtered !== source) {
                val removedIndexes = removalRanges.entries.filter { (group, _) -> filtered.none { it === group } }
                    .flatMap { it.value }.toSet()
                val sectionEntries = entries.subList(entryStart, entries.size).mapIndexed { index, entry ->
                    if (index + entryStart in removedIndexes) entry.copy(showing = false) else entry
                }
                if (write(field, value, PrunedSections(filtered, sectionEntries, sectionCapabilities.toSet()))) {
                    sectionEntries.forEachIndexed { index, entry -> entries[entryStart + index] = entry }
                    changed += source.size - filtered.size
                } else {
                    // Item/button writes may still have applied, but removed groups did not.
                    if (writeUnknown.compareAndSet(false, true)) environment.logInfo(
                        "mine_model_write_unknown", "[BIL] mine_components path=$path stage=UNKNOWN section_write=1"
                    )
                    for (index in entryStart until entries.size) if (entries[index].kind == "group") {
                        entries[index] = entries[index].copy(showing = true)
                    }
                }
            }
        }
        fields["livetip"]?.let { field ->
            val tip = runCatching { field.get(value) }.getOrNull() ?: return@let
            val named = synchronized(tips) {
                tips.getOrPut(tip.javaClass) { MineSerializedFields(tip.javaClass, emptySet(), expectedSerialName = null) }
            }.values(tip).ifEmpty {
                // The older FastJSON model exposes these semantic fields directly.
                listOf("text", "title", "id").mapNotNull { key ->
                    field(tip.javaClass, key)?.let { key to runCatching { it.get(tip) }.getOrNull() }
                }.toMap()
            }
            // Read values from the owning serializer rather than guessing R8 letters or equating
            // duplicate strings/zero ids with fields. These values are not retained after this call.
            if (named.keys.none { it in setOf("text", "title", "id") }) return@let
            capabilities += "live_tip_filter"
            val title = (named["text"] ?: named["title"]) as? String
            val id = named["id"]?.toString()
            val hidden = matches("live_tip", title, id, null)
            val didHide = hidden && write(field, value, null)
            MineComponentScanEntry.create("live_tip", title, id, null, showing = !didHide)?.let(entries::add)
            if (didHide) changed++
        }
        if (capabilities.isNotEmpty() || entries.isNotEmpty()) onScan(capabilities, entries)
        if (changed > 0) evidence(FeatureRuntimeStage.APPLIED, applied, changed)
        return changed
    }

    private fun pruneGroup(value: Any, group: Group): GroupResult {
        val source = runCatching { group.items.get(value) as? List<*> }.getOrNull()
            ?: return GroupResult(emptyList(), remove = false, changed = 0)
        val title = group.title?.readString(value)
        val hideGroup = matches("group", title, null, null)
        val entries = ArrayList<MineComponentScanEntry>()
        var changed = 0
        var hasSettings = false
        val oldEntries = (source as? PrunedItems)?.entries.orEmpty()
        entries += oldEntries
        val filtered = CopyOnFilter.list(source) { item ->
            if (!group.item.owner.isInstance(item)) return@list false
            val uri = group.item.uri?.readString(item)
            val protected = MineSettingsEntryGuard.isSettingsEntry(uri)
            hasSettings = hasSettings || protected
            val itemTitle = group.item.title.readString(item)
            val id = group.item.id?.readValue(item)
            val hidden = !protected && (hideGroup || matches("item", itemTitle, id, uri))
            MineComponentScanEntry.create("item", itemTitle, id, uri, !hidden, !protected)?.let { entry ->
                if (oldEntries.none { it.key == entry.key }) entries += entry
            }
            hidden
        }
        var listChanged = false
        if (filtered !== source) {
            listChanged = write(group.items, value, PrunedItems(filtered, entries.toList()))
            if (listChanged) changed += source.size - filtered.size
            else for (index in oldEntries.size until entries.size) {
                entries[index] = entries[index].copy(showing = true)
            }
        }
        var buttonRemains = false
        group.button?.let { field ->
            val button = runCatching { field.get(value) }.getOrNull() ?: return@let
            val shape = synchronized(buttons) { buttons.getOrPut(button.javaClass) { Button.resolve(button.javaClass) } }
            val text = shape.text?.readString(button)
            val uri = shape.uri?.readString(button)
            val id = shape.id?.readValue(button)
            val hidden = hideGroup || matches("button", text, id, uri)
            val didHide = hidden && write(field, value, null)
            buttonRemains = !didHide
            MineComponentScanEntry.create("button", text, id, uri, !didHide)?.let(entries::add)
            if (didHide) changed++
        }
        // Keep unknown future item types and originally empty groups. Remove occupancy only when
        // this filter actually emptied a populated menu, or when the group itself was selected.
        val remove = !hasSettings && (hideGroup || (listChanged && filtered.isEmpty() && !buttonRemains))
        MineComponentScanEntry.create("group", title, null, null, !remove)?.let(entries::add)
        return GroupResult(entries, remove, changed)
    }

    private fun evidence(stage: FeatureRuntimeStage, once: AtomicBoolean, count: Int) {
        environment.reportRuntimeEvidence(MineComponentFilterFeatureInstaller.ID, stage, count)
        if (once.compareAndSet(false, true)) {
            environment.reportStatus(MineComponentFilterFeatureInstaller.CHANNEL_STATUS,
                "${stage.name.lowercase()}:path=$path,count=$count")
            environment.logInfo("mine_model_${stage.name.lowercase()}",
                "[BIL] mine_components path=$path stage=${stage.name} count=$count")
        }
    }

    private data class GroupResult(val entries: List<MineComponentScanEntry>, val remove: Boolean, val changed: Int)
    private data class Item(val owner: Class<*>, val title: Field, val id: Field?, val uri: Field?,
                            val visible: Field?, val localShow: Field?, val constructor: Constructor<*>?) {
        fun settings(): Any? = runCatching {
            val ctor = constructor ?: return@runCatching null
            val route = uri?.takeIf { it.type == String::class.java } ?: return@runCatching null
            val identity = id?.takeIf { it.type == java.lang.Long.TYPE || it.type == java.lang.Long::class.java }
                ?: return@runCatching null
            val visibleField = visible?.takeIf { it.type == Integer.TYPE || it.type == Integer::class.java }
                ?: return@runCatching null
            val showField = localShow?.takeIf { it.type == java.lang.Boolean.TYPE || it.type == java.lang.Boolean::class.java }
                ?: return@runCatching null
            ctor.newInstance().also { value ->
                title.set(value, "设置")
                route.set(value, MineSettingsEntryGuard.SETTINGS_URI)
                identity.set(value, 10L)
                visibleField.set(value, 1)
                showField.set(value, true)
                check(route.get(value) == MineSettingsEntryGuard.SETTINGS_URI && visibleField.get(value) == 1 && showField.get(value) == true)
            }
        }.getOrNull()
    }
    private data class Button(val text: Field?, val id: Field?, val uri: Field?) {
        companion object {
            fun resolve(owner: Class<*>) = Button(field(owner, "text") ?: field(owner, "title"),
                field(owner, "id"), field(owner, "jumpUrl") ?: field(owner, "uri"))
        }
    }
    private data class Group(val owner: Class<*>, val title: Field?, val items: Field, val item: Item,
                             val button: Field?, val buttonShape: Button?) {
        companion object {
            fun resolve(owner: Class<*>): Group? {
                val itemOwner = Lookup.classOrNull(owner.classLoader, owner.name + "\$Item") ?: return null
                val title = field(itemOwner, "title")?.takeIf { it.type == String::class.java } ?: return null
                val items = field(owner, "itemList")?.takeIf { List::class.java.isAssignableFrom(it.type) } ?: return null
                val button = field(owner, "button")
                val buttonShape = button?.type?.let {
                    Button.resolve(it)
                }
                return Group(owner, field(owner, "title"), items,
                    Item(itemOwner, title, field(itemOwner, "id"), field(itemOwner, "uri"),
                        field(itemOwner, "visible"), field(itemOwner, "localShow"),
                        Lookup.constructorOrNull(itemOwner)), button, buttonShape)
            }
        }
    }

    /** Keep scan-only metadata beside copied host lists, never in a long-lived host-object cache. */
    private class PrunedItems(items: Collection<Any?>, val entries: List<MineComponentScanEntry>) : ArrayList<Any?>(items)
    private class PrunedSections(items: Collection<Any?>, val entries: List<MineComponentScanEntry>,
                                 val capabilities: Set<String>) : ArrayList<Any?>(items)

    companion object {
        const val OWNER = "tv.danmaku.bili.ui.main2.api.AccountMineV2"
        private val GROUPS = listOf("com.bilibili.lib.homepage.mine.MenuGroupV2", "com.bilibili.lib.homepage.mine.MenuGroup")
        private fun field(owner: Class<*>, name: String): Field? = Lookup.fieldOrNull(owner, name)?.takeUnless { it.isStatic }
        private fun Field.readString(value: Any): String? = runCatching { get(value) as? String }.getOrNull()
        private fun Field.readValue(value: Any): String? = runCatching { get(value)?.toString() }.getOrNull()
        private fun write(field: Field, value: Any, replacement: Any?): Boolean = runCatching {
            field.set(value, replacement)
            field.get(value) === replacement
        }.getOrDefault(false)
    }
}
