package wtf.buddyworks.multitasker

/** A pane in fractions of the screen (0..1). */
data class PaneRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

object PaneGeometry {
    /** Where each app of [config] ends up in true split mode, in app order. */
    fun panes(config: SplitConfig): List<PaneRect> {
        val x1 = config.leftPercent / 100f
        val x2 = config.rightPercent / 100f
        val y = config.stackPercent / 100f
        return when (config.apps.size) {
            0 -> emptyList()
            1 -> listOf(PaneRect(0f, 0f, 1f, 1f))
            2 -> listOf(PaneRect(0f, 0f, x1, 1f), PaneRect(x1, 0f, 1f, 1f))
            else -> when (config.layout) {
                ThreeAppLayout.COLUMNS -> listOf(
                    PaneRect(0f, 0f, x1, 1f),
                    PaneRect(x1, 0f, x2, 1f),
                    PaneRect(x2, 0f, 1f, 1f),
                )
                ThreeAppLayout.BIG_LEFT -> listOf(
                    PaneRect(0f, 0f, x1, 1f),
                    PaneRect(x1, 0f, 1f, y),
                    PaneRect(x1, y, 1f, 1f),
                )
                ThreeAppLayout.BIG_RIGHT -> listOf(
                    PaneRect(0f, 0f, x1, y),
                    PaneRect(0f, y, x1, 1f),
                    PaneRect(x1, 0f, 1f, 1f),
                )
            }
        }
    }
}
