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

/**
 * Recovery escape hatch: every plant's gardenId (for the device's own default garden) is stamped
 * with whatever getOrCreateInstallId() returned at save time. Reinstalling the app always
 * generates a brand-new random install id with no way to recover the old one — so restoring an
 * old backup onto a reinstalled app leaves every plant permanently orphaned under an id nothing
 * points to anymore (they don't show up anywhere, but aren't corrupted or deleted either). This
 * lets that be fixed by typing the old id back in — visible in the old backup's plans as
 * "gardenId", or copied from the "Install ID" field before ever uninstalling. Requires an app
 * restart to take effect (effectiveGardenId() and everything downstream re-reads this from prefs
 * fresh each call, but nothing observes prefs changes directly to trigger recomposition).
 */
fun setInstallId(context: Context, value: String) {
    context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INSTALL_ID, value).apply()
}
