import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlantListScreen(
    plants: List<Plant>,
    onAdd: () -> Unit,
    onEdit: (Plant) -> Unit,
    onHistory: (Plant) -> Unit,
    onLogCare: (Plant, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = plants.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.sci.contains(query, ignoreCase = true) || it.location.contains(query, ignoreCase = true)
    }.sortedBy { it.name.lowercase() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Plants (${plants.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onAdd) { Text("+ Add plant") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search by name, species or location") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            Text("No plants yet — add your first one above.", fontSize = 13.sp)
        } else {
            LazyColumn {
                items(filtered, key = { it.id }) { plant ->
                    val now = System.currentTimeMillis()
                    val status = computeWateringStatus(plant, now)
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(plant.name.ifBlank { "(unnamed)" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                val subtitle = listOfNotNull(
                                    plant.sci.ifBlank { null },
                                    plant.location.ifBlank { null }
                                ).joinToString(" · ")
                                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp)
                                status?.let { Text(it.label, fontSize = 11.sp) }
                            }
                            Row {
                                TextButton(onClick = { onLogCare(plant, "watering") }) { Text("💧 Log") }
                                TextButton(onClick = { onHistory(plant) }) { Text("History") }
                                OutlinedButton(onClick = { onEdit(plant) }) { Text("Edit") }
                            }
                        }
                    }
                }
            }
        }
    }
}
