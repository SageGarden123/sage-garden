package com.example.sagegarden

import android.content.Context
import java.util.UUID

private const val ENTITLEMENT_PREFS = "garden_mapper_entitlement_prefs"
private const val KEY_INSTALL_ID = "install_id"

/**
 * A per-installation UUID identifying this device to the Sage backend for entitlement and
 * Sage-prompt-count tracking. Generated once and persisted locally — there are no user accounts,
 * so reinstalling the app generates a new id (and a fresh trial).
 */
fun getOrCreateInstallId(context: Context): String {
    val prefs = context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_INSTALL_ID, null)
    if (existing != null) return existing
    val newId = UUID.randomUUID().toString()
    prefs.edit().putString(KEY_INSTALL_ID, newId).apply()
    return newId
}
