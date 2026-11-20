package nl.atlas.gallery

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * #675: the in-app changelog. `scripts/atlas_release.py` (#674) writes
 * `assets/atlas_changelog.json` into each signed release from `changelog.d/`;
 * debug and test builds have none and show the empty state. Read-only, local,
 * no preference and no network.
 */
@Immutable
data class AtlasChangelog(val releases: List<Release>) {
    @Immutable
    data class Release(val version: String, val build: Long?, val date: LocalDate?,
                       val groups: List<Group>)

    @Immutable
    data class Group(val type: String, val title: String, val entries: List<String>)

    companion object {
        const val ASSET = "atlas_changelog.json"
        private val GROUP_ORDER = listOf("major", "feature", "improvement", "fix")
        private const val MAX_RELEASES = 500
        private const val MAX_BYTES = 512 * 1024

        /** Null when the text is not a schema-1 changelog; bad entries are skipped. */
        fun parse(text: String): AtlasChangelog? = runCatching {
            val root = JSONObject(text)
            if (root.optInt("schema") != 1) return null
            val list = root.getJSONArray("releases")
            val releases = (0 until minOf(list.length(), MAX_RELEASES)).mapNotNull { i ->
                val r = list.optJSONObject(i) ?: return@mapNotNull null
                val version = r.optString("version").takeIf {
                    Regex("""\d{1,4}\.\d{1,4}\.\d{1,5}""").matches(it)
                } ?: return@mapNotNull null
                val groupsJson = r.optJSONArray("groups")
                val groups = (0 until (groupsJson?.length() ?: 0)).mapNotNull { g ->
                    val o = groupsJson!!.optJSONObject(g) ?: return@mapNotNull null
                    val type = o.optString("type")
                    if (type !in GROUP_ORDER) return@mapNotNull null
                    val entriesJson = o.optJSONArray("entries") ?: return@mapNotNull null
                    val entries = (0 until entriesJson.length())
                        .mapNotNull { e -> entriesJson.optString(e).trim().takeIf { it.isNotEmpty() } }
                    if (entries.isEmpty()) null
                    else Group(type, o.optString("title").ifBlank { defaultTitle(type) }, entries)
                }.sortedBy { GROUP_ORDER.indexOf(it.type) }
                Release(version, r.optLong("build").takeIf { it > 0 },
                    runCatching { LocalDate.parse(r.optString("date")) }.getOrNull(), groups)
            }
            AtlasChangelog(releases)
        }.getOrNull()

        fun load(context: Context): AtlasChangelog? = runCatching {
            context.assets.open(ASSET).use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_BYTES) null else parse(bytes.toString(Charsets.UTF_8))
            }
        }.getOrNull()

        private fun defaultTitle(type: String) = when (type) {
            "major" -> "Big changes"
            "feature" -> "New"
            "improvement" -> "Improved"
            else -> "Fixed"
        }
    }
}

private val WHATS_NEW_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

internal fun whatsNewSubline(release: AtlasChangelog.Release): String? = listOfNotNull(
    release.date?.format(WHATS_NEW_DATE),
    release.build?.let { "build $it" }
).joinToString(", ").ifEmpty { null }

/** Value for the Settings › About row. */
internal fun whatsNewRowValue(changelog: AtlasChangelog?): String =
    changelog?.releases?.firstOrNull()?.version ?: "None in this build"

@Composable
internal fun AtlasWhatsNewScreen(
    changelog: AtlasChangelog?,
    installedVersion: String,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("atlas-whats-new-screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("atlas-whats-new-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text("What's new", Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        val releases = changelog?.releases.orEmpty()
        if (releases.isEmpty()) {
            Text("This build has no release notes. Versions installed from Obtainium include them.",
                Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 16.dp)
                    .testTag("atlas-whats-new-empty"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("atlas-whats-new-list"),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            releases.forEach { release ->
                WhatsNewRelease(release, installed = release.version == installedVersion)
            }
        }
    }
}

@Composable
private fun WhatsNewRelease(release: AtlasChangelog.Release, installed: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("atlas-whats-new-release-${release.version}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    // Quiet Frame held sphere: the current item. TalkBack gets words, not colour.
                    Box(Modifier.size(12.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "Installed version" }
                        .testTag("atlas-whats-new-installed"))
                    Spacer(Modifier.width(10.dp))
                }
                Text("Version ${release.version}", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
            }
            whatsNewSubline(release)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (release.groups.isEmpty()) {
                Text("Small fixes and maintenance.", style = MaterialTheme.typography.bodyLarge)
            }
            release.groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.title, Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    group.entries.forEach { entry ->
                        Row {
                            Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant))
                            Spacer(Modifier.width(12.dp))
                            Text(entry, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
