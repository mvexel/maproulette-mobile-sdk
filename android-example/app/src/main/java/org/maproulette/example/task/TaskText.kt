package org.maproulette.example.task

import org.maproulette.sdk.Challenge
import org.maproulette.sdk.EditKind
import org.maproulette.sdk.FormField
import org.maproulette.sdk.MobileSupport
import org.maproulette.sdk.Task
import org.maproulette.sdk.TaskResolution
import org.maproulette.sdk.TaskWork
import org.maproulette.sdk.allowedResolutions
import org.maproulette.sdk.canSkip
import org.maproulette.sdk.mobileSupport
import org.maproulette.sdk.resolvedInstruction
import org.maproulette.sdk.templateProperties
import org.maproulette.sdk.work

/** User-facing wording for task completion (docs/challenge-types.md §7). */
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

    fun button(resolution: TaskResolution): String = when (resolution) {
        TaskResolution.FIXED -> "I fixed this in OSM"
        TaskResolution.NOT_AN_ISSUE -> "Not an issue"
        TaskResolution.ALREADY_FIXED -> "Already fixed"
        TaskResolution.TOO_HARD -> "Too hard"
    }

    fun explanation(resolution: TaskResolution): String = when (resolution) {
        TaskResolution.FIXED -> "You made the fix in OpenStreetMap yourself."
        TaskResolution.NOT_AN_ISSUE -> "The problem this task describes is not real (false positive)."
        TaskResolution.ALREADY_FIXED -> "Someone else had already fixed it in OpenStreetMap before you saw this task."
        TaskResolution.TOO_HARD -> "You can't complete it. It stays open for other mappers."
    }

    const val SKIP = "Skip"
    const val SKIP_EXPLANATION = "Leave it for someone else. The task stays open."

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

    /** Why some or all actions are missing, or null when everything applies. */
    fun limitation(task: Task): String? {
        val work = task.work()
        return when {
            task.mobileSupport() == MobileSupport.UNSUPPORTED -> when {
                task.bundleId != null -> "This task is part of a bundle. Bundled tasks can only be completed on the MapRoulette website."
                work is TaskWork.TagFix && work.edits.any { it.kind == EditKind.UNKNOWN } ->
                    "This task proposes an OpenStreetMap change the app does not understand. Complete it on the MapRoulette website."
                else -> "This task uses a format the app does not support. Complete it on the MapRoulette website."
            }
            task.allowedResolutions().isEmpty() && !task.canSkip() ->
                "This task is ${status(task.status?.code).lowercase()}. There is nothing left to do."
            work is TaskWork.TagFix ->
                "This task proposes tag changes. The app cannot apply them to OpenStreetMap, so \"I fixed this in OSM\" is not offered here."
            work is TaskWork.ChangeFile ->
                "This task comes with a change file for an OpenStreetMap editor. The app cannot apply it, so \"I fixed this in OSM\" is not offered here."
            else -> null
        }
    }

    /** "Completed by" line after a resolution, compared with the signed-in user. */
    fun completedBy(completedBy: Long?, me: Long): String = when (completedBy) {
        null -> "MapRoulette did not report who completed it."
        me -> "Completed by you (MapRoulette user $me)."
        else -> "Completed by MapRoulette user $completedBy, not you (you are user $me)."
    }
}
