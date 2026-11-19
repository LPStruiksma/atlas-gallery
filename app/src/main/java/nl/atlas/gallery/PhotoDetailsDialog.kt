package nl.atlas.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Read-only, scrollable Settings > Advanced > Photo details explanation. */
@Composable
internal fun PhotoDetailsDialog(content: PhotoDetailsContent, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Photo details") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("How many of your photos are missing details Android normally stores. " +
                    "Missing details don't change your photos.")
                Text(content.summary, style = MaterialTheme.typography.bodyMedium)
                content.rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleSmall)
                        Text(row.countText, style = MaterialTheme.typography.bodyMedium)
                        Text(row.note, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                content.skippedNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        modifier = Modifier.testTag("atlas-people-details-dialog")
    )
}
