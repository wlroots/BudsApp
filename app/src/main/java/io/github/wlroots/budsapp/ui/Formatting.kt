package io.github.wlroots.budsapp.ui

import io.github.wlroots.budsapp.model.RingSide

/** Small presentation helpers shared by the UI classes. */
object Formatting {
    fun humanize(value: String): String = value
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        }

    fun slotLabel(group: String, type: String): String = when (group) {
        "left" -> "Left"
        "right" -> "Right"
        "case" -> "Case"
        "single" -> "Single"
        else -> humanize(group)
    } + " · " + humanize(type)

    fun actionLabel(action: String): String = humanize(action)

    fun ringLabel(side: RingSide): String = when (side) {
        RingSide.LEFT -> "Left"
        RingSide.RIGHT -> "Right"
        RingSide.SINGLE -> "Buds"
    }
}
