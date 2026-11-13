package wtf.buddyworks.multitasker.ui

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import wtf.buddyworks.multitasker.LaunchActivity
import wtf.buddyworks.multitasker.LaunchableApp
import wtf.buddyworks.multitasker.MAX_APPS
import wtf.buddyworks.multitasker.MIN_APPS
import wtf.buddyworks.multitasker.PaneGeometry
import wtf.buddyworks.multitasker.R
import wtf.buddyworks.multitasker.SplitConfig
import wtf.buddyworks.multitasker.ThreeAppLayout
import wtf.buddyworks.multitasker.MainViewModel
import wtf.buddyworks.multitasker.ShortcutFactory
import wtf.buddyworks.multitasker.UiState

private val ADB_COMMANDS = """
    adb shell pm grant wtf.buddyworks.multitasker android.permission.DUMP
    adb shell pm grant wtf.buddyworks.multitasker android.permission.PACKAGE_USAGE_STATS
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val onToggle: (LaunchableApp) -> Unit = { app ->
        if (!viewModel.toggle(app)) say(resources.getQuantityString(R.plurals.max_apps_reached, MAX_APPS, MAX_APPS))
    }
    val onTry: () -> Unit = { context.startActivity(LaunchActivity.intent(context, state.config)) }
    val onCreate: () -> Unit = {
        when {
            !ShortcutFactory.isSupported(context) -> say(resources.getString(R.string.shortcuts_unsupported))
            ShortcutFactory.requestPin(context, state.shortcutName, state.selected, state.config) -> say(resources.getString(R.string.shortcut_confirm, state.shortcutName))
            else -> say(resources.getString(R.string.shortcut_refused))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 720.dp) {
                // Unfolded: settings on the left, app picker on the right.
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Composer(state, viewModel, onTry, onCreate)
                    }
                    AppPicker(
                        state = state,
                        onQuery = viewModel::setQuery,
                        onToggle = onToggle,
                        modifier = Modifier.weight(0.55f).fillMaxHeight(),
                        header = null,
                    )
                }
            } else {
                // Folded (cover screen): everything in one scrolling column.
                AppPicker(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onToggle = onToggle,
                    modifier = Modifier.fillMaxSize(),
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Composer(state, viewModel, onTry, onCreate)
                        }
                    },
                )
            }
        }
    }
}

/** Preview, name and actions for the shortcut being built, plus the true-split setup card. */
@Composable
private fun Composer(state: UiState, viewModel: MainViewModel, onTry: () -> Unit, onCreate: () -> Unit) {
    SplitPreview(state, onMove = viewModel::move, onRemove = viewModel::remove)

    if (state.usesTrueSplit && state.selected.size >= MIN_APPS) {
        SizeControls(state, viewModel)
    }

    OutlinedTextField(
        value = state.customName,
        onValueChange = viewModel::setName,
        label = { Text(stringResource(R.string.shortcut_name_label)) },
        placeholder = { Text(state.autoName.ifBlank { stringResource(R.string.shortcut_name_placeholder) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = if (state.customName.isBlank() && state.autoName.isNotBlank()) {
            { Text(stringResource(R.string.shortcut_name_hint, state.autoName), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onTry, enabled = state.canCreate, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.try_it))
        }
        Button(onClick = onCreate, enabled = state.canCreate, modifier = Modifier.weight(1.4f)) {
            Icon(Icons.Default.AddToHomeScreen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_to_home), maxLines = 1)
        }
    }

    TrueSplitCard(
        state,
        onEnabledChange = viewModel::setTrueSplitEnabled,
        onResizeTwiceChange = viewModel::setResizeColdAppsTwice,
    )
}

/** A sketch of the unfolded screen showing where each selected app will open. */
@Composable
private fun SplitPreview(state: UiState, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit) {
    val selected = state.selected
    val popupThird = selected.size == 3 && !state.usesTrueSplit
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().aspectRatio(2448f / 1848f),
    ) {
        BoxWithConstraints(Modifier.padding(8.dp)) {
            if (selected.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.preview_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.preview_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                return@BoxWithConstraints
            }
            // The split panes, positioned as the shortcut will open them. With one app picked, the second pane is
            // an empty slot; in pop-up mode the third app floats over a 2-app split.
            val config = when {
                selected.size == 1 -> SplitConfig(List(2) { selected[0].component })
                popupThird -> SplitConfig(selected.take(2).map { it.component })
                else -> state.config
            }
            val half = 3.dp
            PaneGeometry.panes(config).forEachIndexed { i, pane ->
                val startInset = if (pane.left > 0f) half else 0.dp
                val endInset = if (pane.right < 1f) half else 0.dp
                val topInset = if (pane.top > 0f) half else 0.dp
                val bottomInset = if (pane.bottom < 1f) half else 0.dp
                val paneModifier = Modifier
                    .offset(maxWidth * pane.left + startInset, maxHeight * pane.top + topInset)
                    .size(
                        maxWidth * (pane.right - pane.left) - startInset - endInset,
                        maxHeight * (pane.bottom - pane.top) - topInset - bottomInset,
                    )
                if (selected.size == 1 && i == 1) {
                    EmptyPane(paneModifier)
                } else {
                    AppPane(selected[i], index = i, count = selected.size, onMove = onMove, onRemove = onRemove, modifier = paneModifier)
                }
            }
            if (popupThird) {
                AppPane(
                    app = selected[2],
                    index = 2,
                    count = 3,
                    onMove = onMove,
                    onRemove = onRemove,
                    floating = true,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.42f).fillMaxHeight(0.5f),
                )
            }
        }
    }
}

/** Layout choice for three apps and the size sliders. True split mode only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeControls(state: UiState, viewModel: MainViewModel) {
    val colors = MaterialTheme.colorScheme
    val config = state.config
    val threeApps = state.selected.size == 3
    val reset = stringResource(R.string.action_reset)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (threeApps) {
            val options = listOf(
                ThreeAppLayout.COLUMNS to stringResource(R.string.layout_columns),
                ThreeAppLayout.BIG_LEFT to stringResource(R.string.layout_big_left),
                ThreeAppLayout.BIG_RIGHT to stringResource(R.string.layout_big_right),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                options.forEachIndexed { i, (layout, label) ->
                    SegmentedButton(
                        selected = state.layout == layout,
                        onClick = { viewModel.setLayout(layout) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }

        if (config.isColumns) {
            val left = config.leftPercent
            val right = config.rightPercent
            SliderHeader(
                text = stringResource(
                    R.string.sizes_columns,
                    state.selected[0].label, left,
                    state.selected[1].label, right - left,
                    state.selected[2].label, 100 - right,
                ),
                enabled = state.canSizeThreePanes,
                action = when {
                    !state.canSizeThreePanes -> null
                    config.hasDefaultColumns -> stringResource(R.string.action_equal) to { viewModel.setColumns(33, 67) }
                    else -> reset to viewModel::resetColumns
                },
            )
            RangeSlider(
                value = left.toFloat()..right.toFloat(),
                onValueChange = { viewModel.setColumns(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                valueRange = 0f..100f,
                enabled = state.canSizeThreePanes,
            )
        } else {
            val percent = config.leftPercent
            SliderHeader(
                text = stringResource(R.string.sizes_left_right, percent, 100 - percent),
                enabled = true,
                action = if (percent != SplitConfig.DEFAULT_LEFT) reset to { viewModel.setSplitPercent(SplitConfig.DEFAULT_LEFT) } else null,
            )
            Slider(
                value = percent.toFloat(),
                onValueChange = { viewModel.setSplitPercent(it.roundToInt()) },
                valueRange = SplitConfig.MIN_WIDTH.toFloat()..SplitConfig.MAX_WIDTH.toFloat(),
            )
        }

        if (config.isStacked) {
            val stack = config.stackPercent
            SliderHeader(
                text = stringResource(R.string.sizes_top_bottom, stack, 100 - stack),
                enabled = state.canSizeThreePanes,
                action = if (state.canSizeThreePanes && stack != SplitConfig.DEFAULT_STACK) {
                    reset to { viewModel.setStackPercent(SplitConfig.DEFAULT_STACK) }
                } else {
                    null
                },
            )
            Slider(
                value = stack.toFloat(),
                onValueChange = { viewModel.setStackPercent(it.roundToInt()) },
                valueRange = SplitConfig.MIN_STACK.toFloat()..SplitConfig.MAX_STACK.toFloat(),
                enabled = state.canSizeThreePanes,
            )
        }

        if (threeApps && !state.dividerServiceEnabled) {
            DividerServicePrompt()
        } else if (threeApps) {
            Text(
                stringResource(R.string.divider_info, SplitConfig.MIN_WIDTH, SplitConfig.MIN_STACK),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SliderHeader(text: String, enabled: Boolean, action: Pair<String, () -> Unit>?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

/** Explains why 3-app sizes are locked and opens the system page where the divider service is switched on. */
@Composable
private fun DividerServicePrompt() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.divider_prompt, stringResource(R.string.divider_service_label)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSecondaryContainer,
            )
            TextButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.open_accessibility_settings))
            }
        }
    }
}


@Composable
private fun AppPane(
    app: LaunchableApp,
    index: Int,
    count: Int,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (floating) colors.secondaryContainer else colors.surface,
        border = if (floating) BorderStroke(2.dp, colors.secondary) else null,
        modifier = modifier.then(if (floating) Modifier.shadow(12.dp, RoundedCornerShape(14.dp)) else Modifier),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(app.image, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (floating) {
                    Text(stringResource(R.string.popup_badge), style = MaterialTheme.typography.labelSmall, color = colors.onSecondaryContainer)
                }
            }
            IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_app, app.label), modifier = Modifier.size(18.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter)) {
                if (index > 0) {
                    IconButton(onClick = { onMove(index, -1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.move_app_left, app.label))
                    }
                }
                if (index < count - 1) {
                    IconButton(onClick = { onMove(index, 1) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.move_app_right, app.label))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPane(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, colors.outlineVariant, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.add_an_app), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
}

/** Shows whether the third app gets a real split pane, and how to unlock it if it doesn't. */
@Composable
private fun TrueSplitCard(state: UiState, onEnabledChange: (Boolean) -> Unit, onResizeTwiceChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.trueSplitAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.true_split_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.trueSplitEnabled) {
                                stringResource(R.string.true_split_on)
                            } else {
                                stringResource(R.string.true_split_off)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.trueSplitEnabled, onCheckedChange = onEnabledChange)
                }
                Text(
                    stringResource(R.string.true_split_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (state.canSizeThreePanes) {
                    // Workaround for apps that miss their first resize after a cold start. See Prefs.resizeColdAppsTwice.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.resize_twice_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.resize_twice_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.resizeColdAppsTwice, onCheckedChange = onResizeTwiceChange)
                    }
                }
            } else {
                Text(stringResource(R.string.popup_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.grant_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                CommandBlock(ADB_COMMANDS)
                Text(
                    stringResource(R.string.grant_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommandBlock(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val clipLabel = stringResource(R.string.adb_commands_clip_label)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHighest)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text))) } }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_commands), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AppPicker(
    state: UiState,
    onQuery: (String) -> Unit,
    onToggle: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)?,
) {
    val focusManager = LocalFocusManager.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 92.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(bottom = 16.dp)) { header() } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_apps)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        when {
            state.apps == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.filteredApps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.no_apps_match, state.query),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            else -> items(state.filteredApps, key = { it.component.flattenToString() }) { app ->
                val position = state.selected.indexOfFirst { it.component == app.component }
                AppTile(app, position, onClick = {
                    // Let go of the search field, or the keyboard comes back every time the app is reopened.
                    focusManager.clearFocus()
                    onToggle(app)
                })
            }
        }
    }
}

@Composable
private fun AppTile(app: LaunchableApp, position: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = position >= 0
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.primaryContainer else colors.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(app.image, contentDescription = null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape).background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("${position + 1}", color = colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
