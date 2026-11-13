package wtf.buddyworks.multitasker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import java.util.UUID

object ShortcutFactory {
    /** Adaptive icon canvas: 108dp at 4x. Launchers mask it and show roughly the middle 72dp. */
    private const val SIZE = 432f
    private const val BRAND = 0xFF0D9488.toInt()

    fun isSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Drops FLAG_ACTIVITY_CLEAR_TASK from shortcuts pinned by older versions: with it, tapping a shortcut twice
     * destroyed the launch that was still running. Updating a pinned shortcut keeps its label and icon.
     */
    fun migratePinned(context: Context) {
        val outdated = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .filter { (it.intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0 }
        if (outdated.isEmpty()) return
        val updated = outdated.map { old ->
            ShortcutInfoCompat.Builder(context, old.id)
                .setShortLabel(old.shortLabel)
                .setIntent(Intent(old.intent).apply { flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv() })
                .build()
        }
        ShortcutManagerCompat.updateShortcuts(context, updated)
    }

    /** Asks the launcher to pin a shortcut that opens [apps] in split. Returns false if the launcher refused. */
    fun requestPin(context: Context, name: String, apps: List<LaunchableApp>, config: SplitConfig): Boolean {
        val shortcut = ShortcutInfoCompat.Builder(context, "split-${UUID.randomUUID()}")
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(apps, config)))
            .setIntent(LaunchActivity.intent(context, config))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * App icons on translucent panes laid out like the split (see [PaneGeometry]), on the brand color. Everything
     * sits inside the adaptive-icon safe zone.
     */
    fun icon(apps: List<LaunchableApp>, config: SplitConfig): Bitmap {
        val bitmap = createBitmap(SIZE.toInt(), SIZE.toInt())
        val canvas = Canvas(bitmap)
        canvas.drawColor(BRAND)

        val safe = SIZE * 0.6f
        val origin = (SIZE - safe) / 2f
        val halfGap = SIZE * 0.0125f
        val panes = PaneGeometry.panes(config).map { p ->
            RectF(
                origin + p.left * safe + if (p.left > 0f) halfGap else 0f,
                origin + p.top * safe + if (p.top > 0f) halfGap else 0f,
                origin + p.right * safe - if (p.right < 1f) halfGap else 0f,
                origin + p.bottom * safe - if (p.bottom < 1f) halfGap else 0f,
            )
        }

        val panePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val radius = SIZE * 0.04f
        apps.take(3).zip(panes).forEach { (app, pane) ->
            canvas.drawRoundRect(pane, radius, radius, panePaint)
            val iconSize = minOf(pane.width(), pane.height()) * 0.86f
            val dst = RectF(
                pane.centerX() - iconSize / 2f,
                pane.centerY() - iconSize / 2f,
                pane.centerX() + iconSize / 2f,
                pane.centerY() + iconSize / 2f,
            )
            canvas.drawBitmap(app.icon, null, dst, iconPaint)
        }
        return bitmap
    }
}
