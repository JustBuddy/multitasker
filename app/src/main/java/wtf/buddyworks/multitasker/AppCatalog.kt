package wtf.buddyworks.multitasker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator

/** An app that can be put in a split shortcut: one launcher entry. */
data class LaunchableApp(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
) {
    /** [icon] for Compose, wrapped once instead of on every recomposition. */
    val image: ImageBitmap = icon.asImageBitmap()
}

object AppCatalog {
    private const val ICON_PX = 144

    /** Every launcher entry except this app's own, sorted by label. Slow (loads icons): call off the main thread. */
    fun load(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(query, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                LaunchableApp(
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    label = info.loadLabel(pm).toString().trim(),
                    icon = info.loadIcon(pm).toIconBitmap(),
                )
            }
            .distinctBy { it.component }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()
    }

    private fun Drawable.toIconBitmap(): Bitmap = toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
}
