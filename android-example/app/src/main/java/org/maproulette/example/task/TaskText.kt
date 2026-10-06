package org.maproulette.example.task

import org.maproulette.sdk.Challenge
import org.maproulette.sdk.ChoiceOption
import org.maproulette.sdk.ChoiceOutcome
import org.maproulette.sdk.FormField
import org.maproulette.sdk.OsmElementRef
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.resolvedInstruction
import org.maproulette.sdk.templateProperties
import org.maproulette.sdk.work
import java.net.URI

/** User-facing wording for choice tasks (docs/mobile-choice-challenges.md §7). */
object TaskText {
    fun status(code: Int?): String = when (code) {
        null -> "Status unavailable"
        0 -> "Created"
        1 -> "Fixed"
        2 -> "Not an issue"
        3 -> "Skipped"
        4 -> "Deleted"
        5 -> "Already fixed"
        6 -> "Too hard"
        7 -> "Answered"
        8 -> "Validated"
        9 -> "Disabled"
        else -> "Unknown status ($code)"
    }

    const val SKIP = "Skip"
    const val SKIP_EXPLANATION = "Leave it for someone else. The task stays open."
    const val CANT_TELL = "Can't tell"
    const val NO_LONGER_NEEDED = "This one no longer needs answering."

    fun reviewStatus(code: Int?): String? = when (code) {
        null, -1 -> null
        0 -> "Review requested"
        1 -> "Review approved"
        2 -> "Review rejected"
        3 -> "Review assisted"
        4 -> "Review disputed"
        5 -> "Review unnecessary"
        6 -> "Approved with revisions"
        7 -> "Approved with fixes after revisions"
        else -> "Review status $code"
    }

    /** Instruction text with `{{property}}` placeholders filled in; markdown is shown as plain text. */
    fun instruction(task: Task, challenge: Challenge): String {
        val instruction = task.resolvedInstruction(challenge)
        val text = instruction.render(task.templateProperties()).trim()
        val fields = instruction.formFields.map {
            when (it) {
                is FormField.Checkbox -> "• ${it.label}"
                is FormField.Select -> "• ${it.label}: ${it.values.joinToString(" / ")}"
            }
        }
        val questions = if (fields.isEmpty()) "" else
            "\n\nThe website asks these questions; the app does not submit answers:\n" + fields.joinToString("\n")
        return (text.ifEmpty { "No instructions." }) + questions
    }

    /** Why a task is "Not available on mobile". */
    fun unavailableReason(task: Task): String = when {
        task.bundleId != null -> "This task is part of a bundle. Complete it on the MapRoulette website."
        task.work() !is TaskWork.Choice -> "The app only handles multiple-choice tasks. Complete this one on the MapRoulette website."
        else -> "This task is ${status(task.status?.code).lowercase()}. There is nothing left to do."
    }

    fun element(ref: OsmElementRef): String = "${ref.type.name.lowercase()}/${ref.id}"

    /** The exact tag change of an option, as shown below its label: `backrest=yes`, `remove note`. */
    fun tagChange(option: ChoiceOption): String =
        (option.setTags.map { (key, value) -> "$key=$value" } + option.unsetTags.map { "remove $it" }).joinToString(", ")

    /** What an outcome records, shown on its button. */
    fun outcomeExplanation(outcome: ChoiceOutcome, element: OsmElementRef): String = when {
        outcome.deletesElement -> "Deletes ${element(element)} from OpenStreetMap and marks the task Fixed."
        outcome.resolution == TaskResolution.TOO_HARD -> "You can't answer it. It stays open for other mappers."
        else -> "Marks the task “${status(outcome.resolution.code)}”. Does not edit OpenStreetMap."
    }

    /** Which OSM server this build edits; [osmServer] is null when the app does not know it. */
    fun osmNotice(osmServer: String?): String = when (osmServer) {
        null -> "The test backend decides which OpenStreetMap server is edited."
        DEV_OSM -> "Staging build: this edits the development OpenStreetMap server (${host(osmServer)}), not the real map."
        else -> "This edits OpenStreetMap at ${host(osmServer)}."
    }

    /** Confirmation for answers: the exact tag changes that will be uploaded. */
    fun answersSummary(form: ChoiceForm, taskId: Long, me: Long, osmServer: String?): String = buildString {
        append("Upload these tag changes to ${element(form.work.element)} in OpenStreetMap:")
        form.work.questions.forEach { question ->
            val option = question.options.firstOrNull { it.id == form.answers[question.id] } ?: return@forEach
            append("\n• ${tagChange(option)}")
        }
        val skipped = form.work.questions.filter { it.id !in form.answers }
        if (skipped.isNotEmpty()) {
            append("\n\nLeft as “$CANT_TELL” (not changed):")
            skipped.forEach { append("\n• ${it.prompt}") }
        }
        append("\n\nThe changes are uploaded in one changeset as your OpenStreetMap account. ")
        append("MapRoulette then marks task $taskId as Fixed for user $me.")
        append("\n\n${osmNotice(osmServer)}")
    }

    /** Confirmation for a task-level outcome. */
    fun outcomeSummary(outcome: ChoiceOutcome, element: OsmElementRef, taskId: Long, me: Long, osmServer: String?): String =
        if (outcome.deletesElement) {
            "Delete ${element(element)} from OpenStreetMap as your OpenStreetMap account, then mark task $taskId as Fixed " +
                "for MapRoulette user $me.\n\n${osmNotice(osmServer)}"
        } else {
            "Mark task $taskId as “${status(outcome.resolution.code)}” in MapRoulette as user $me. It does not edit OpenStreetMap."
        }

    /** Web link to an OSM changeset, or null when the server is unknown. */
    fun changesetUrl(osmServer: String?, changesetId: Long): String? = osmServer?.let { "$it/changeset/$changesetId" }

    /** "Completed by" line after a resolution, compared with the signed-in user. */
    fun completedBy(completedBy: Long?, me: Long): String = when (completedBy) {
        null -> "MapRoulette did not report who completed it."
        me -> "Completed by you (MapRoulette user $me)."
        else -> "Completed by MapRoulette user $completedBy, not you (you are user $me)."
    }

    /** Tasks to offer as "Next task": those after [current] in the list order, then those before it. */
    fun nextTasks(ids: List<Long>, current: Long): List<Long> {
        val at = ids.indexOf(current)
        return if (at < 0) ids.filter { it != current } else ids.drop(at + 1) + ids.take(at)
    }

    const val DEV_OSM = "https://master.apis.dev.openstreetmap.org"

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url
}
