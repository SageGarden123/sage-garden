@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// REUSABLE DROPDOWN FIELD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String, options: List<String>, selected: String,
    onSelect: (String) -> Unit, helperText: String? = null, enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) },
            placeholder = { Text("Pick an option") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            supportingText = helperText?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

// ============================================================================
// DATE PICKER FIELD
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String, dateString: String, onDateChange: (String) -> Unit,
    restrictToPastOrToday: Boolean = false,
    allowNotApplicable: Boolean = false,
    allowClear: Boolean = true,
    enabled: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = dateString, onValueChange = {}, readOnly = true, enabled = enabled,
        label = { Text(label) }, placeholder = { Text("YYYY-MM-DD") },
        trailingIcon = { IconButton(onClick = { if (enabled) showDialog = true }, enabled = enabled) { Text("📅") } },
        modifier = Modifier.fillMaxWidth()
    )
    if (showDialog) {
        val datePickerState = rememberDatePickerState(
            selectableDates = if (restrictToPastOrToday) {
                // The picker reports each candidate day as UTC midnight of that calendar date, so
                // comparing it against a raw System.currentTimeMillis() instant breaks in any
                // timezone ahead of UTC: today's UTC-midnight representation is later than the
                // actual current UTC instant until local time catches up to the UTC offset (e.g.
                // until 10am in AEST/UTC+10), making "today" look like a future date and get
                // excluded — this is exactly why only yesterday and earlier were selectable.
                // Fix: compare against UTC midnight of *today's local date* instead, using the
                // same yyyy-MM-dd/UTC convention dateStringToMillis uses everywhere else.
                val todayUtcMidnight = run {
                    val localSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val utcSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    utcSdf.parse(localSdf.format(Date()))!!.time
                }
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis <= todayUtcMidnight
                }
            } else DatePickerDefaults.AllDates
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        onDateChange(sdf.format(Date(millis)))
                    }
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowNotApplicable && dateString != "N/A") {
                        TextButton(onClick = { onDateChange("N/A"); showDialog = false }) { Text("N/A") }
                    }
                    if (allowClear && dateString.isNotBlank()) {
                        TextButton(onClick = { onDateChange(""); showDialog = false }) { Text("Clear") }
                    }
                    TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
            }
        ) { DatePicker(state = datePickerState) }
    }
}
