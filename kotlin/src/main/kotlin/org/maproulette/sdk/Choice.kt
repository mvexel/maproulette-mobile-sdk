package org.maproulette.sdk

import kotlinx.serialization.json.*

/** One question about the task's element. [expect] maps each guarded key to its required current
 * value (null: absent). "Can't tell" is not listed: leaving the question out of the answers means it. */
data class ChoiceQuestion(
    val id: String,
    val prompt: String,
    val description: String?,
    val expect: Map<String, String?>,
    val options: List<ChoiceOption>,
)

/** One answer and its exact tag change; show the change below the label. */
data class ChoiceOption(
    val id: String,
    val label: String,
    val description: String?,
    val setTags: Map<String, String>,
    val unsetTags: List<String>,
)

/** A task-level result that is not an answer. [resolution] is the status it will set: the declared
 * 2 or 6, Not an issue for "gone" without deletion, or Fixed for an enabled delete. [deletesElement]
 * is true only when the payload declares a delete AND element deletion is enabled.
 * "It's not here" ([NOT_HERE]) and "It's moved" ([ChoiceSubmission.Moved]) are offered by every
 * choice task but are not in [MapRouletteClient.choiceOutcomes]; both set Too hard and never edit
 * OSM. Campaign outcomes can't use their ids. */
data class ChoiceOutcome(
    val id: String,
    val label: String,
    val description: String?,
    val resolution: TaskResolution,
    val deletesElement: Boolean,
) {
    /** The same outcome without the OSM delete: "gone" recorded as Not an issue. Use it per task, e.g.
     * when [MapRouletteClient.checkChoice] reports `deleteAllowed == false` or a delete failed with
     * [ChoiceProblem.ElementInUse]. [MapRouletteClient.submitChoice] accepts it under a
     * deletion-enabled client. Outcomes that do not delete are returned unchanged. */
    fun withoutDeletion(): ChoiceOutcome =
        if (deletesElement) copy(resolution = TaskResolution.NOT_AN_ISSUE, deletesElement = false) else this

    companion object {
        /** Id of the built-in Too hard outcome, for localizing its label ("Too hard" is a fallback). */
        const val TOO_HARD_ID: String = "too-hard"

        /** Id of the built-in "It's not here": the element isn't at its mapped position. */
        const val NOT_HERE_ID: String = "not-here"

        /** Id of the built-in "It's moved". Submit it with [ChoiceSubmission.Moved], which carries the
         * reported position; [ChoiceSubmission.Outcome] with this id is refused. */
        const val MOVED_ID: String = "moved"

        /** The built-in "It's not here", for `submitChoice(task, ChoiceSubmission.Outcome(ChoiceOutcome.NOT_HERE))`.
         * Every choice task accepts it. Its English label is a fallback. */
        @JvmField
        val NOT_HERE: ChoiceOutcome = ChoiceOutcome(NOT_HERE_ID, "It's not here", null, TaskResolution.TOO_HARD, false)
    }
}

sealed interface ChoiceSubmission {
    /** Question id → option id; non-empty. Questions left out are "Can't tell". */
    data class Answers(val byQuestion: Map<String, String>) : ChoiceSubmission

    /** One of [MapRouletteClient.choiceOutcomes], or its [ChoiceOutcome.withoutDeletion] form, or
     * [ChoiceOutcome.NOT_HERE]. Not "moved". */
    data class Outcome(val outcome: ChoiceOutcome) : ChoiceSubmission

    /** The built-in "It's moved", with where the element is now. Sets Too hard; never edits OSM. */
    data class Moved(val location: MovedLocation) : ChoiceSubmission
}

/** Where a person reports a moved element to be. [source] says how they picked it. Coordinates are
 * sent rounded to 6 decimals. The server refuses one more than 500 m from the element
 * ([ChoiceProblem.MovedTooFar]). Throws IllegalArgumentException for a latitude outside -90..90 or a
 * longitude outside -180..180. */
data class MovedLocation(val latitude: Double, val longitude: Double, val source: Source) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "coordinates out of range" }
    }

    enum class Source(internal val wire: String) {
        /** Picked on the map. */
        TAP("tap"),
        /** The device's location while standing at the element. */
        HERE("here"),
    }
}

/** [changesetId] is null when nothing was edited in OSM. After an interrupted response the result
 * comes from a fresh task read, which may not report the changeset: then it is null even for an edit. */
data class ChoiceResult(val status: TaskStatus, val changesetId: Long?)

/** Why a choice task went stale: the element is gone or invisible, its `match` tags changed, or a
 * guarded key no longer has its expected value. UNKNOWN covers reasons added later. */
enum class IneligibleReason { ELEMENT_GONE, MATCH_FAILED, KEY_CHANGED, UNKNOWN }

/** Fresh server-side OSM check. Eligibility is all-or-nothing: an ineligible task is not shown at
 * all. [deleteAllowed] (the node is in no way or relation) is only meaningful when [eligible];
 * [reason] is set only when not. The server's diagnostic `detail` is not modelled. */
data class ChoiceEligibility(
    val eligible: Boolean,
    val deleteAllowed: Boolean,
    val reason: IneligibleReason?,
    /** When present, only these question IDs currently lack their guarded OSM tags. */
    val questionIds: Set<String>? = null,
)

internal const val TOO_HARD_ID = ChoiceOutcome.TOO_HARD_ID
private val BUILT_IN_IDS = setOf(TOO_HARD_ID, ChoiceOutcome.NOT_HERE_ID, ChoiceOutcome.MOVED_ID)

/** Declared outcomes plus the built-in Too hard; empty when the task is not a valid choice task.
 * Apps use [MapRouletteClient.choiceOutcomes], which applies the client's setting. */
internal fun Task.choiceOutcomes(allowElementDeletion: Boolean): List<ChoiceOutcome> {
    val work = work(allowElementDeletion) as? TaskWork.Choice ?: return emptyList()
    return work.outcomes + ChoiceOutcome(TOO_HARD_ID, "Too hard", null, TaskResolution.TOO_HARD, false)
}

/** Strict v1 decoding (docs/design/mobile-choice-challenges.md §2). Any broken rule throws
 * IllegalArgumentException; unknown fields are ignored. The 16 KiB size rule is server-only. */
internal fun choiceWork(raw: JsonObject, allowElementDeletion: Boolean): TaskWork.Choice {
    val meta = raw.obj("meta")
    require(meta.whole("version") == 2L && meta.whole("type") == 3L && meta.whole("choiceVersion") == 1L)
    val element = osmElement(raw.req("element").str())
    val match = raw.opt("match")?.let { value ->
        val o = value as? JsonObject ?: fail()
        require(o.size in 1..4)
        o.mapValues { (key, tag) -> tagText(key); tagText(tag.str()) }
    }.orEmpty()

    val questions = raw.req("questions").arr().also { require(it.size in 1..8) }.map { question(it.objValue()) }
    require(questions.map { it.id }.toSet().size == questions.size)
    val guarded = questions.flatMap { it.expect.keys }
    require(guarded.toSet().size == guarded.size && guarded.none { it in match })

    val outcomes = raw.opt("outcomes")?.arr().orEmpty().also { require(it.size <= 4) }.map { item ->
        val o = item.objValue()
        val id = choiceId(o.req("id").str())
        require(id !in BUILT_IN_IDS)
        val status = o.opt("status")?.let { (it as? JsonPrimitive ?: fail()).wholeNumberOrNull() ?: fail() }
        val delete = o.opt("delete")?.let { require(it == JsonPrimitive(true)); true } ?: false
        require((status != null) != delete && (status == null || status == 2L || status == 6L))
        val resolution = when {
            delete && allowElementDeletion -> TaskResolution.FIXED
            delete -> TaskResolution.NOT_AN_ISSUE
            status == 2L -> TaskResolution.NOT_AN_ISSUE
            else -> TaskResolution.TOO_HARD
        }
        ChoiceOutcome(id, text(o.req("label").str(), 1, 60), o.optText("description", 300), resolution,
            delete && allowElementDeletion) to delete
    }
    require(outcomes.map { it.first.id }.toSet().size == outcomes.size)
    val deletes = outcomes.count { it.second }
    require(deletes == 0 || (deletes == 1 && element.type == OsmType.NODE && match.isNotEmpty()))
    return TaskWork.Choice(element, match, questions, outcomes.map { it.first })
}

private fun question(o: JsonObject): ChoiceQuestion {
    val id = choiceId(o.req("id").str())
    val expect = o.req("expect").let { it as? JsonObject ?: fail() }.also { require(it.size in 1..4) }
        .mapValues { (key, value) -> tagText(key); if (value == JsonNull) null else tagText(value.str()) }
    val options = o.req("options").arr().also { require(it.size in 2..12) }.map { item ->
        val option = item.objValue()
        val set = option.opt("setTags")?.let { it as? JsonObject ?: fail() }.orEmpty()
            .mapValues { (key, value) -> tagText(key); tagText(value.str()) }
        val unset = option.opt("unsetTags")?.arr().orEmpty().map { tagText(it.str()) }
        require(set.isNotEmpty() || unset.isNotEmpty())
        require(unset.toSet().size == unset.size && unset.none { it in set })
        require((set.keys + unset).all { it in expect })
        // An option equal to the expected state would be a no-op edit.
        require(expect != expect + set + unset.associateWith { null })
        ChoiceOption(choiceId(option.req("id").str()), text(option.req("label").str(), 1, 60),
            option.optText("description", 300), set, unset)
    }
    require(options.map { it.id }.toSet().size == options.size)
    return ChoiceQuestion(id, text(o.req("prompt").str(), 1, 200), o.optText("description", 500), expect, options)
}

/** `^(node|way|relation)/[1-9][0-9]{0,15}$`, checked without a regex (no `$`-before-newline quirk). */
internal fun osmElement(value: String): OsmElementRef {
    val type = value.substringBefore('/', "")
    val digits = value.substringAfter('/', "")
    require(type in setOf("node", "way", "relation") && digits.length in 1..16 &&
        digits[0] in '1'..'9' && digits.all { it in '0'..'9' })
    return OsmElementRef(OsmType.valueOf(type.uppercase()), digits.toLong())
}

/** `^[a-z0-9][a-z0-9-]{0,31}$` */
internal fun choiceId(value: String): String {
    fun ok(c: Char) = c in 'a'..'z' || c in '0'..'9'
    require(value.length in 1..32 && ok(value[0]) && value.all { ok(it) || it == '-' })
    return value
}

// Lengths count Unicode code points on both platforms.
private fun text(value: String, min: Int, max: Int): String {
    require(value.codePointCount(0, value.length) in min..max)
    return value
}

/** OSM tag key or value: 1–255 code points, no control characters (Cc). */
private fun tagText(value: String): String {
    text(value, 1, 255)
    require(value.none { it.code < 0x20 || it.code in 0x7F..0x9F })
    return value
}

private fun fail(): Nothing = throw IllegalArgumentException()
private fun JsonObject.req(key: String): JsonElement = get(key)?.takeUnless { it == JsonNull } ?: fail()
private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeUnless { it == JsonNull }
private fun JsonObject.obj(key: String): JsonObject = req(key).objValue()
private fun JsonObject.whole(key: String): Long = (req(key) as? JsonPrimitive ?: fail()).wholeNumberOrNull() ?: fail()
private fun JsonObject.optText(key: String, max: Int): String? = opt(key)?.let { text(it.str(), 0, max) }
private fun JsonElement.objValue(): JsonObject = this as? JsonObject ?: fail()
private fun JsonElement.arr(): JsonArray = this as? JsonArray ?: fail()
private fun JsonElement.str(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()

/** Canonical request body: answers sorted by question id; ids are pattern-checked, so no escaping. */
internal fun choiceBody(submission: ChoiceSubmission): String = when (submission) {
    is ChoiceSubmission.Answers -> submission.byQuestion.toSortedMap()
        .entries.joinToString(",", "{\"answers\":{", "}}") { (q, o) -> "\"$q\":\"$o\"" }
    is ChoiceSubmission.Outcome ->
        "{\"outcome\":\"${submission.outcome.id}\"" + (if (submission.outcome.deletesElement) ",\"delete\":true" else "") + "}"
    is ChoiceSubmission.Moved -> submission.location.let {
        "{\"outcome\":\"${ChoiceOutcome.MOVED_ID}\",\"location\":{\"lat\":${degrees(it.latitude)}," +
            "\"lon\":${degrees(it.longitude)},\"source\":\"${it.source.wire}\"}}"
    }
}

/** Fixed 6 decimals, without a negative zero. */
private fun degrees(value: Double): String =
    String.format(java.util.Locale.ROOT, "%.6f", value).let { if (it == "-0.000000") "0.000000" else it }

/** Checks the submission against the task's payload before anything is sent. Returns the status the
 * server will set on success. Throws IllegalArgumentException for any mismatch. */
internal fun Task.validateChoice(submission: ChoiceSubmission, allowElementDeletion: Boolean): TaskResolution {
    require(bundleId == null) { "Bundled tasks are not supported" }
    val work = work(allowElementDeletion) as? TaskWork.Choice
    requireNotNull(work) { "Not a valid choice task" }
    return when (submission) {
        is ChoiceSubmission.Answers -> {
            require(submission.byQuestion.size in 1..8) { "Answer 1 to 8 questions" }
            for ((questionId, optionId) in submission.byQuestion) {
                val question = work.questions.firstOrNull { it.id == questionId }
                require(question != null && question.options.any { it.id == optionId }) { "Unknown answer" }
            }
            TaskResolution.FIXED
        }
        is ChoiceSubmission.Moved -> TaskResolution.TOO_HARD
        is ChoiceSubmission.Outcome -> {
            require(submission.outcome.id != ChoiceOutcome.MOVED_ID) { "Submit moved with its location" }
            if (submission.outcome.id == ChoiceOutcome.NOT_HERE_ID) {
                require(submission.outcome == ChoiceOutcome.NOT_HERE) { "Use ChoiceOutcome.NOT_HERE" }
                return TaskResolution.TOO_HARD
            }
            // Must equal what this client decodes (or its no-delete form), so a UI built with another
            // deletion setting fails here.
            val allowed = choiceOutcomes(allowElementDeletion)
            require(submission.outcome in allowed || submission.outcome in allowed.map { it.withoutDeletion() }) {
                "Unknown outcome, or decoded with a different allowElementDeletion setting"
            }
            submission.outcome.resolution
        }
    }
}
