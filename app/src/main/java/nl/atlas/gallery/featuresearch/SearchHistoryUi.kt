package nl.atlas.gallery.featuresearch

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private object SearchHistoryStoreHolder {
    @Volatile private var store: SearchHistoryStore? = null

    fun get(context: Context): SearchHistoryStore = store ?: synchronized(this) {
        store ?: run {
            val app = context.applicationContext
            // filesDir is resolved on the I/O thread inside the file seam.
            SearchHistoryStore(SearchHistoryFile.atomic {
                File(app.filesDir, "search-history-v1.txt")
            }).also { store = it }
        }
    }
}

/** One store per process, so rotation or a new Activity keeps the same history. */
fun searchHistoryStoreFor(context: Context): SearchHistoryStore = SearchHistoryStoreHolder.get(context)

/**
 * The live history for the UI: reads [state], writes through [store] on I/O,
 * and offers Undo on the shared [snackbar]. Undo snackbars hold no query text.
 */
@Stable
class SearchHistoryController(
    private val store: SearchHistoryStore,
    private val stateHolder: State<SearchHistoryState>,
    private val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    /** Opaque keys of people who currently have a name (null: none known yet). */
    private val namedPersonKeys: () -> Set<String>
) {
    val state: SearchHistoryState get() = stateHolder.value

    private fun change(op: (SearchHistoryState) -> SearchHistoryState) {
        if (store.update(op)) scope.launch(Dispatchers.IO) { store.flush() }
    }

    /** Shown pills for [scope]: hides any person who is no longer named. */
    fun visible(scope: SearchHistoryScope): List<SearchHistoryEntry> {
        val named = namedPersonKeys()
        return state.shown(scope).filter { entry -> entry.personKeys.all { it in named } }
    }

    fun remember(scope: SearchHistoryScope, entry: SearchHistoryEntry) =
        change { it.remember(scope, entry) }

    fun remove(scope: SearchHistoryScope, entry: SearchHistoryEntry) =
        withUndo("Removed") { it.remove(scope, entry) }

    fun clear(scope: SearchHistoryScope) = withUndo("Recent searches cleared") { it.clear(scope) }

    fun clearAll() = withUndo("Search history cleared") { it.clearAll() }

    fun setEnabled(on: Boolean) {
        if (on) change { it.withEnabled(true) } else withUndo("Search history off") { it.withEnabled(false) }
    }

    fun onGrant(level: SearchGrantLevel) = change { it.onGrant(level) }

    private fun withUndo(message: String, op: (SearchHistoryState) -> SearchHistoryState) {
        val before = store.state.value
        if (!store.update(op) && store.state.value == before) return
        scope.launch(Dispatchers.IO) { store.flush() }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo",
                withDismissAction = false, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) change { it.restore(before) }
        }
    }
}

/** Provided once by MainActivity; null (tests, previews) means no history UI. */
val LocalSearchHistory = staticCompositionLocalOf<SearchHistoryController?> { null }

/**
 * Undo messages for history actions, drawn over the app (not in the
 * Scaffold slot, which ignores the keyboard): it sits right above an open
 * IME, and [bottomClearance] above the navigation bar otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchHistorySnackbarHost(
    controller: SearchHistoryController?,
    modifier: Modifier = Modifier,
    /** Space kept free above the navigation bar while the keyboard is closed. */
    bottomClearance: Dp = 0.dp
) {
    if (controller == null) return
    val imeOpen = WindowInsets.isImeVisible
    SnackbarHost(controller.snackbar,
        modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(bottom = if (imeOpen) 8.dp else bottomClearance)
            .testTag("atlas-search-history-snackbar"))
}

/**
 * #134 + FIND-3a: "Recent searches" header with Clear (this scope), then up to
 * ten 32 dp pills that wrap; each pill has a trailing ✕ to remove it.
 * Query text never goes into a saveable key or saved-state Bundle here.
 */
@Composable
fun RecentSearchPills(
    entries: List<SearchHistoryEntry>,
    labelOf: (SearchHistoryEntry) -> String,
    onPick: (SearchHistoryEntry) -> Unit,
    onRemove: (SearchHistoryEntry) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("atlas-search-recents"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            SearchSectionHeader("Recent searches")
            TextButton(onClick = onClear,
                modifier = Modifier.testTag("atlas-search-recents-clear")) { Text("Clear") }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entries.take(SearchHistoryState.SHOWN).forEach { entry ->
                val label = labelOf(entry)
                InputChip(
                    selected = false,
                    onClick = { onPick(entry) },
                    label = { Text(label, maxLines = 1) },
                    trailingIcon = {
                        // The ✕ fills the chip height so it is easy to hit
                        // without the chip growing past #134's 32 dp.
                        Box(Modifier.size(width = 32.dp, height = 32.dp)
                            .clickable(role = Role.Button) { onRemove(entry) }
                            .semantics {
                                contentDescription = "Remove “$label” from recent searches"
                            }
                            .testTag("atlas-search-recent-remove"),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, null, Modifier.size(InputChipDefaults.IconSize))
                        }
                    },
                    shape = SearchChipShape,
                    modifier = Modifier.testTag("atlas-search-recent")
                )
            }
        }
    }
}
