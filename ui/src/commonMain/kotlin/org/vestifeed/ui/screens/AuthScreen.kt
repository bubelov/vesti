@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.db.table.ConfTable
import org.vestifeed.ui.AppState

/**
 * The first-run sign-in screen. A vertically centered, width-constrained form
 * (Material 3's sign-in pattern): a headline, a backend segmented button, the
 * credentials for the selected backend, and one primary action. Validation is
 * shown on the offending field; server failures (bad token, CORS) appear in an
 * error container under the button.
 */
@Composable
fun AuthScreen(state: AppState) {
    var backend by remember { mutableStateOf(state.conf.backend ?: ConfTable.Backend.Miniflux) }
    var url by remember { mutableStateOf(state.conf.minifluxUrl ?: "") }
    var token by remember { mutableStateOf(state.conf.minifluxToken ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val miniflux = backend == ConfTable.Backend.Miniflux
    val missingUrl = miniflux && url.isBlank()
    val missingToken = miniflux && token.isBlank()
    val urlError = missingUrl && showValidation
    val tokenError = missingToken && showValidation

    // A field edit or backend change invalidates the previous errors.
    fun clearErrors() {
        showValidation = false
        serverError = null
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Text(
                    text = "Vesti",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Choose how to read your feeds.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(32.dp))
                Text(
                    text = "Backend",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = backend == ConfTable.Backend.Miniflux,
                        onClick = {
                            backend = ConfTable.Backend.Miniflux
                            clearErrors()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {},
                    ) { Text("Miniflux") }
                    SegmentedButton(
                        selected = backend == ConfTable.Backend.Embedded,
                        onClick = {
                            backend = ConfTable.Backend.Embedded
                            clearErrors()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {},
                    ) { Text("Embedded") }
                }

                if (miniflux) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            clearErrors()
                        },
                        label = { Text("Miniflux URL") },
                        placeholder = { Text("https://feeds.example.com") },
                        singleLine = true,
                        isError = urlError,
                        supportingText = if (urlError) {
                            { Text("Enter your Miniflux server URL") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = {
                            token = it
                            clearErrors()
                        },
                        label = { Text("API token") },
                        singleLine = true,
                        isError = tokenError,
                        supportingText = if (tokenError) {
                            { Text("Enter your Miniflux API token") }
                        } else {
                            null
                        },
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Text(
                                text = if (tokenVisible) "Hide" else "Show",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { tokenVisible = !tokenVisible }
                                    .padding(horizontal = 8.dp),
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(28.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        when {
                            missingUrl || missingToken -> {
                                showValidation = true
                                serverError = null
                            }
                            else -> scope.launch {
                                busy = true
                                serverError = null
                                try {
                                    if (miniflux) {
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
                                    serverError = t.message ?: t.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = when {
                            busy -> "Signing in…"
                            miniflux -> "Sign in"
                            else -> "Continue"
                        },
                    )
                }

                serverError?.let {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
