package wtf.buddyworks.multitasker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_APPS = 3
const val MIN_APPS = 2

data class UiState(
    /** Null while loading. */
    val apps: List<LaunchableApp>? = null,
    val query: String = "",
    val selected: List<LaunchableApp> = emptyList(),
    /** What the user typed; blank means use [autoName]. */
    val customName: String = "",
    val trueSplitAvailable: Boolean = false,
    val trueSplitEnabled: Boolean = true,
    val resizeColdAppsTwice: Boolean = false,
    val dividerServiceEnabled: Boolean = false,
    val layout: ThreeAppLayout = ThreeAppLayout.COLUMNS,
    /** Left column width for 2 apps and the stacked layouts. */
    val splitPercent: Int = SplitConfig.DEFAULT_LEFT,
    /** The two divider positions of the three-column layout. */
    val columnLeft: Int = SplitConfig.DEFAULT_LEFT,
    val columnRight: Int = SplitConfig.DEFAULT_RIGHT,
    /** Top pane height of the stacked pair. */
    val stackPercent: Int = SplitConfig.DEFAULT_STACK,
) {
    val autoName: String get() = selected.joinToString(" + ") { it.label }
    val shortcutName: String get() = customName.ifBlank { autoName }
    val canCreate: Boolean get() = selected.size >= MIN_APPS
    val usesTrueSplit: Boolean get() = trueSplitAvailable && trueSplitEnabled

    /** Whether three-pane sizes can be set: they take true split plus the divider service. */
    val canSizeThreePanes: Boolean get() = usesTrueSplit && dividerServiceEnabled

    /**
     * What a shortcut made now would open. Sizes only exist in true split mode, and three-pane sizes only with the
     * divider service; whatever isn't available is left at One UI's defaults.
     */
    val config: SplitConfig
        get() {
            val apps = selected.map { it.component }
            if (!usesTrueSplit) return SplitConfig(apps)
            val full = SplitConfig(
                apps = apps,
                leftPercent = if (apps.size == 3 && layout == ThreeAppLayout.COLUMNS) columnLeft else splitPercent,
                rightPercent = columnRight,
                stackPercent = stackPercent,
                layout = layout,
            )
            return when {
                canSizeThreePanes || apps.size < 3 -> full
                full.isColumns -> full.copy(leftPercent = SplitConfig.DEFAULT_LEFT, rightPercent = SplitConfig.DEFAULT_RIGHT)
                else -> full.copy(stackPercent = SplitConfig.DEFAULT_STACK)
            }
        }

    val filteredApps: List<LaunchableApp>
        get() = apps.orEmpty().let { all ->
            if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        UiState(
            trueSplitEnabled = Prefs.trueSplitEnabled(application),
            resizeColdAppsTwice = Prefs.resizeColdAppsTwice(application),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** PackageManager change sequence at the last app list load. */
    private var packageSequence = 0

    /**
     * Loads the launcher apps the first time, and again only if packages were installed, updated or removed since:
     * loading draws every launcher icon, too much to repeat on every resume.
     */
    fun reloadAppsIfChanged() {
        val changes = getApplication<Application>().packageManager.getChangedPackages(packageSequence)
        if (changes != null) packageSequence = changes.sequenceNumber
        if (changes != null || _state.value.apps == null) reloadApps()
    }

    private fun reloadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.Default) { AppCatalog.load(getApplication()) }
            _state.update { s ->
                // Keep the selection, minus apps that were uninstalled meanwhile.
                val stillThere = s.selected.mapNotNull { sel -> apps.firstOrNull { it.component == sel.component } }
                s.copy(apps = apps, selected = stillThere)
            }
        }
    }

    /** Re-reads the ADB grant and the divider service state. Called whenever the screen comes back. */
    fun refreshCapabilities() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                trueSplitAvailable = SplitLauncher.isTrueSplitAvailable(app),
                dividerServiceEnabled = DividerService.isEnabled(app),
            )
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setName(name: String) = _state.update { it.copy(customName = name) }

    /** Selects or deselects [app]. Returns false if it could not be added because [MAX_APPS] are already picked. */
    fun toggle(app: LaunchableApp): Boolean {
        val s = _state.value
        if (s.selected.any { it.component == app.component }) {
            _state.update { it.copy(selected = it.selected.filterNot { sel -> sel.component == app.component }) }
            return true
        }
        if (s.selected.size >= MAX_APPS) return false
        _state.update { it.copy(selected = it.selected + app) }
        return true
    }

    fun remove(index: Int) = _state.update { s ->
        // The index comes from the UI and can be stale if the selection just changed (e.g. an app was uninstalled).
        if (index !in s.selected.indices) return@update s
        s.copy(selected = s.selected.toMutableList().apply { removeAt(index) })
    }

    /** Moves the app at [index] one place earlier (-1) or later (+1). */
    fun move(index: Int, delta: Int) = _state.update { s ->
        val target = index + delta
        if (target !in s.selected.indices) return@update s
        s.copy(selected = s.selected.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun setResizeColdAppsTwice(enabled: Boolean) {
        Prefs.setResizeColdAppsTwice(getApplication(), enabled)
        _state.update { it.copy(resizeColdAppsTwice = enabled) }
    }

    fun setTrueSplitEnabled(enabled: Boolean) {
        Prefs.setTrueSplitEnabled(getApplication(), enabled)
        _state.update { it.copy(trueSplitEnabled = enabled) }
    }

    fun setLayout(layout: ThreeAppLayout) = _state.update { it.copy(layout = layout) }

    fun setSplitPercent(percent: Int) = _state.update {
        it.copy(splitPercent = percent.coerceIn(SplitConfig.MIN_WIDTH, SplitConfig.MAX_WIDTH))
    }

    /** Sets both column dividers, keeping every column at least [SplitConfig.MIN_WIDTH] wide. */
    fun setColumns(left: Int, right: Int) = _state.update {
        val l = left.coerceIn(SplitConfig.MIN_WIDTH, 100 - 2 * SplitConfig.MIN_WIDTH)
        val r = right.coerceIn(l + SplitConfig.MIN_WIDTH, 100 - SplitConfig.MIN_WIDTH)
        it.copy(columnLeft = l, columnRight = r)
    }

    fun resetColumns() = _state.update { it.copy(columnLeft = SplitConfig.DEFAULT_LEFT, columnRight = SplitConfig.DEFAULT_RIGHT) }

    fun setStackPercent(percent: Int) = _state.update {
        it.copy(stackPercent = percent.coerceIn(SplitConfig.MIN_STACK, SplitConfig.MAX_STACK))
    }
}
