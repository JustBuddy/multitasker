package wtf.buddyworks.multitasker

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Opens up to three apps side by side.
 *
 * Two strategies, both verified on a Galaxy Z Fold (One UI 9 / Android 17):
 *
 * - [Strategy.TRUE_SPLIT] drives SystemUI's window-manager shell through its `WMShell splitscreen` commands. That is
 *   the only route available outside the system to One UI's third split pane ("cell") and to custom 2-pane sizes.
 *   Reaching the commands takes DUMP and PACKAGE_USAGE_STATS, which can only be granted over ADB. Custom 3-pane
 *   sizes have no command at all; for those, [DividerService] drags the dividers after the split opens.
 *
 * - [Strategy.ADJACENT] uses public APIs only: app 1 opens normally, app 2 with FLAG_ACTIVITY_LAUNCH_ADJACENT
 *   (One UI then splits it with app 1, always 50/50), app 3 as a pop-up (freeform) window on top of the split.
 */
object SplitLauncher {
    private const val TAG = "MultiTasker"

    /** Time for app 1's task to come to the front before app 2 asks to go next to it. */
    private const val ADJACENT_DELAY_MS = 350L

    /** Time for the split to settle before app 3 opens as a pop-up over it. */
    private const val POPUP_DELAY_MS = 450L

    /** How long the invisible launcher activity lives after the last launch. It must outlive the launches. */
    private const val FINISH_DELAY_MS = 600L

    /** How long to wait for apps to show up as tasks, or in their split panes. */
    private const val TASK_WAIT_MS = 4_000L

    /** Pause after a split transition before starting the next one, so One UI doesn't drop it. */
    private const val SETTLE_MS = 600L

    // Divider timing, measured on the Fold. Dividers are listed as touchable ~150ms after a split command but ignore
    // drags until its animation ends, ~500ms after the command. After a divider is released, One UI hides the other
    // divider ~50-100ms later for ~300ms, and it again ignores drags for ~250ms after it reappears.
    private const val ENTER_READY_MS = 600L
    private const val HIDE_START_MS = 250L
    private const val REAPPEAR_READY_MS = 320L
    private const val DIVIDER_WAIT_MS = 2_000L
    private const val DIVIDER_POLL_MS = 30L

    /** How far short of its target a divider stops on the first of two passes. Well past One UI's touch slop. */
    private const val REDRAG_STEP_DP = 40

    /** Time from releasing a divider until the panes report their new bounds. */
    private const val VERIFY_DELAY_MS = 120L

    /** A divider that ends up this far from its target (in px) gets dragged again. */
    private const val DRAG_TOLERANCE_PX = 24

    private const val DUMPSYS_TIMEOUT_S = 5L

    /** One UI's smallest split pane. Narrower drags are clamped; much narrower ones hide ("stash") the pane. */
    private const val MIN_PANE_DP = 250

    // One UI ActivityOptions extras, read by the system from the options Bundle (see ActivityOptions(Bundle)).
    private const val KEY_ENTER_SPLIT_SIDE = "android:activity.enterSplitSideWithAdjacentFlag"
    private const val SPLIT_SIDE_RIGHT = 4
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"

    /** Unlike [KEY_WINDOWING_MODE], also applies when an existing task is brought back. */
    private const val KEY_FORCE_WINDOWING_MODE = "android.activity.forceWindowingMode"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    // WindowConfiguration stage positions (One UI). Cell positions must combine a column and a row: a column alone
    // produced overlapping panes in testing.
    private const val STAGE_LEFT = 8
    private const val STAGE_RIGHT = 32
    private const val STAGE_BOTTOM = 64
    private const val CELL_RIGHT_BOTTOM = STAGE_RIGHT or STAGE_BOTTOM
    private const val CELL_LEFT_BOTTOM = STAGE_LEFT or STAGE_BOTTOM

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val RECENT_TASK = Regex("""\* Recent #\d+: Task\{\S+ #(\d+) type=(\S+)""")
    private val TASK_COMPONENT = Regex("""cmp=([^ }]+)""")
    private val PROCESS_LINE = Regex(""" \d+:([A-Za-z0-9_.]+)/[u0-9]""") // uid: u0a123, or 1000 for system apps
    private val TASK_USER = Regex("""userId=(\d+)""")
    private val DIVIDER_INPUT = Regex("""name=Embedded\{StageCoordinatorSplitDivider(?:Cell)?\}.*?inputConfig=([^,]*),""")
    private val STAGE_BOUNDS = Regex("""Task=\d+\((main|side|cell)\) .*?bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    private enum class Strategy { TRUE_SPLIT, ADJACENT }

    /** How a true split attempt ended. Only [COMMAND_FAILED] falls back to the public-API launch. */
    private enum class Outcome { OPENED, NOT_IN_SPLIT, COMMAND_FAILED }

    /**
     * Set while a launch runs, divider drags included. A shortcut tapped meanwhile is ignored: two launches would
     * interleave their SystemUI commands and cancel each other's drags.
     */
    private val busy = AtomicBoolean(false)

    /** Whether a launch is running right now. */
    val isBusy: Boolean get() = busy.get()

    /** True when the ADB-granted permissions for [Strategy.TRUE_SPLIT] are in place. */
    fun isTrueSplitAvailable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED

    /** The strategy [launch] will use for [config] with the current settings and permissions. */
    private fun strategyFor(context: Context, config: SplitConfig): Strategy =
        if (config.apps.size >= 2 && Prefs.trueSplitEnabled(context) && isTrueSplitAvailable(context) &&
            canUseTrueSplit(context, config.apps)
        ) {
            Strategy.TRUE_SPLIT
        } else {
            Strategy.ADJACENT
        }

    /**
     * Opens [config] from [host], an activity that must stay alive until [onDone] runs. Call on the main thread.
     * Divider drags may still run after [onDone]; they don't need the host.
     */
    fun launch(host: Activity, config: SplitConfig, onDone: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        if (config.apps.isEmpty()) return onDone()
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "A launch is already running; ignoring this one")
            return onDone()
        }
        // Runs onDone at most once, whichever path gets there first.
        val doneOnce = AtomicBoolean(false)
        val finish = { if (doneOnce.compareAndSet(false, true)) onDone() }
        val finishAdjacent = {
            busy.set(false)
            finish()
        }
        if (strategyFor(host, config) == Strategy.TRUE_SPLIT) {
            Thread {
                val outcome = try {
                    startTrueSplit(config.sanitized(), Prefs.resizeColdAppsTwice(host)) { handler.post(finish) }
                } catch (e: Throwable) {
                    // Includes DumpsysTimeout: SystemUI may well have acted, so this must not launch again.
                    Log.e(TAG, "True split stopped early", e)
                    Outcome.NOT_IN_SPLIT
                }
                handler.post {
                    when (outcome) {
                        // NOT_IN_SPLIT: SystemUI took the commands but an app didn't join the split (some apps refuse
                        // split screen, others start slowly). Launching again the other way would only fight it.
                        Outcome.OPENED, Outcome.NOT_IN_SPLIT -> {
                            busy.set(false)
                            finish()
                        }
                        Outcome.COMMAND_FAILED -> {
                            Log.w(TAG, "True split commands failed, falling back to adjacent launch")
                            launchAdjacent(host, config.apps, handler, finishAdjacent)
                        }
                    }
                }
            }.start()
        } else {
            launchAdjacent(host, config.apps, handler, finishAdjacent)
        }
    }

    private fun launchAdjacent(host: Activity, apps: List<ComponentName>, handler: Handler, onDone: () -> Unit) {
        // App 1 must be fullscreen: if it is still a pop-up from an earlier launch, One UI has nothing to pair app 2 with.
        start(host, apps[0], extraFlags = 0, options = if (apps.size >= 2) windowingOptions(WINDOWING_MODE_FULLSCREEN) else null)
        if (apps.size >= 2) {
            handler.postDelayed({
                val options = ActivityOptions.makeBasic().toBundle().apply { putInt(KEY_ENTER_SPLIT_SIDE, SPLIT_SIDE_RIGHT) }
                start(host, apps[1], Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, options)
            }, ADJACENT_DELAY_MS)
        }
        if (apps.size >= 3) {
            handler.postDelayed({
                start(host, apps[2], extraFlags = 0, options = windowingOptions(WINDOWING_MODE_FREEFORM))
            }, ADJACENT_DELAY_MS + POPUP_DELAY_MS)
        }
        val lastLaunch = when (apps.size) {
            1 -> 0L
            2 -> ADJACENT_DELAY_MS
            else -> ADJACENT_DELAY_MS + POPUP_DELAY_MS
        }
        handler.postDelayed(onDone, lastLaunch + FINISH_DELAY_MS)
    }

    private fun windowingOptions(mode: Int): Bundle = ActivityOptions.makeBasic().toBundle().apply {
        putInt(KEY_WINDOWING_MODE, mode)
        putInt(KEY_FORCE_WINDOWING_MODE, mode)
    }

    private fun start(host: Activity, app: ComponentName, extraFlags: Int, options: Bundle?) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or extraFlags)
        try {
            host.startActivity(intent, options)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start $app", e)
        }
    }

    /**
     * SystemUI's command parser throws on malformed input, and an exception there restarts SystemUI. So only call
     * it with 2 or 3 distinct, well-formed package names that SystemUI can resolve to a launch intent. SystemUI opens
     * each package's default entry, so apps picked by a secondary launcher entry (e.g. Lens inside the Google app)
     * take the public-API route instead.
     */
    private fun canUseTrueSplit(context: Context, apps: List<ComponentName>): Boolean {
        val packages = apps.map { it.packageName }
        return packages.size in 2..3 &&
            packages.distinct().size == packages.size &&
            apps.all { app ->
                PACKAGE_NAME.matches(app.packageName) &&
                    context.packageManager.getLaunchIntentForPackage(app.packageName)?.component == app
            }
    }

    /**
     * Blocking; run off the main thread. Calls [onShown] once all apps are in their panes. A divider drag that misses
     * leaves One UI's sizes but still counts as [Outcome.OPENED].
     */
    private fun startTrueSplit(config: SplitConfig, resizeColdTwice: Boolean, onShown: () -> Unit): Outcome {
        val packages = config.apps.map { it.packageName }
        // Which apps start from scratch, noted before anything launches (see Prefs.resizeColdAppsTwice).
        val cold = if (resizeColdTwice && config.needsDividerDrags) packages.toSet() - runningPackages() else emptySet()
        val outcome = when {
            // Equal halves and three columns open with one startIntents; custom columns are dragged afterwards.
            (packages.size == 2 && config.leftPercent == SplitConfig.DEFAULT_LEFT) || config.isColumns -> when {
                !startIntents(packages) -> Outcome.COMMAND_FAILED
                !awaitInSplit(packages) -> Outcome.NOT_IN_SPLIT
                else -> Outcome.OPENED
            }
            else -> startSizedSplit(config)
        }
        if (outcome != Outcome.OPENED) return outcome
        onShown()
        if (config.needsDividerDrags) {
            val dragger = DividerService.instance
            if (dragger == null) {
                Log.w(TAG, "Custom 3-pane sizes need the accessibility service; keeping One UI's sizes")
            } else {
                dragger.showResizeNotice()
                try {
                    dragDividers(dragger, config, cold)
                } finally {
                    dragger.hideResizeNotice()
                }
            }
        }
        return Outcome.OPENED
    }

    /** Two apps at a custom width, or a stacked layout: split the column apps at the ratio, then add the stacked app. */
    private fun startSizedSplit(config: SplitConfig): Outcome {
        val packages = config.apps.map { it.packageName }
        val (left, right, cell, cellPosition) = when {
            packages.size == 2 -> Quad(packages[0], packages[1], null, 0)
            config.layout == ThreeAppLayout.BIG_LEFT -> Quad(packages[0], packages[1], packages[2], CELL_RIGHT_BOTTOM)
            else -> Quad(packages[0], packages[2], packages[1], CELL_LEFT_BOTTOM)
        }
        val byPackage = config.apps.associateBy { it.packageName }
        val columnApps = listOf(byPackage.getValue(left), byPackage.getValue(right))
        var tasks = findTasks(columnApps)
        if (tasks == null) {
            // startSplitTasks takes task IDs, so apps that aren't running yet are started first, at 50/50.
            if (!startIntents(listOf(left, right))) return Outcome.COMMAND_FAILED
            val deadline = SystemClock.uptimeMillis() + TASK_WAIT_MS
            while (tasks == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(250)
                tasks = findTasks(columnApps)
            }
            if (tasks == null) return Outcome.NOT_IN_SPLIT
            SystemClock.sleep(SETTLE_MS)
        }
        val ratio = String.format(Locale.ROOT, "%.2f", config.leftPercent / 100f)
        val all = listOfNotNull(left, right, cell)
        // Rarely (seen once, with apps starting cold) One UI drops part of the sequence; one retry has fixed it.
        repeat(2) { attempt ->
            if (!wmShell("startSplitTasks", tasks[0].toString(), tasks[1].toString(), ratio)) return Outcome.COMMAND_FAILED
            if (cell != null) {
                SystemClock.sleep(SETTLE_MS)
                if (!wmShell("startIntentToCell", cell, cellPosition.toString())) return Outcome.COMMAND_FAILED
            }
            if (awaitInSplit(all)) return Outcome.OPENED
            Log.w(TAG, "Split incomplete after attempt ${attempt + 1}")
        }
        return Outcome.NOT_IN_SPLIT
    }

    private data class Quad(val left: String, val right: String, val cell: String?, val cellPosition: Int)

    private data class Pane(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /**
     * A divider drag from its current middle to the target, along one axis. [min]..[max] is where the divider may go
     * on that axis without making a pane too small.
     */
    private data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val min: Int, val max: Int) {
        val horizontal: Boolean get() = fromY == toY
        val distance: Int get() = abs(toX - fromX) + abs(toY - fromY)

        /**
         * The same drag, stopping [step] short of the target (or [step] past it if there is no room before it), for
         * a first pass that resizes the panes once before the real target.
         */
        fun intermediate(step: Int): Drag? {
            val from = if (horizontal) fromX else fromY
            val to = if (horizontal) toX else toY
            val towardFrom = if (from > to) 1 else -1
            val short = to + towardFrom * step
            val past = to - towardFrom * step
            val point = when {
                abs(from - to) > step && short in min..max -> short
                past in min..max -> past
                else -> return null
            }
            return if (horizontal) copy(toX = point) else copy(toY = point)
        }
    }

    /**
     * Moves the dividers to [config]'s positions. Each divider is grabbed at the middle of its length (One UI's handle,
     * the only part that takes touches), dragged, and checked; a miss gets one more try.
     */
    private fun dragDividers(dragger: DividerService, config: SplitConfig, cold: Set<String>) {
        val minPane = (MIN_PANE_DP * Resources.getSystem().displayMetrics.density).roundToInt() + 8
        val apps = config.apps.map { it.packageName }
        awaitDividersReady(afterDrag = false)
        if (config.isColumns) {
            // SystemUI's three columns are side | main | cell, left to right. One UI opens them at 50/25/25, so the
            // first divider only ever moves left and can go first without squeezing the middle column.
            // The middle app is resized by both dividers anyway; the outer ones only by theirs.
            val firstMoved = dragTo(dragger, "first column", twice = apps[0] in cold) { stages ->
                val (a, b, c) = columns(stages) ?: return@dragTo null
                val min = a.left + minPane
                val max = c.left - minPane
                val target = (a.left + (c.right - a.left) * config.leftPercent / 100)
                    .clampOrNull(min, c.right - 2 * minPane) ?: return@dragTo null
                Drag((a.right + b.left) / 2, a.centerY, target, a.centerY, min, max)
            }
            if (firstMoved) awaitDividersReady(afterDrag = true)
            dragTo(dragger, "second column", twice = apps[2] in cold) { stages ->
                val (a, b, c) = columns(stages) ?: return@dragTo null
                val min = a.right + minPane
                val max = c.right - minPane
                val target = (a.left + (c.right - a.left) * config.rightPercent / 100)
                    .clampOrNull(min, max) ?: return@dragTo null
                Drag((b.right + c.left) / 2, b.centerY, target, b.centerY, min, max)
            }
        } else if (config.isStacked) {
            // The stacked pair is side over cell (big left) or main over cell (big right).
            val upperStage = if (config.layout == ThreeAppLayout.BIG_LEFT) "side" else "main"
            val stacked = if (config.layout == ThreeAppLayout.BIG_LEFT) apps.drop(1) else apps.take(2)
            dragTo(dragger, "stacked panes", twice = stacked.any { it in cold }) { stages ->
                val upper = stages[upperStage] ?: return@dragTo null
                val lower = stages["cell"] ?: return@dragTo null
                if (abs(upper.left - lower.left) > 4 || upper.bottom >= lower.top) return@dragTo null
                val min = upper.top + minPane
                val max = lower.bottom - minPane
                val target = (upper.top + (lower.bottom - upper.top) * config.stackPercent / 100)
                    .clampOrNull(min, max) ?: return@dragTo null
                Drag(upper.centerX, (upper.bottom + lower.top) / 2, upper.centerX, target, min, max)
            }
        }
    }

    /**
     * Drags one divider into place, checking the result right after release and retrying once if it missed. With
     * [twice], it first stops a little short of the target, so the apps on both sides get resized twice. Returns
     * whether it dragged at all, so callers can skip waiting for the dividers when nothing moved.
     */
    private fun dragTo(
        dragger: DividerService,
        what: String,
        twice: Boolean = false,
        plan: (Map<String, Pane>) -> Drag?,
    ): Boolean {
        var dragged = false
        if (twice) {
            val step = (REDRAG_STEP_DP * Resources.getSystem().displayMetrics.density).roundToInt()
            readStages()?.let(plan)?.intermediate(step)?.let { first ->
                dragger.drag(first.fromX.toFloat(), first.fromY.toFloat(), first.toX.toFloat(), first.toY.toFloat())
                dragged = true
                SystemClock.sleep(VERIFY_DELAY_MS)
                awaitDividersReady(afterDrag = true)
            }
        }
        for (attempt in 0..1) {
            var drag = readStages()?.let(plan)
            if (drag == null) {
                Log.w(TAG, "Can't size $what: unexpected split layout")
                return dragged
            }
            if (drag.distance <= DRAG_TOLERANCE_PX) return dragged
            if (attempt > 0) {
                Log.w(TAG, "Drag for $what missed, retrying")
                awaitDividersReady(afterDrag = true)
                // The bounds may have caught up meanwhile: a late update, not a miss. Don't tap a divider in place.
                drag = readStages()?.let(plan) ?: return dragged
                if (drag.distance <= DRAG_TOLERANCE_PX) return dragged
            }
            dragger.drag(drag.fromX.toFloat(), drag.fromY.toFloat(), drag.toX.toFloat(), drag.toY.toFloat())
            dragged = true
            SystemClock.sleep(VERIFY_DELAY_MS)
        }
        val left = readStages()?.let(plan)
        if (left != null && left.distance > DRAG_TOLERANCE_PX) Log.w(TAG, "Couldn't move $what into place")
        return dragged
    }

    /**
     * Waits until the dividers take a drag. They are embedded SystemUI surfaces, so their state comes from the input
     * dispatcher, where a divider that can't be touched is listed as NOT_VISIBLE. Being listed as touchable isn't
     * enough, though (see [ENTER_READY_MS] and [REAPPEAR_READY_MS]). With [afterDrag], this first waits for the hide
     * that follows a drag, if it comes within [HIDE_START_MS].
     */
    private fun awaitDividersReady(afterDrag: Boolean) {
        val start = SystemClock.uptimeMillis()
        var sawHidden = false
        while (SystemClock.uptimeMillis() - start < DIVIDER_WAIT_MS) {
            val dump = dumpsys(listOf("input")) ?: return
            val states = DIVIDER_INPUT.findAll(dump).map { !it.groupValues[1].contains("NOT_VISIBLE") }.toList()
            val now = SystemClock.uptimeMillis()
            if (states.size < 2 || !states.all { it }) {
                sawHidden = true
            } else if (!afterDrag) {
                SystemClock.sleep((lastSplitCommandAt + ENTER_READY_MS - now).coerceAtLeast(0))
                return
            } else if (sawHidden || now - start > HIDE_START_MS) {
                SystemClock.sleep(REAPPEAR_READY_MS)
                return
            }
            SystemClock.sleep(DIVIDER_POLL_MS)
        }
        Log.w(TAG, "Dividers didn't become touchable in time")
    }

    /** [coerceIn], or null if the range is empty (the panes are too small for the requested sizes). */
    private fun Int.clampOrNull(min: Int, max: Int): Int? = if (min > max) null else coerceIn(min, max)

    /** The three columns left to right, if the split really is three side-by-side columns. */
    private fun columns(stages: Map<String, Pane>): Triple<Pane, Pane, Pane>? {
        val a = stages["side"] ?: return null
        val b = stages["main"] ?: return null
        val c = stages["cell"] ?: return null
        val sideBySide = a.left >= 0 && a.right < b.left && b.right < c.left && a.top == b.top && b.top == c.top
        return if (sideBySide) Triple(a, b, c) else null
    }

    /** Current bounds of the split stages on the main display, by stage name. */
    private fun readStages(): Map<String, Pane>? {
        val dump = dumpsys(listOf("activity", "containers")) ?: return null
        val stages = mutableMapOf<String, Pane>()
        STAGE_BOUNDS.findAll(dump).forEach { m ->
            val (name, l, t, r, b) = m.destructured
            stages.putIfAbsent(name, Pane(l.toInt(), t.toInt(), r.toInt(), b.toInt()))
        }
        return stages
    }

    /**
     * `startIntents p1 p2 <splitDivision>` or `startIntents p1 p2 p3 <splitDivision> <parallelMultiSplit>`. SystemUI
     * puts p2 on the left, then p1, then p3, so the first two are swapped to keep the given left-to-right order.
     * Division 0 with parallel=true gives three side-by-side columns.
     */
    private fun startIntents(packages: List<String>): Boolean {
        val ordered = listOf(packages[1], packages[0]) + packages.drop(2)
        val tail = if (ordered.size == 3) listOf("0", "true") else listOf("0")
        return wmShell("startIntents", *(ordered + tail).toTypedArray())
    }

    /**
     * Packages whose main process is running right now, from the process LRU list: small and fast (~20ms), unlike
     * `dumpsys activity processes` (~1.5MB). This runs before anything launches, so it must stay cheap.
     */
    private fun runningPackages(): Set<String> {
        val dump = dumpsys(listOf("activity", "lru")) ?: return emptySet()
        // "  #201: fg    TOP  LCMNFUATI 1234:com.example.app/u0a123 act:..."; ":"-suffixed names are secondary processes.
        return PROCESS_LINE.findAll(dump).map { it.groupValues[1] }.toSet()
    }

    /**
     * The most recent task of each app for this user, in order, or null if any app has none. Matched on the task's base
     * component, so another task of the same package (say, a Chrome web app) isn't taken for the app itself.
     */
    private fun findTasks(apps: List<ComponentName>): List<Int>? {
        val dump = dumpsys(listOf("activity", "recents")) ?: return null
        val myUser = Process.myUserHandle().hashCode()
        val found = mutableMapOf<ComponentName, Int>()
        // Each task's block starts at its "* Recent #N" line and runs to the next one.
        val starts = RECENT_TASK.findAll(dump).toList()
        starts.forEachIndexed { i, match ->
            val type = match.groupValues[2]
            if (type == "home" || type == "recents") return@forEachIndexed
            val block = dump.substring(match.range.first, starts.getOrNull(i + 1)?.range?.first ?: dump.length)
            val user = TASK_USER.find(block)?.groupValues?.get(1)?.toIntOrNull()
            val component = TASK_COMPONENT.find(block)?.groupValues?.get(1)?.let(ComponentName::unflattenFromString)
            if (user == myUser && component != null && component !in found) found[component] = match.groupValues[1].toInt()
        }
        return apps.map { found[it] ?: return null }
    }

    /** Waits up to [TASK_WAIT_MS] for every package's top activity to be in a split pane. */
    private fun awaitInSplit(packages: List<String>): Boolean {
        val deadline = SystemClock.uptimeMillis() + TASK_WAIT_MS
        val user = Process.myUserHandle().hashCode()
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            val dump = dumpsys(listOf("activity", "containers")) ?: return false
            val inSplit = packages.all { pkg ->
                // The first record of a package is its topmost activity.
                val record = dump.lineSequence().firstOrNull { it.contains("ActivityRecord{") && it.contains(" u$user $pkg/") }
                record != null && record.contains("mode=MULTI-WINDOW")
            }
            if (inSplit) return true
        }
        return false
    }

    /** When the last WMShell split command returned (uptime ms). */
    @Volatile
    private var lastSplitCommandAt = 0L

    private fun wmShell(command: String, vararg args: String): Boolean {
        val output = dumpsys(listOf("activity", "service", "SystemUIService", "WMShell", "splitscreen", command) + args)
            ?: return false
        lastSplitCommandAt = SystemClock.uptimeMillis()
        val failed = listOf("Permission Denial", "Invalid command", "Error:", "Exception").any { output.contains(it) } ||
            !output.contains("WMShell")
        if (failed) Log.w(TAG, "$command failed: ${output.take(500)}")
        return !failed
    }

    /** Kills dumpsys runs that hang. One shared thread instead of one per call: launches poll dumpsys a lot. */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dumpsys-watchdog").apply { isDaemon = true }
    }

    /**
     * A dumpsys run that hung and was killed. Its command may still have been carried out, so callers must treat
     * this as "unknown", never as "failed".
     */
    private class DumpsysTimeout : RuntimeException("dumpsys timed out")

    /** Runs dumpsys and returns its output, or null if it couldn't run. Throws [DumpsysTimeout] if it hangs. */
    private fun dumpsys(args: List<String>): String? {
        val process = try {
            ProcessBuilder(listOf("dumpsys") + args).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.e(TAG, "Could not run dumpsys", e)
            return null
        }
        // readText() returns only when the process ends, so a hung dumpsys is killed from the watchdog.
        val timedOut = AtomicBoolean(false)
        val kill = watchdog.schedule({
            timedOut.set(true)
            process.destroyForcibly()
        }, DUMPSYS_TIMEOUT_S, TimeUnit.SECONDS)
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ""
        } finally {
            kill.cancel(false)
        }
        if (timedOut.get()) throw DumpsysTimeout()
        return output
    }
}
