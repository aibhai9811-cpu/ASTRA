package com.astra.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.assistant.data.entity.CorrectionRuleEntity

@Composable
fun CorrectionsScreen(
    rules: List<CorrectionRuleEntity>,
    onAddRule: (trigger: String, actionType: String, target: String, label: String) -> Unit,
    onDeleteRule: (id: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020617)).padding(16.dp)
    ) {
        Text("LEARNED CORRECTIONS", color = Color.White, fontSize = 16.sp)
        Text("Custom Phrase Mappings (e.g. 'gadi lagao' ➔ Uber)", color = Color(0xFFA855F7), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rules) { rule ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFA855F7).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("\"${rule.triggerPhrase}\"", color = Color(0xFFA855F7), fontSize = 14.sp)
                            Text("➔ ${rule.actionLabel}", color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDeleteRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171))
                        }
                    }
                }
            }
        }
    }
}
