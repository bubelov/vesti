@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.vestifeed.ui.AppState

@Composable
fun SettingsScreen(state: AppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Reading", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Show preview images", state.conf.showPreviewImages) {
            state.updateConf { conf -> conf.copy(showPreviewImages = it) }
        }
        SettingSwitch("Crop preview images", state.conf.cropPreviewImages) {
            state.updateConf { conf -> conf.copy(cropPreviewImages = it) }
        }
        SettingSwitch("Show preview text", state.conf.showPreviewText) {
            state.updateConf { conf -> conf.copy(showPreviewText = it) }
        }
        SettingSwitch("Show author name", state.conf.showAuthorName) {
            state.updateConf { conf -> conf.copy(showAuthorName = it) }
        }
        SettingSwitch("Use built-in browser", state.conf.useBuiltInBrowser) {
            state.updateConf { conf -> conf.copy(useBuiltInBrowser = it) }
        }
        SettingSwitch("Use built-in audio player", state.conf.useBuiltInAudioPlayer) {
            state.updateConf { conf -> conf.copy(useBuiltInAudioPlayer = it) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Sync", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Sync on startup", state.conf.syncOnStartup) {
            state.updateConf { conf -> conf.copy(syncOnStartup = it) }
        }
        SettingSwitch("Sync in background", state.conf.syncInBackground) {
            state.updateConf { conf -> conf.copy(syncInBackground = it) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Tabs", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Show tags tab", state.conf.showTagsTab) {
            state.updateConf { conf -> conf.copy(showTagsTab = it) }
        }
        SettingSwitch("Show podcasts tab", state.conf.showPodcastsTab) {
            state.updateConf { conf -> conf.copy(showPodcastsTab = it) }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Backend: ${state.conf.backend?.name ?: "Not configured"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.conf.minifluxUrl?.let {
            Text("Miniflux URL: $it", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { state.logout() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log out") }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
