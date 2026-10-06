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
 * is true only when the payload declares a delete AND element deletion is enabled. */
data class ChoiceOutcome(
    val id: String,
    val label: String,
    val description: String?,
    val resolution: TaskResolution,
    val deletesElement: Boolean,
)

sealed interface ChoiceSubmission {
    /** Question id → option id; non-empty. Questions left out are "Can't tell". */
    data class Answers(val byQuestion: Map<String, String>) : ChoiceSubmission

    /** One of [Task.choiceOutcomes], decoded with the same deletion setting as the client. */
    data class Outcome(val outcome: ChoiceOutcome) : ChoiceSubmission
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
)

internal const val TOO_HARD_ID = "too-hard"

/** Declared outcomes plus the built-in Too hard; empty when the task is not a valid choice task.
 * Pass the client's `allowElementDeletion`. */
fun Task.choiceOutcomes(allowElementDeletion: Boolean = false): List<ChoiceOutcome> {
    val work = work(allowElementDeletion) as? TaskWork.Choice ?: return emptyList()
    return work.outcomes + ChoiceOutcome(TOO_HARD_ID, "Too hard", null, TaskResolution.TOO_HARD, false)
}

/** Strict v1 decoding (docs/mobile-choice-challenges.md §2). Any broken rule throws
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
        require(id != TOO_HARD_ID)
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
}

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
        is ChoiceSubmission.Outcome -> {
            // Must equal what this client decodes, so a UI built with another deletion setting fails here.
            require(submission.outcome in choiceOutcomes(allowElementDeletion)) {
                "Unknown outcome, or decoded with a different allowElementDeletion setting"
            }
            submission.outcome.resolution
        }
    }
}
