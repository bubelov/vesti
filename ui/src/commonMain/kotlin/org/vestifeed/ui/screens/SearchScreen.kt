@file:OptIn(ExperimentalMaterial3Api::class)

package org.vestifeed.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vestifeed.ui.AppState
import org.vestifeed.ui.Screen
import org.vestifeed.ui.entries.EntryRow
import org.vestifeed.ui.entries.toRow

@Composable
fun SearchScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<EntryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, state.conf) {
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            rows = emptyList()
            loading = false
        } else {
            loading = true
            rows = state.withDb {
                state.db.entry.selectByQuery(trimmed).map { it.toRow(state.conf) }
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        when {
            query.trim().length < 3 -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text("Type at least 3 characters.") }

            loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            rows.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text("No results.") }

            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { state.navigate(Screen.EntryDetail(row.id)) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                row.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (row.showPreviewText && row.summary.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stripHtml(row.summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
