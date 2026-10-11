@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.vestifeed.db.table.ConfTable
import org.vestifeed.ui.AppState
import org.vestifeed.ui.i18n.SupportedLanguages
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.about
import org.vestifeed.ui.resources.account
import org.vestifeed.ui.resources.backend
import org.vestifeed.ui.resources.cards
import org.vestifeed.ui.resources.check_for_updates
import org.vestifeed.ui.resources.crop_feed_images
import org.vestifeed.ui.resources.entry_layout
import org.vestifeed.ui.resources.fetch_news_in_background
import org.vestifeed.ui.resources.fetch_news_on_startup
import org.vestifeed.ui.resources.language
import org.vestifeed.ui.resources.list
import org.vestifeed.ui.resources.log_out
import org.vestifeed.ui.resources.media
import org.vestifeed.ui.resources.miniflux_url
import org.vestifeed.ui.resources.not_available_on_this_platform
import org.vestifeed.ui.resources.not_configured
import org.vestifeed.ui.resources.reading
import org.vestifeed.ui.resources.show_author_name
import org.vestifeed.ui.resources.show_feed_images
import org.vestifeed.ui.resources.show_podcasts_tab
import org.vestifeed.ui.resources.show_preview_text
import org.vestifeed.ui.resources.show_tags_tab
import org.vestifeed.ui.resources.sync
import org.vestifeed.ui.resources.system_default
import org.vestifeed.ui.resources.tabs
import org.vestifeed.ui.resources.unknown
import org.vestifeed.ui.resources.use_built_in_audio_player
import org.vestifeed.ui.resources.use_built_in_browser
import org.vestifeed.ui.resources.version

/** The reading width of the screen's content, centered on wide (desktop) windows. */
private val ContentWidth = 720.dp

/**
 * Settings, grouped into labelled cards: each switch is a row whose whole
 * surface toggles it, and the account card is read-only info followed by the
 * log-out action.
 */
@Composable
fun SettingsScreen(state: AppState) {
    var languageDialogVisible by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsSection(stringResource(Res.string.language), isFirst = true) {
                SettingAction(
                    glyph = MaterialSymbols.Web,
                    label = state.conf.language
                        ?.let { SupportedLanguages.displayName(it) ?: it }
                        ?: stringResource(Res.string.system_default),
                    onClick = { languageDialogVisible = true },
                )
            }

            SettingsSection(stringResource(Res.string.reading)) {
                LayoutSetting(
                    selected = state.conf.entriesView,
                    onSelect = { view -> state.updateConf { conf -> conf.copy(entriesView = view) } },
                )
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Image,
                    label = stringResource(Res.string.show_feed_images),
                    checked = state.conf.showPreviewImages,
                ) { state.updateConf { conf -> conf.copy(showPreviewImages = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Crop,
                    label = stringResource(Res.string.crop_feed_images),
                    checked = state.conf.cropPreviewImages,
                ) { state.updateConf { conf -> conf.copy(cropPreviewImages = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Notes,
                    label = stringResource(Res.string.show_preview_text),
                    checked = state.conf.showPreviewText,
                ) { state.updateConf { conf -> conf.copy(showPreviewText = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Person,
                    label = stringResource(Res.string.show_author_name),
                    checked = state.conf.showAuthorName,
                ) { state.updateConf { conf -> conf.copy(showAuthorName = it) } }
            }

            SettingsSection(stringResource(Res.string.media)) {
                SettingSwitch(
                    glyph = MaterialSymbols.OpenInBrowser,
                    label = stringResource(Res.string.use_built_in_browser),
                    checked = state.conf.useBuiltInBrowser,
                    enabled = state.platform.supportsBuiltInBrowser,
                    supportingText = if (!state.platform.supportsBuiltInBrowser) {
                        stringResource(Res.string.not_available_on_this_platform)
                    } else {
                        null
                    },
                ) { state.updateConf { conf -> conf.copy(useBuiltInBrowser = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Headphones,
                    label = stringResource(Res.string.use_built_in_audio_player),
                    checked = state.conf.useBuiltInAudioPlayer,
                ) { state.updateConf { conf -> conf.copy(useBuiltInAudioPlayer = it) } }
            }

            SettingsSection(stringResource(Res.string.sync)) {
                SettingSwitch(
                    glyph = MaterialSymbols.Sync,
                    label = stringResource(Res.string.fetch_news_on_startup),
                    checked = state.conf.syncOnStartup,
                ) { state.updateConf { conf -> conf.copy(syncOnStartup = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Schedule,
                    label = stringResource(Res.string.fetch_news_in_background),
                    checked = state.conf.syncInBackground,
                    enabled = state.platform.supportsBackgroundSync,
                    supportingText = if (!state.platform.supportsBackgroundSync) {
                        stringResource(Res.string.not_available_on_this_platform)
                    } else {
                        null
                    },
                ) { state.updateConf { conf -> conf.copy(syncInBackground = it) } }
            }

            SettingsSection(stringResource(Res.string.tabs)) {
                SettingSwitch(
                    glyph = MaterialSymbols.Label,
                    label = stringResource(Res.string.show_tags_tab),
                    checked = state.conf.showTagsTab,
                ) { state.updateConf { conf -> conf.copy(showTagsTab = it) } }
                SettingDivider()
                SettingSwitch(
                    glyph = MaterialSymbols.Podcasts,
                    label = stringResource(Res.string.show_podcasts_tab),
                    checked = state.conf.showPodcastsTab,
                ) { state.updateConf { conf -> conf.copy(showPodcastsTab = it) } }
            }

            SettingsSection(stringResource(Res.string.account)) {
                InfoRow(
                    glyph = MaterialSymbols.Dns,
                    label = stringResource(Res.string.backend),
                    value = state.conf.backend?.name ?: stringResource(Res.string.not_configured),
                )
                state.conf.minifluxUrl?.let {
                    SettingDivider()
                    InfoRow(
                        glyph = MaterialSymbols.Link,
                        label = stringResource(Res.string.miniflux_url),
                        value = it,
                    )
                }
            }

            // Only hosts that can install their own updates (Android nightlies)
            // have a version to report or a channel to check.
            if (state.supportsUpdates) {
                SettingsSection(stringResource(Res.string.about)) {
                    InfoRow(
                        glyph = MaterialSymbols.Download,
                        label = stringResource(Res.string.version),
                        value = state.currentVersionLabel ?: stringResource(Res.string.unknown),
                    )
                    SettingDivider()
                    SettingAction(
                        glyph = MaterialSymbols.Sync,
                        label = stringResource(Res.string.check_for_updates),
                        supportingText = state.updateMessage,
                        busy = state.updateBusy,
                    ) { state.checkForUpdates(manual = true) }
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
                Text(stringResource(Res.string.log_out))
            }

            Spacer(Modifier.height(8.dp))
        }

        if (languageDialogVisible) {
            LanguageDialog(
                selected = state.conf.language,
                onSelect = { code ->
                    state.updateConf { conf -> conf.copy(language = code) }
                    languageDialogVisible = false
                },
                onDismiss = { languageDialogVisible = false },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    isFirst: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // The first section sits right below the content padding; later ones
        // carry the gap to the previous group.
        modifier = Modifier.padding(top = if (isFirst) 0.dp else 24.dp, bottom = 8.dp),
    )
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
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
            Text(stringResource(Res.string.entry_layout), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selected == ConfTable.EntriesView.Cards,
                onClick = { onSelect(ConfTable.EntriesView.Cards) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    MaterialSymbol(MaterialSymbols.GridView, contentDescription = null, size = 18.sp)
                },
            ) { Text(stringResource(Res.string.cards)) }
            SegmentedButton(
                selected = selected == ConfTable.EntriesView.List,
                onClick = { onSelect(ConfTable.EntriesView.List) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    MaterialSymbol(MaterialSymbols.ViewList, contentDescription = null, size = 18.sp)
                },
            ) { Text(stringResource(Res.string.list)) }
        }
    }
}

@Composable
private fun SettingSwitch(
    glyph: String,
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    supportingText: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val disabledAlpha = 0.38f
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
    }
    ListItem(
        modifier = if (enabled) {
            Modifier.toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            )
        } else {
            Modifier
        },
        leadingContent = {
            MaterialSymbol(
                glyph = glyph,
                contentDescription = null,
                tint = tint,
            )
        },
        headlineContent = {
            Text(
                text = label,
                color = if (enabled) {
                    Color.Unspecified
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                },
            )
        },
        supportingContent = supportingText?.let {
            {
                Text(
                    text = it,
                    color = if (enabled) {
                        Color.Unspecified
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    },
                )
            }
        },
        trailingContent = {
            // A disabled row reads as "unavailable", so show it off rather than
            // its stored (possibly on) value, which would look active-but-broken.
            Switch(checked = enabled && checked, onCheckedChange = null, enabled = enabled)
        },
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

/**
 * A settings row that performs an action when tapped, with an optional result
 * line and a spinner while it is [busy] (the "Check for updates" row).
 */
@Composable
private fun SettingAction(
    glyph: String,
    label: String,
    supportingText: String? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = !busy, onClick = onClick),
        leadingContent = {
            MaterialSymbol(
                glyph = glyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(label) },
        supportingContent = supportingText?.let { { Text(it) } },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                MaterialSymbol(
                    glyph = MaterialSymbols.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
