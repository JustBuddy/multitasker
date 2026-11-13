package wtf.buddyworks.multitasker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drags split-screen dividers. One UI gives apps no way to set three-pane sizes, but its dividers accept drags to
 * exact positions, so this service performs the drag a person would. It reads no screen content and reacts to no
 * events: it only acts when [SplitLauncher] asks it to, right after opening a shortcut. While it does, it shows a
 * small notice so the moving dividers don't come as a surprise.
 */
class DividerService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        notice?.let { runCatching { windowManager.removeViewImmediate(it) } }
        notice = null
        super.onDestroy()
    }

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    /** The "resizing" notice while it is on screen. Main thread only. */
    private var notice: View? = null
    private val autoHide = Runnable { removeNotice() }

    /**
     * Shows a small "Resizing split" pill at the top of the screen. It is an accessibility overlay (no extra permission)
     * that can't be touched, so the divider drags and the user's own touches go straight through it. It hides itself
     * after [NOTICE_TIMEOUT_MS] in case [hideResizeNotice] never comes. Safe to call from any thread.
     */
    fun showResizeNotice() {
        main.post { addNotice() }
    }

    private fun addNotice() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, NOTICE_TIMEOUT_MS)
        notice?.let {
            it.animate().cancel()
            it.alpha = 1f
            return
        }
        val view = buildNotice()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
            title = "MultiTasker resize notice"
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            return
        }
        notice = view
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    /** Fades the notice out and removes it. Safe to call from any thread, and when no notice is showing. */
    fun hideResizeNotice() {
        main.post { removeNotice() }
    }

    private fun removeNotice() {
        main.removeCallbacks(autoHide)
        val view = notice ?: return
        notice = null
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            runCatching { windowManager.removeView(view) }
        }.start()
    }

    private fun buildNotice(): View {
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        val label = TextView(this).apply {
            text = getString(R.string.resize_notice)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE6202124.toInt())
            }
            elevation = dp(6).toFloat()
            addView(spinner)
            addView(label)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Presses at ([fromX], [fromY]), moves to ([toX], [toY]) in one quick stroke and lifts. One UI's dividers stay
     * where they are released; they don't fling. Blocking; call off the main thread.
     */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, DRAG_MS))
            .build()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
            }, main)
            if (!dispatched) done.countDown()
        }
        done.await(DRAG_MS + 1_500, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    companion object {
        private const val DRAG_MS = 180L
        private const val FADE_MS = 150L
        private const val NOTICE_TIMEOUT_MS = 6_000L

        /**
         * The running service, or null if it is turned off. Not a leak: it is cleared in onDestroy, and the service
         * lives exactly as long as the process anyway.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var instance: DividerService? = null
            private set

        /** Whether the service is switched on in Accessibility settings (it may still be starting). */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, DividerService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == me }
        }
    }
}
