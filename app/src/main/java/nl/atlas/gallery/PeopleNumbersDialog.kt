package nl.atlas.gallery

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featurepeople.identity.PeopleNumbers

/**
 * #580 People ⋮ → People numbers: how the faces are grouped and how often the
 * owner had to correct a group, as counts. "Copy" puts [PeopleNumbers.line]
 * on the clipboard: one line of numbers, no names, no photos, nothing sent
 * anywhere by the app.
 */
@Composable
internal fun PeopleNumbersRoute(
    host: PeopleDiscoveryHost,
    galleryGrant: String?,
    onClose: () -> Unit
) {
    val version by host.changes.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf<PeopleNumbers?>(null) }
    LaunchedEffect(galleryGrant, version) {
        numbers = withContext(Dispatchers.Default) { host.peopleNumbers(galleryGrant) }
        loaded = true
    }
    PeopleNumbersDialog(numbers, loaded, onClose)
}

@Composable
internal fun PeopleNumbersDialog(numbers: PeopleNumbers?, loaded: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("People numbers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    numbers != null -> {
                        numbers.summary().forEach { Text(it) }
                        Text("Copy gives these numbers as one line. No names or photos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    !loaded -> CircularProgressIndicator(
                        Modifier.size(28.dp).testTag("atlas-people-numbers-loading"))
                    else -> Text("No numbers yet. They appear once faces have been grouped.")
                }
            }
        },
        confirmButton = {
            if (numbers != null) TextButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("ATLAS People numbers", numbers.line()))
                },
                modifier = Modifier.testTag("atlas-people-numbers-copy")
            ) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onClose,
                modifier = Modifier.testTag("atlas-people-numbers-close")) { Text("Close") }
        },
        modifier = Modifier.testTag("atlas-people-numbers")
    )
}
