package org.vestifeed.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.vestifeed.ui.AppState
import org.vestifeed.ui.icons.MaterialSymbol
import org.vestifeed.ui.icons.MaterialSymbols

/**
 * The "a newer build is available" prompt. Rendered at the app root so it can
 * appear over any screen; it drives [AppState.installUpdate] and stays up while
 * the download runs and, if the OS still needs the install permission, while the
 * user grants it.
 */
@Composable
fun UpdateAvailableDialog(state: AppState) {
    val manifest = state.availableUpdate ?: return
    val busy = state.updateBusy
    val progress = state.updateProgress

    AlertDialog(
        onDismissRequest = { if (!busy) state.dismissUpdate() },
        icon = { MaterialSymbol(MaterialSymbols.Download, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Vesti ${manifest.versionName} is ready to install.")
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                state.updateMessage?.let { message ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.installUpdate() }, enabled = !busy) {
                Text(if (busy) "Downloading…" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = { state.dismissUpdate() }, enabled = !busy) {
                Text("Later")
            }
        },
    )
}
