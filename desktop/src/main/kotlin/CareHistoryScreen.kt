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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CareHistoryScreen(
    plant: Plant,
    entries: List<CareLogEntry>,
    onLogCare: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onBack: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("‹ Back to plants") }
        Spacer(Modifier.height(6.dp))
        Text("${plant.name.ifBlank { "(unnamed)" }} — care history", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onLogCare("watering") }) { Text("💧 Log watering", fontSize = 12.sp) }
            Button(onClick = { onLogCare("fertilise") }) { Text("🌱 Log fertilising", fontSize = 12.sp) }
            Button(onClick = { onLogCare("feed") }) { Text("🍽️ Log feeding", fontSize = 12.sp) }
            Button(onClick = { onLogCare("prune") }) { Text("✂️ Log pruning", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(20.dp))

        if (entries.isEmpty()) {
            Text("No entries yet — log care above.", fontSize = 13.sp)
        } else {
            LazyColumn {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${careTypeIcon(entry.type)} ${careTypeLabel(entry.type)} — ${sdf.format(Date(entry.date))}", fontSize = 13.sp)
                            TextButton(onClick = { onDeleteEntry(entry.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
