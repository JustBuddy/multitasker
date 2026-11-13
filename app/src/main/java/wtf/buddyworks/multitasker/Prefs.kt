package wtf.buddyworks.multitasker

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val FILE = "settings"
    private const val TRUE_SPLIT = "true_split"
    private const val RESIZE_TWICE = "resize_cold_apps_twice"

    /** Whether to use true 3-way split when its permissions are granted. On by default. */
    fun trueSplitEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TRUE_SPLIT, true)

    fun setTrueSplitEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(TRUE_SPLIT, enabled) }
    }

    /**
     * Whether panes of freshly started apps get resized twice. Some apps (Instagram, for one) start in a pane and then
     * miss their first resize, keeping the old layout until the next one. Off by default: most apps don't need it.
     */
    fun resizeColdAppsTwice(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(RESIZE_TWICE, false)

    fun setResizeColdAppsTwice(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(RESIZE_TWICE, enabled) }
    }
}
