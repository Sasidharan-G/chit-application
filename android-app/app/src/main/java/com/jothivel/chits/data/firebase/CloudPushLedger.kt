package com.jothivel.chits.data.firebase

import android.content.Context
import java.security.MessageDigest

/**
 * What the admin phone has already written to the cloud: a short hash of each document's content, keyed
 * by "collection/id". A sync then only writes the documents whose content changed, which is what keeps
 * an automatic sync inside the free Firestore quota. The `agentIds` access list is left out of the hash
 * on purpose: it is kept up to date separately (refreshAgentAccess), so a change of who may see a chit
 * does not make every document look edited.
 */
object CloudPushLedger {
    private const val PREFS = "jvc_cloud_push_hashes"

    fun key(collection: String, id: String) = "$collection/$id"

    /** Stable content hash of a document, ignoring its `agentIds`. */
    fun hash(data: Map<String, Any?>): String {
        val text = data.filterKeys { it != FirestoreSchema.AGENT_IDS }.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): String? = prefs(context).getString(key, null)

    /** Loads everything once, for comparing many documents without one preferences read each. */
    fun snapshot(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun putAll(context: Context, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val editor = prefs(context).edit()
        entries.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
