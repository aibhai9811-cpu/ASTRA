package com.astra.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.assistant.data.entity.MemoryEntity

@Composable
fun MemoryScreen(
    memories: List<MemoryEntity>,
    onAddMemory: (key: String, value: String, category: String) -> Unit,
    onDeleteMemory: (id: String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .padding(16.dp)
    ) {
        Text("USER-CONTROLLED MEMORY", color = Color.White, fontSize = 16.sp, style = MaterialTheme.typography.titleMedium)
        Text("Room SQLite Database • Sensitive Data Protected", color = Color(0xFF22D3EE), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(memories) { mem ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1329)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF22D3EE).copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mem.key, color = Color(0xFF22D3EE), fontSize = 13.sp)
                            Text(mem.value, color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDeleteMemory(mem.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171))
                        }
                    }
                }
            }
        }
    }
}
