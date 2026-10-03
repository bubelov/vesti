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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.db.table.ConfTable
import org.vestifeed.ui.AppState

@Composable
fun AuthScreen(state: AppState) {
    var backend by remember { mutableStateOf(state.conf.backend ?: ConfTable.Backend.Miniflux) }
    var url by remember { mutableStateOf(state.conf.minifluxUrl ?: "") }
    var token by remember { mutableStateOf(state.conf.minifluxToken ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Vesti", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Choose how to read your feeds.", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(24.dp))
        BackendOption(
            label = "Miniflux",
            selected = backend == ConfTable.Backend.Miniflux,
            onSelect = { backend = ConfTable.Backend.Miniflux },
        )
        BackendOption(
            label = "Embedded",
            selected = backend == ConfTable.Backend.Embedded,
            onSelect = { backend = ConfTable.Backend.Embedded },
        )

        if (backend == ConfTable.Backend.Miniflux) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Miniflux URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !busy,
            onClick = {
                if (backend == ConfTable.Backend.Miniflux && url.isBlank()) {
                    error = "Miniflux URL is required"
                } else if (backend == ConfTable.Backend.Miniflux && token.isBlank()) {
                    error = "API token is required"
                } else {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            if (backend == ConfTable.Backend.Miniflux) {
                                state.setConf {
                                    it.copy(
                                        backend = ConfTable.Backend.Miniflux,
                                        minifluxUrl = url.trim().trimEnd('/').ifBlank { null },
                                        minifluxToken = token.ifBlank { null },
                                    )
                                }
                                // Validate the credentials by listing feeds. This
                                // throws on a rejected token (401) and, in the
                                // browser, on a cross-origin API that does not
                                // send CORS headers.
                                backend(state.db).getFeeds()
                            } else {
                                state.setConf {
                                    it.copy(
                                        backend = ConfTable.Backend.Embedded,
                                        minifluxUrl = null,
                                        minifluxToken = null,
                                    )
                                }
                            }
                            state.navigateRoot(state.defaultTab())
                            // Pull the first batch of entries/feeds.
                            state.sync.runInBackground()
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        ) {
            Text(if (busy) "Signing in…" else "Sign in")
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BackendOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
