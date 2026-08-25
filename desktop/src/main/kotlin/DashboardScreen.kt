import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Stat(val label: String, val value: String)

private fun computeStats(plants: List<Plant>): List<Stat> {
    val now = System.currentTimeMillis()
    val needsWater = plants.count { p ->
        computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
    }
    return listOf(
        Stat("Total Plants", plants.sumOf { it.qty }.toString()),
        Stat("Native", plants.count { it.native.startsWith("Native") }.toString()),
        Stat("Exotic", plants.count { it.native.startsWith("Exotic") }.toString()),
        Stat("Pollinator-friendly", plants.count { it.pollinator.startsWith("Yes") }.toString()),
        Stat("Indoor Plants", plants.count { it.isIndoor }.toString()),
        Stat("Needs Watering Now", needsWater.toString()),
        Stat("Frost Hardy", plants.count { it.frost == "Hardy" }.toString())
    )
}

@Composable
fun DashboardScreen(plants: List<Plant>, onGoToPlants: () -> Unit) {
    val stats = computeStats(plants)
    val now = System.currentTimeMillis()
    val due = plants.filter { p ->
        computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        StatRow(stats.subList(0, 4))
        Spacer(Modifier.height(12.dp))
        StatRow(stats.subList(4, stats.size))

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Needs watering now", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onGoToPlants) { Text("View all plants") }
        }
        Spacer(Modifier.height(10.dp))
        if (due.isEmpty()) {
            Text("Nothing due right now.", fontSize = 13.sp)
        } else {
            LazyColumn {
                items(due) { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name.ifBlank { "(unnamed)" }, fontSize = 13.sp)
                            Text(computeWateringStatus(p, now)?.label ?: "", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(stats: List<Stat>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.forEach { stat ->
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stat.value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stat.label, fontSize = 12.sp)
                }
            }
        }
    }
}
