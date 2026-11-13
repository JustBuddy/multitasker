package wtf.buddyworks.multitasker

import android.content.ComponentName

/** How three apps share the screen in true split mode. */
enum class ThreeAppLayout {
    /** Three columns. One UI opens them at 50 / 25 / 25; other widths need [DividerService]. */
    COLUMNS,

    /** App 1 fills the left column; apps 2 and 3 are stacked in the right column. */
    BIG_LEFT,

    /** Apps 1 and 2 are stacked in the left column; app 3 fills the right column. */
    BIG_RIGHT,
}

/**
 * What a shortcut opens. Apps are in reading order: left column top to bottom, then right column.
 *
 * All positions are percentages of the split area, measured from its left or top edge:
 * - [leftPercent]: the first vertical divider (2 apps, stacked layouts, and the first column of [ThreeAppLayout.COLUMNS]).
 * - [rightPercent]: the second vertical divider, [ThreeAppLayout.COLUMNS] only.
 * - [stackPercent]: the divider between the two stacked apps, stacked layouts only.
 *
 * One UI never makes a pane smaller than 250dp (656px on the Fold), and squeezing one further hides it, so the
 * limits below keep every pane above that on the unfolded screen. The launcher clamps again at runtime.
 */
data class SplitConfig(
    val apps: List<ComponentName>,
    val leftPercent: Int = DEFAULT_LEFT,
    val rightPercent: Int = DEFAULT_RIGHT,
    val stackPercent: Int = DEFAULT_STACK,
    val layout: ThreeAppLayout = ThreeAppLayout.COLUMNS,
) {
    val isColumns: Boolean get() = apps.size == 3 && layout == ThreeAppLayout.COLUMNS
    val isStacked: Boolean get() = apps.size == 3 && layout != ThreeAppLayout.COLUMNS

    /** Column widths that One UI opens with, needing no divider drags. */
    val hasDefaultColumns: Boolean get() = leftPercent == DEFAULT_LEFT && rightPercent == DEFAULT_RIGHT

    /** Whether opening this config takes divider drags by [DividerService] on top of SystemUI's split commands. */
    val needsDividerDrags: Boolean
        get() = (isColumns && !hasDefaultColumns) || (isStacked && stackPercent != DEFAULT_STACK)

    /** The same config with every value forced into a range One UI accepts. */
    fun sanitized(): SplitConfig {
        if (isColumns) {
            if (hasDefaultColumns) return this
            val left = leftPercent.coerceIn(MIN_WIDTH, 100 - 2 * MIN_WIDTH)
            val right = rightPercent.coerceIn(left + MIN_WIDTH, 100 - MIN_WIDTH)
            return copy(leftPercent = left, rightPercent = right)
        }
        return copy(
            leftPercent = leftPercent.coerceIn(MIN_WIDTH, MAX_WIDTH),
            stackPercent = stackPercent.coerceIn(MIN_STACK, MAX_STACK),
        )
    }

    /** The config a shortcut gets when only the defaults are available. */
    fun withDefaultSizes(): SplitConfig = copy(leftPercent = DEFAULT_LEFT, rightPercent = DEFAULT_RIGHT, stackPercent = DEFAULT_STACK)

    companion object {
        const val DEFAULT_LEFT = 50
        const val DEFAULT_RIGHT = 75
        const val DEFAULT_STACK = 50

        /** Narrowest column, as a share of the unfolded screen's width (250dp of 2448px is 27%). */
        const val MIN_WIDTH = 28
        const val MAX_WIDTH = 100 - MIN_WIDTH

        /** Shortest stacked pane, as a share of the unfolded screen's height (250dp of 1848px is 36%). */
        const val MIN_STACK = 37
        const val MAX_STACK = 100 - MIN_STACK
    }
}
