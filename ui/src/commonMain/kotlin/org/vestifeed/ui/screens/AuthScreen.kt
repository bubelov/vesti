@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.vestifeed.backend.backend
import org.vestifeed.db.table.ConfTable
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/** The width the sign-in form is capped at on every screen. */
private val FormMaxWidth = 420.dp

/** Width at which the phone layout becomes the wide, card-centred layout. */
private val ExpandedBreakpoint = 600.dp

/**
 * Width at which the wide layout adds a brand/supporting pane beside the form.
 * Kept well above twice the form width so each pane has real margin, not a
 * card butted against the divider.
 */
private val TwoPaneBreakpoint = 960.dp

/**
 * The first-run sign-in screen. A width-constrained form (Material 3's sign-in
 * pattern): a headline, a backend segmented button, the credentials for the
 * selected backend, and one primary action. Validation is shown on the
 * offending field; server failures (bad token, CORS) appear in an error
 * container under the button.
 *
 * The layout adapts to the window: a bottom-anchored form with a full brand
 * above it on phones, a centred card under a smaller brand on medium windows,
 * and a brand/supporting pane beside the form on wide ones. On keyboard-first
 * hosts the first field is focused and Enter submits.
 */
@Composable
fun AuthScreen(state: AppState) {
    val form = remember { AuthFormState(state.conf) }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            val width = maxWidth
            when {
                width >= TwoPaneBreakpoint -> TwoPaneAuth(state, form)
                width >= ExpandedBreakpoint -> WideAuth(state, form)
                else -> CompactAuth(state, form)
            }
        }
    }
}

/** Phones: the brand fills the space above the bottom-anchored form. */
@Composable
private fun CompactAuth(state: AppState, form: AuthFormState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Brand(logoSize = 128.sp, titleSize = 36.sp)
        }
        AuthForm(
            state,
            form,
            Modifier
                .widthIn(max = FormMaxWidth)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        )
    }
}

/** Medium windows: a smaller brand over a vertically centred card. */
@Composable
private fun WideAuth(state: AppState, form: AuthFormState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Brand(logoSize = 72.sp, titleSize = 28.sp)
        Spacer(Modifier.height(20.dp))
        OutlinedCard(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = FormMaxWidth),
        ) {
            AuthForm(state, form, Modifier.padding(24.dp))
        }
    }
}

/** Wide windows: a brand/supporting pane beside the form card. */
@Composable
private fun TwoPaneAuth(state: AppState, form: AuthFormState) {
    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BrandPanel(Modifier.padding(32.dp))
            }
        }
        VerticalDivider()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedCard(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = FormMaxWidth),
            ) {
                AuthForm(state, form, Modifier.padding(24.dp))
            }
        }
    }
}

/**
 * The Vesti mark: the app glyph with the wordmark tucked under it. The sizes
 * are parameters so the layouts can render different scales.
 */
@Composable
private fun Brand(logoSize: TextUnit, titleSize: TextUnit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MaterialSymbol(
            glyph = MaterialSymbols.ListAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            size = logoSize,
        )
        Text(
            text = "VESTI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            fontSize = titleSize,
            letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.onSurface,
            // The icon font carries descender space, so a fixed gap reads too
            // loose. Tuck the wordmark up by a third of its own size — less
            // than a full half so there is still visible breathing room — which
            // scales with both the phone and desktop brand.
            modifier = Modifier.offset(y = (-titleSize.value / 3).dp),
        )
    }
}

/** The supporting pane's content: the mark plus a one-line description. */
@Composable
private fun BrandPanel(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Brand(logoSize = 96.sp, titleSize = 32.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "A simple, private feed reader.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Form fields and transient UI state, remembered across the layout switch. */
private class AuthFormState(conf: ConfTable.Conf) {
    var backend by mutableStateOf(conf.backend ?: ConfTable.Backend.Miniflux)
    var url by mutableStateOf(conf.minifluxUrl ?: "")
    var token by mutableStateOf(conf.minifluxToken ?: "")
    var tokenVisible by mutableStateOf(false)
    var showValidation by mutableStateOf(false)
    var serverError by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

@Composable
private fun AuthForm(
    state: AppState,
    form: AuthFormState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val urlFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }

    val miniflux = form.backend == ConfTable.Backend.Miniflux
    val missingUrl = miniflux && form.url.isBlank()
    val missingToken = miniflux && form.token.isBlank()
    val urlError = missingUrl && form.showValidation
    val tokenError = missingToken && form.showValidation

    // A field edit or backend change invalidates the previous errors.
    fun clearErrors() {
        form.showValidation = false
        form.serverError = null
    }

    fun submit() {
        when {
            missingUrl || missingToken -> {
                form.showValidation = true
                form.serverError = null
            }
            else -> scope.launch {
                form.busy = true
                form.serverError = null
                try {
                    if (miniflux) {
                        state.setConf {
                            it.copy(
                                backend = ConfTable.Backend.Miniflux,
                                minifluxUrl = form.url.trim().trimEnd('/').ifBlank { null },
                                minifluxToken = form.token.ifBlank { null },
                            )
                        }
                        // Validate the credentials by listing feeds. This
                        // throws on a rejected token (401) and, in the browser,
                        // on a cross-origin API that does not send CORS headers.
                        state.withDb { backend(state.db).getFeeds() }
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
                    form.serverError = t.message ?: t.toString()
                } finally {
                    form.busy = false
                }
            }
        }
    }

    // Keyboard-first hosts land on the first field (or, for Embedded, the
    // action) so typing can begin without a click. Touch hosts stay unfocused
    // so no soft keyboard pops up unasked.
    val autofocus = state.platform.supportsHardwareKeyboard
    LaunchedEffect(autofocus, miniflux) {
        if (!autofocus) return@LaunchedEffect
        if (miniflux) urlFocus.requestFocus() else submitFocus.requestFocus()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Backend",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = form.backend == ConfTable.Backend.Miniflux,
                onClick = {
                    form.backend = ConfTable.Backend.Miniflux
                    clearErrors()
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {},
            ) { Text("Miniflux") }
            SegmentedButton(
                selected = form.backend == ConfTable.Backend.Embedded,
                onClick = {
                    form.backend = ConfTable.Backend.Embedded
                    clearErrors()
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {},
            ) { Text("Embedded") }
        }

        if (miniflux) {
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = form.url,
                onValueChange = {
                    form.url = it
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
                keyboardActions = KeyboardActions(onNext = { tokenFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(urlFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            tokenFocus.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.token,
                onValueChange = {
                    form.token = it
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
                visualTransformation = if (form.tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { form.tokenVisible = !form.tokenVisible }) {
                        MaterialSymbol(
                            glyph = if (form.tokenVisible) {
                                MaterialSymbols.VisibilityOff
                            } else {
                                MaterialSymbols.Visibility
                            },
                            contentDescription = if (form.tokenVisible) {
                                "Hide password"
                            } else {
                                "Show password"
                            },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(tokenFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
        } else {
            // Reserve the credentials' height so the brand header above
            // doesn't shift when switching backends; the warning fills
            // the same slot.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 164.dp),
            ) {
                EmbeddedWarning(Modifier.padding(top = 24.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            enabled = !form.busy,
            onClick = { submit() },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(submitFocus),
        ) {
            if (form.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = when {
                    form.busy -> "Signing in…"
                    miniflux -> "Sign in"
                    else -> "Continue"
                },
            )
        }

        form.serverError?.let {
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

/**
 * The caution shown when the Embedded backend is selected. Embedded mode keeps
 * its data in the app's own database, so the notice points the user at OPML
 * export for a durable backup.
 */
@Composable
private fun EmbeddedWarning(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(16.dp)) {
            MaterialSymbol(MaterialSymbols.Warning, contentDescription = null, size = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Embedded mode is experimental",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your data may not survive app updates. " +
                        "Back up your feeds regularly by exporting them as OPML.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
