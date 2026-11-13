package wtf.buddyworks.multitasker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Invisible trampoline behind every home screen shortcut: opens the shortcut's apps, then goes away. */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Don't relaunch the apps when the activity is recreated. Folding no longer does that (see configChanges in
        // the manifest), but other config changes still could.
        if (savedInstanceState != null) return finish()
        open(intent)
    }

    /**
     * A shortcut tapped again while this is still open (singleTask) lands here instead of replacing the activity,
     * which would leave the running launch with a destroyed host. [SplitLauncher.launch] ignores it if busy.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (SplitLauncher.isBusy) {
            // The tap brought this (invisible) activity to the front, on top of the apps being opened. One UI won't
            // put an adjacent launch next to its own source, so step back behind them and let the launch finish.
            moveTaskToBack(true)
            return
        }
        open(intent)
    }

    private fun open(intent: Intent) {
        val requested = intent.getStringArrayExtra(EXTRA_APPS).orEmpty().mapNotNull(ComponentName::unflattenFromString)
        val installed = requested.filter { isLaunchable(it) }
        if (installed.size < requested.size) {
            Toast.makeText(this, R.string.apps_missing, Toast.LENGTH_LONG).show()
        }
        if (installed.isEmpty()) {
            finish()
            return
        }

        val config = SplitConfig(
            apps = installed,
            leftPercent = intent.getIntExtra(EXTRA_LEFT_PERCENT, SplitConfig.DEFAULT_LEFT),
            rightPercent = intent.getIntExtra(EXTRA_RIGHT_PERCENT, SplitConfig.DEFAULT_RIGHT),
            stackPercent = intent.getIntExtra(EXTRA_STACK_PERCENT, SplitConfig.DEFAULT_STACK),
            layout = intent.getStringExtra(EXTRA_LAYOUT)
                ?.let { name -> ThreeAppLayout.entries.firstOrNull { it.name == name } }
                ?: ThreeAppLayout.COLUMNS,
        )
        SplitLauncher.launch(this, config) {
            if (!isFinishing) finishAndRemoveTask()
        }
    }

    private fun isLaunchable(app: ComponentName): Boolean = try {
        packageManager.getActivityInfo(app, 0).isEnabled
    } catch (_: Exception) {
        false
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        const val ACTION_LAUNCH = "wtf.buddyworks.multitasker.LAUNCH_SPLIT"
        const val EXTRA_APPS = "apps"
        const val EXTRA_LEFT_PERCENT = "left_percent"
        const val EXTRA_RIGHT_PERCENT = "right_percent"
        const val EXTRA_STACK_PERCENT = "stack_percent"
        const val EXTRA_LAYOUT = "layout"

        /**
         * Shortcut extras are persisted as a PersistableBundle, so only strings and ints here (no floats). No
         * FLAG_ACTIVITY_CLEAR_TASK: a second tap must reach the running instance, not destroy it.
         */
        fun intent(context: Context, config: SplitConfig): Intent =
            Intent(context, LaunchActivity::class.java)
                .setAction(ACTION_LAUNCH)
                .putExtra(EXTRA_APPS, config.apps.map { it.flattenToString() }.toTypedArray())
                .putExtra(EXTRA_LEFT_PERCENT, config.leftPercent)
                .putExtra(EXTRA_RIGHT_PERCENT, config.rightPercent)
                .putExtra(EXTRA_STACK_PERCENT, config.stackPercent)
                .putExtra(EXTRA_LAYOUT, config.layout.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
