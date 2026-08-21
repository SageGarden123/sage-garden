package com.example.sagegarden

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WateringWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                WidgetConfigScreen(
                    initialConfig = getWidgetConfig(this, appWidgetId),
                    onSave = { config ->
                        setWidgetConfig(this, appWidgetId, config)
                        scheduleWidgetRefresh(this, appWidgetId, config.intervalDays)
                        lifecycleScope.launch {
                            refreshWateringWidgets(this@WateringWidgetConfigActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigScreen(initialConfig: WidgetConfig, onSave: (WidgetConfig) -> Unit) {
    val context = LocalContext.current
    // The widget itself stays free for everyone — only how many plants it can show is capped.
    val maxPlantsCap = if (EntitlementManager.getCached(context).isPro) 20 else EntitlementManager.FREE_WIDGET_PLANT_LIMIT
    val maxPlantsOptions = remember(maxPlantsCap) { listOf(4, 8, 12, 20).filter { it <= maxPlantsCap } }

    var intervalDays by remember { mutableStateOf(initialConfig.intervalDays) }
    var maxPlants by remember { mutableStateOf(minOf(initialConfig.maxPlants, maxPlantsCap)) }
    var lookaheadDays by remember { mutableStateOf(initialConfig.lookaheadDays) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Widget settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF233821))
        Text("Plants needing water", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        Text("Refresh every", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 day", 2 to "2 days", 3 to "3 days", 7 to "7 days").forEach { (days, label) ->
                FilterChip(selected = intervalDays == days, onClick = { intervalDays = days }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Show up to", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            maxPlantsOptions.forEach { n ->
                FilterChip(selected = maxPlants == n, onClick = { maxPlants = n }, label = { Text("$n", fontSize = 12.sp) })
            }
        }
        if (maxPlantsCap < 20) {
            Spacer(Modifier.height(4.dp))
            Text("Free plan widgets show up to $maxPlantsCap plants.", fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(20.dp))

        Text("Include plants due within", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Today only", 2 to "2 days", 5 to "5 days", 7 to "7 days").forEach { (days, label) ->
                FilterChip(selected = lookaheadDays == days, onClick = { lookaheadDays = days }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { onSave(WidgetConfig(intervalDays, maxPlants, lookaheadDays)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
    }
}
