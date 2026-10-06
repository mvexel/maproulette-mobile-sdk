package org.maproulette.sdk

import kotlinx.serialization.json.*

/** Challenge-level hint only; the task's [work] decides how a task behaves. */
enum class CooperativeType { NONE, TAGS, CHANGE_FILE, UNKNOWN }

val Challenge.cooperativeKind: CooperativeType
    get() = when (cooperativeType) {
        null, 0 -> CooperativeType.NONE
        1 -> CooperativeType.TAGS
        2 -> CooperativeType.CHANGE_FILE
        else -> CooperativeType.UNKNOWN
    }

/** How a task is meant to be worked, decoded from its `cooperativeWork` payload. */
sealed interface TaskWork {
    data object Standard : TaskWork

    /** Proposed OSM tag changes. Applying them is an OSM write and is not offered by the SDK. */
    data class TagFix(val version: Int, val edits: List<ElementTagEdit>) : TaskWork

    /** An OSC change file for an external editor. Content is not decoded. */
    data class ChangeFile(val format: String?, val encoding: String?) : TaskWork

    /** Multiple-choice questions about one OSM element (`meta.type` 3, docs/design/mobile-choice-challenges.md).
     * [match] is the identity guard (empty when absent); [outcomes] are the declared ones: decoded without
     * deletion by [Task.work], with the client's setting by [MapRouletteClient.work].
     * [MapRouletteClient.choiceOutcomes] adds the built-in Too hard. */
    data class Choice(
        val element: OsmElementRef,
        val match: Map<String, String>,
        val questions: List<ChoiceQuestion>,
        val outcomes: List<ChoiceOutcome>,
    ) : TaskWork

    /** Unrecognized version/type or malformed payload, preserved as received. */
    data class Unknown(val raw: JsonObject) : TaskWork
}

enum class OsmType { NODE, WAY, RELATION }

data class OsmElementRef(val type: OsmType, val id: Long)

enum class EditKind { MODIFY, CREATE, DELETE, UNKNOWN }

/** [element] is null for created elements, which have no OSM id yet. */
data class ElementTagEdit(
    val element: OsmElementRef?,
    val kind: EditKind,
    val setTags: Map<String, String>,
    val unsetTags: List<String>,
)

/** Display-only form fields. Their answers (`completionResponses`) are not submitted. */
sealed interface FormField {
    val name: String

    data class Select(override val name: String, val label: String, val values: List<String>) : FormField
    data class Checkbox(override val name: String, val label: String) : FormField
}

/** Instruction markdown (not rendered by the SDK) with the form fields it declares. */
data class Instruction(val markdown: String, val formFields: List<FormField>) {
    /** Replaces `{{name}}` with [properties] values (missing → ""), matching the web UI.
     * `{{{…}}}` short codes and map-viewport properties are left to the app. */
    fun render(properties: Map<String, String>): String =
        propertyTag.replace(markdown) { it.groupValues[1] + (properties[it.groupValues[2]] ?: "") }
}

/** Mobile offers only tasks that are completed in place: valid, unbundled choice tasks that are
 * still actionable. Standard, tag-fix and change-file tasks are UNSUPPORTED. */
enum class MobileSupport { IN_PLACE, UNSUPPORTED }

// Same expressions as the MapRoulette web UI templating. Braces and brackets are escaped
// everywhere: Android's ICU regex engine rejects an unescaped `}` that the JVM accepts.
private val propertyTag = Regex("""(^|[^\{])\{\{([^\{][^\}]*)\}\}""")
private val shortCode = Regex("""\{\{\{[^\}]+\}\}\}|\[[^\]]+\](?=[^(]|$)""")
private val checkbox = Regex("""checkbox[/ ]?"([^"]+)"\s+name="([^"]+)"""")
private val select = Regex("""select[/ ]?"([^"]+)"\s+name="([^"]+)"\s+values="([^"]+)"""")
private val elementId = Regex("""(node|way|relation)/(\d+)""")
private val featureId = Regex("""^(node|way|relation|n|r|w)?/?\d+$""")
private val featureType = Regex("""^(node|way|relation|n|r|w)""")
private val actionable = setOf(0, 3, 6)

/** Decodes the task kind. The task payload wins over the challenge's cooperativeType. Choice
 * payloads are validated strictly: any broken rule gives [TaskWork.Unknown]. Delete outcomes decode
 * without deletion; [MapRouletteClient.work] applies the client's `allowElementDeletion`. */
fun Task.work(): TaskWork = work(allowElementDeletion = false)

internal fun Task.work(allowElementDeletion: Boolean): TaskWork {
    val raw = cooperativeWork ?: return TaskWork.Standard
    return try {
        val meta = raw["meta"] as? JsonObject
        val version = meta?.int("version")
        val type = meta?.int("type")
        when {
            version == 1 || (version == 2 && type == 1) -> TaskWork.TagFix(version!!, edits(raw))
            version == 2 && type == 2 -> (raw["file"] as? JsonObject).let {
                TaskWork.ChangeFile(it?.string("format"), it?.string("encoding"))
            }
            version == 2 && type == 3 -> choiceWork(raw, allowElementDeletion)
            else -> TaskWork.Unknown(raw)
        }
    } catch (_: IllegalArgumentException) {
        TaskWork.Unknown(raw)
    }
}

private fun edits(raw: JsonObject): List<ElementTagEdit> =
    (raw["operations"] as? JsonArray ?: JsonArray(emptyList())).map { item ->
        val operation = item as? JsonObject ?: throw IllegalArgumentException()
        val data = operation["data"] as? JsonObject
        val kind = when (operation.string("operationType")) {
            "modifyElement" -> EditKind.MODIFY
            "createElement" -> EditKind.CREATE
            "deleteElement" -> EditKind.DELETE
            else -> EditKind.UNKNOWN
        }
        val id = data?.string("id")
        val element = id?.let { value ->
            val match = elementId.matchEntire(value) ?: throw IllegalArgumentException()
            OsmElementRef(OsmType.valueOf(match.groupValues[1].uppercase()), match.groupValues[2].toLong())
        }
        require(element != null || kind == EditKind.CREATE || kind == EditKind.UNKNOWN)
        val set = linkedMapOf<String, String>()
        val unset = mutableListOf<String>()
        (data?.get("operations") as? JsonArray)?.forEach { dependent ->
            val o = dependent as? JsonObject ?: throw IllegalArgumentException()
            when (o.string("operation")) {
                "setTags" -> (o["data"] as? JsonObject ?: throw IllegalArgumentException())
                    .forEach { (key, value) -> set[key] = value.stringValue() }
                "unsetTags" -> (o["data"] as? JsonArray ?: throw IllegalArgumentException())
                    .forEach { unset += it.stringValue() }
            }
        }
        ElementTagEdit(element, kind, set, unset)
    }

/** Task instruction when non-blank, otherwise the challenge instruction, with its form fields. */
fun Task.resolvedInstruction(challenge: Challenge?): Instruction {
    val markdown = instruction?.takeIf { it.isNotBlank() } ?: challenge?.instruction ?: ""
    val fields = shortCode.findAll(markdown).mapNotNull { match ->
        val code = match.value
        checkbox.find(code)?.let { FormField.Checkbox(it.groupValues[2], it.groupValues[1]) }
            ?: select.find(code)?.let {
                FormField.Select(it.groupValues[2], it.groupValues[1], it.groupValues[3].split(Regex(""",\s*""")))
            }
    }.toList()
    return Instruction(markdown, fields)
}

/** Substitution values: primitive properties of all features (later features win) plus
 * `#mrTaskId`, and `#osmId`/`#osmType` identified from the first feature's id fields.
 * Map-viewport properties (`#map…`) depend on the app's map and are not supplied. */
fun Task.templateProperties(): Map<String, String> {
    val features = (geometry["features"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val result = linkedMapOf("#mrTaskId" to id.value.toString())
    features.firstOrNull()?.let { feature ->
        val properties = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val names = listOf("@id", "osmid", "osmIdentifier", "id")
        val raw = (names.firstNotNullOfOrNull { feature.primitive(it) }
            ?: names.firstNotNullOfOrNull { properties.primitive(it) })
        raw?.let { Regex("""\d+""").find(it)?.value }?.let { result["#osmId"] = it }
        val type = raw?.takeIf { featureType.containsMatchIn(it) }
            ?: listOf("type", "@type", "@osm_type").firstNotNullOfOrNull { properties.primitive(it, any = true) }
        featureType.find(type ?: "")?.value?.let {
            result["#osmType"] = when (it) { "n", "node" -> "node"; "w", "way" -> "way"; else -> "relation" }
        }
    }
    features.forEach { feature ->
        (feature["properties"] as? JsonObject)?.forEach { (key, value) ->
            (value as? JsonPrimitive)?.text()?.let { result[key] = it }
        }
    }
    return result
}

/** Derived suitability for mobile; the backend stores no such flag. IN_PLACE only for a valid,
 * unbundled choice task whose status is Created, Skipped or Too hard. */
fun Task.mobileSupport(): MobileSupport =
    if (bundleId == null && status?.code in actionable && work() is TaskWork.Choice) MobileSupport.IN_PLACE
    else MobileSupport.UNSUPPORTED

/** Whether to offer Skip (`POST task/{id}/skip`): only for IN_PLACE tasks. */
fun Task.canSkip(): Boolean = mobileSupport() == MobileSupport.IN_PLACE

/** Applies the verification rules to a fresh [getTask][MapRouletteClient.getTask] read after an
 * interrupted status write. [me] is the caller's MapRoulette user id. A missing `completedBy` is
 * treated as unknown, not as someone else. */
fun Task.verifyResolution(target: TaskResolution, me: Long): ResolutionCheck {
    val code = status?.code
    val byOther = completedBy != null && completedBy != me
    return when {
        code == target.code && !byOther && lockedBy == null -> ResolutionCheck.APPLIED
        code == target.code && byOther -> ResolutionCheck.RESOLVED_BY_OTHER
        code != target.code && code !in actionable -> ResolutionCheck.RESOLVED_BY_OTHER
        lockedBy != null && lockedBy != me -> ResolutionCheck.LOCKED_BY_OTHER
        lockedBy == me -> ResolutionCheck.NOT_APPLIED_LOCK_HELD
        else -> ResolutionCheck.NOT_APPLIED_UNLOCKED
    }
}

private fun JsonObject.int(key: String): Int? {
    val value = get(key)?.takeUnless { it == JsonNull } ?: return null
    val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
    require(!primitive.isString)
    val number = primitive.wholeNumberOrNull() ?: throw IllegalArgumentException()
    require(number in Int.MIN_VALUE..Int.MAX_VALUE)
    return number.toInt()
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

private fun JsonElement.stringValue(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()

// Valid feature ids are numeric or prefixed (node/1, n1); [any] accepts any primitive (type fields).
private fun JsonObject.primitive(key: String, any: Boolean = false): String? =
    (get(key) as? JsonPrimitive)?.text()?.takeIf { any || featureId.matches(it) }

// Primitive text matching Swift: whole numbers (1.0, 1e5) as integers, other numbers as Double.
private fun JsonPrimitive.text(): String? = when {
    this == JsonNull -> null
    isString || booleanOrNull != null -> content
    else -> wholeNumberOrNull()?.toString() ?: doubleOrNull?.toString()
}
