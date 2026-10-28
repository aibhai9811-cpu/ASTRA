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
import com.astra.assistant.data.entity.KnowledgeEntity

@Composable
fun KnowledgeScreen(
    knowledgeList: List<KnowledgeEntity>,
    onAddKnowledge: (title: String, content: String, sourceType: String, sourceName: String?, tags: String) -> Unit,
    onDeleteKnowledge: (id: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020617)).padding(16.dp)
    ) {
        Text("KNOWLEDGE LIBRARY", color = Color.White, fontSize = 16.sp)
        Text("Searchable Local Articles & Source Citations", color = Color(0xFF10B981), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(knowledgeList) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF10B981).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.title, color = Color(0xFF10B981), fontSize = 13.sp)
                            IconButton(onClick = { onDeleteKnowledge(item.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.content, color = Color.LightGray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
