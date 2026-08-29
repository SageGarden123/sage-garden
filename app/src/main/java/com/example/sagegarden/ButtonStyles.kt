package com.example.sagegarden

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Content padding for buttons that share a row with siblings (via `Modifier.weight(1f)`),
 * where the default Material `ButtonDefaults.ContentPadding` (24dp horizontal) leaves too
 * little room for the label at larger system font sizes. Keeps the same vertical padding.
 */
val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
