@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vestifeed.db.table.ConfTable
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** The reading width of the screen's content, centered on wide (desktop) windows. */
private val ContentWidth = 720.dp

/**
 * Settings, grouped into labelled cards: each switch is a row whose whole
 * surface toggles it, and the account card is read-only info followed by the
 * log-out action.
 */
@Composable
fun SettingsScreen(state: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsSection("Reading") {
                LayoutSetting(
                    selected = state.conf.entriesView,
                    onSelect = { view -> state.updateConf { conf -> conf.copy(entriesView = view) } },
                )
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Image,
                    label = "Show preview images",
                    checked = state.conf.showPreviewImages,
                ) { state.updateConf { conf -> conf.copy(showPreviewImages = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Crop,
                    label = "Crop preview images",
                    checked = state.conf.cropPreviewImages,
                ) { state.updateConf { conf -> conf.copy(cropPreviewImages = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Notes,
                    label = "Show preview text",
                    checked = state.conf.showPreviewText,
                ) { state.updateConf { conf -> conf.copy(showPreviewText = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Person,
                    label = "Show author name",
                    checked = state.conf.showAuthorName,
                ) { state.updateConf { conf -> conf.copy(showAuthorName = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.OpenInBrowser,
                    label = "Use built-in browser",
                    checked = state.conf.useBuiltInBrowser,
                ) { state.updateConf { conf -> conf.copy(useBuiltInBrowser = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Headphones,
                    label = "Use built-in audio player",
                    checked = state.conf.useBuiltInAudioPlayer,
                ) { state.updateConf { conf -> conf.copy(useBuiltInAudioPlayer = it) } }
            }

            SettingsSection("Sync") {
                SettingSwitch(
                    glyph = MaterialSymbols.Sync,
                    label = "Sync on startup",
                    checked = state.conf.syncOnStartup,
                ) { state.updateConf { conf -> conf.copy(syncOnStartup = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Schedule,
                    label = "Sync in background",
                    checked = state.conf.syncInBackground,
                ) { state.updateConf { conf -> conf.copy(syncInBackground = it) } }
            }

            SettingsSection("Tabs") {
                SettingSwitch(
                    glyph = MaterialSymbols.Label,
                    label = "Show tags tab",
                    checked = state.conf.showTagsTab,
                ) { state.updateConf { conf -> conf.copy(showTagsTab = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Podcasts,
                    label = "Show podcasts tab",
                    checked = state.conf.showPodcastsTab,
                ) { state.updateConf { conf -> conf.copy(showPodcastsTab = it) } }
            }

            SettingsSection("Account") {
                InfoRow(
                    glyph = MaterialSymbols.Dns,
                    label = "Backend",
                    value = state.conf.backend?.name ?: "Not configured",
                )
                state.conf.minifluxUrl?.let {
                    SettingDivider()
                    InfoRow(glyph = MaterialSymbols.Link, label = "Miniflux URL", value = it)
                }
            }

            OutlinedButton(
                onClick = { state.logout() },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                MaterialSymbol(MaterialSymbols.Logout, contentDescription = null, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("Log out")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** A divider indented to line up with a row's text (past the leading icon). */
@Composable
private fun SettingDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp))
}

@Composable
private fun LayoutSetting(
    selected: ConfTable.EntriesView,
    onSelect: (ConfTable.EntriesView) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbol(
                glyph = MaterialSymbols.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text("Entry layout", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selected == ConfTable.EntriesView.List,
                onClick = { onSelect(ConfTable.EntriesView.List) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    MaterialSymbol(MaterialSymbols.ViewList, contentDescription = null, size = 18.sp)
                },
            ) { Text("List") }
            SegmentedButton(
                selected = selected == ConfTable.EntriesView.Cards,
                onClick = { onSelect(ConfTable.EntriesView.Cards) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    MaterialSymbol(MaterialSymbols.GridView, contentDescription = null, size = 18.sp)
                },
            ) { Text("Cards") }
        }
    }
}

@Composable
private fun SettingSwitch(
    glyph: String,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        leadingContent = {
            MaterialSymbol(
                glyph = glyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun InfoRow(glyph: String, label: String, value: String) {
    ListItem(
        leadingContent = {
            MaterialSymbol(
                glyph = glyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}
