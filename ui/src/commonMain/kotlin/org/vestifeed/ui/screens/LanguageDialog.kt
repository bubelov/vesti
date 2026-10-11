package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.vestifeed.ui.i18n.SupportedLanguages
import org.vestifeed.ui.resources.Res
import org.vestifeed.ui.resources.cancel
import org.vestifeed.ui.resources.language
import org.vestifeed.ui.resources.system_default

/**
 * Picks the UI language from [SupportedLanguages], plus a "System default"
 * entry (represented by a null [onSelect] argument).
 */
@Composable
fun LanguageDialog(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.language)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    // Announces the options as one radio group to screen readers.
                    .selectableGroup(),
            ) {
                LanguageOption(
                    label = stringResource(Res.string.system_default),
                    selected = selected == null,
                ) { onSelect(null) }
                SupportedLanguages.all.forEach { language ->
                    LanguageOption(
                        label = language.displayName,
                        selected = selected == language.code,
                    ) { onSelect(language.code) }
                }
            }
        },
        // Selection applies immediately, so the dialog's only action is to
        // dismiss it. AlertDialog requires a confirm slot, so it stays empty
        // and the sole button lives in the dismiss slot (it is not a confirm).
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
