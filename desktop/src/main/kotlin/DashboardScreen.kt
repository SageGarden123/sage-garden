import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DashboardStatOption(val key: String, val label: String)

val dashboardStatCatalog = listOf(
    DashboardStatOption("total", "Total Plants"),
    DashboardStatOption("native", "Native"),
    DashboardStatOption("exotic", "Exotic"),
    DashboardStatOption("pollinator", "Pollinator-friendly"),
    DashboardStatOption("indoor", "Indoor Plants"),
    DashboardStatOption("needs_water", "Needs Watering Now"),
    DashboardStatOption("frost_hardy", "Frost Hardy")
)

private val chartGroupOptions = listOf(
    DashboardStatOption("location", "Location"),
    DashboardStatOption("category", "Category"),
    DashboardStatOption("sun", "Sun Needs"),
    DashboardStatOption("water", "Water Needs"),
    DashboardStatOption("native", "Native/Exotic")
)

fun computeDashboardStatValue(key: String, plants: List<Plant>, now: Long): String = when (key) {
    "total" -> plants.sumOf { it.qty }.toString()
    "native" -> plants.count { it.native.startsWith("Native") }.toString()
    "exotic" -> plants.count { it.native.startsWith("Exotic") }.toString()
    "pollinator" -> plants.count { it.pollinator.startsWith("Yes") }.toString()
    "indoor" -> plants.count { it.isIndoor }.toString()
    "needs_water" -> plants.count { p -> computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true }.toString()
    "frost_hardy" -> plants.count { it.frost == "Hardy" }.toString()
    else -> "0"
}

fun plantMatchesStatKey(key: String, plant: Plant, now: Long): Boolean = when (key) {
    "native" -> plant.native.startsWith("Native")
    "exotic" -> plant.native.startsWith("Exotic")
    "pollinator" -> plant.pollinator.startsWith("Yes")
    "indoor" -> plant.isIndoor
    "needs_water" -> computeWateringStatus(plant, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true
    "frost_hardy" -> plant.frost == "Hardy"
    else -> true
}

@Composable
fun DashboardScreen(plants: List<Plant>, onGoToPlants: () -> Unit, onEditPlant: (Plant) -> Unit) {
    val now = System.currentTimeMillis()
    var selectedStatKey by remember { mutableStateOf<String?>(null) }
    var chartGroupBy by remember { mutableStateOf("location") }

    val chartPlants = if (selectedStatKey == null) plants else plants.filter { plantMatchesStatKey(selectedStatKey!!, it, now) }
    val due = plants.filter { p -> computeWateringStatus(p, now)?.let { it.nextDueMillis != null && it.nextDueMillis <= now } == true }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        dashboardStatCatalog.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { option ->
                    StatTile(
                        label = option.label,
                        value = computeDashboardStatValue(option.key, plants, now),
                        selected = selectedStatKey == option.key,
                        onClick = { selectedStatKey = if (selectedStatKey == option.key) null else option.key },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val chartLabel = chartGroupOptions.firstOrNull { it.key == chartGroupBy }?.label ?: "Location"
            val statLabel = dashboardStatCatalog.firstOrNull { it.key == selectedStatKey }?.label
            Text(
                if (statLabel != null) "Plants by $chartLabel — $statLabel" else "Plants by $chartLabel",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            if (selectedStatKey != null) TextButton(onClick = { selectedStatKey = null }) { Text("Clear", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chartGroupOptions.forEach { option ->
                FilterChip(
                    selected = chartGroupBy == option.key,
                    onClick = { chartGroupBy = option.key },
                    label = { Text(option.label, fontSize = 12.sp) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        DashboardBarChart(chartPlants, chartGroupBy)

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Needs watering now", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onGoToPlants) { Text("View all plants") }
        }
        Spacer(Modifier.height(10.dp))
        if (due.isEmpty()) {
            Text("Nothing due right now.", fontSize = 13.sp)
        } else {
            due.forEach { p ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onEditPlant(p) }
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name.ifBlank { "(unnamed)" }, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(computeWateringStatus(p, now)?.label ?: "", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = SageGreen) else CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Color.Unspecified)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, color = if (selected) Color.White.copy(alpha = 0.85f) else Color.Unspecified)
        }
    }
}

@Composable
private fun DashboardBarChart(plants: List<Plant>, groupBy: String) {
    val keyFn: (Plant) -> String = when (groupBy) {
        "category" -> { p -> p.category.ifBlank { "Unspecified" } }
        "sun" -> { p -> p.sun.ifBlank { "Unspecified" } }
        "water" -> { p -> p.water.ifBlank { "Unspecified" } }
        "native" -> { p -> p.native.ifBlank { "Unspecified" } }
        else -> { p -> p.location.ifBlank { "Unspecified" } }
    }
    val counts = remember(plants, groupBy) {
        plants.groupingBy(keyFn).eachCount().entries.sortedByDescending { it.value }.take(6)
    }
    if (counts.isEmpty()) {
        Text("No data to chart yet.", fontSize = 12.sp, color = Color.Gray)
        return
    }
    val maxCount = counts.maxOf { it.value }.coerceAtLeast(1)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            counts.forEach { entry ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val fraction = (entry.value.toFloat() / maxCount).coerceIn(0.03f, 1f)
                    val barHeightDp = (110.dp * fraction).coerceAtLeast(3.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.value.toString(), fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(barHeightDp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(SageGreen)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { entry ->
                Text(
                    entry.key, fontSize = 10.sp, color = Color.Gray, lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
