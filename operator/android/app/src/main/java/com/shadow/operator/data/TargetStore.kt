package com.shadow.operator.data

import android.content.Context

/**
 * Persisted list of known implants plus the currently selected one.
 *
 * The operator previously shipped a single hardcoded implantId compiled into
 * BuildConfig/Models, which meant reflashing the APK to talk to a different
 * device. This store keeps targets in SharedPreferences (JSON via Gson) so the
 * operator can hold several implants and switch between them.
 */
data class Target(
    val id: String,
    val label: String = "",
    val platform: String = "android",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0L,
    val lastAddr: String = ""
) {
    val display: String get() = label.ifBlank { id.take(8) }
}

class TargetStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("shadow_targets", Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()

    fun load(): MutableList<Target> {
        val raw = prefs.getString("targets", null) ?: return mutableListOf()
        return runCatching {
            gson.fromJson(raw, Array<Target>::class.java).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun save(list: List<Target>) {
        prefs.edit().putString("targets", gson.toJson(list)).apply()
    }

    fun selectedId(): String = prefs.getString("selected", "") ?: ""

    fun select(id: String) {
        prefs.edit().putString("selected", id).apply()
    }

    /** Insert or refresh a target, preserving firstSeen and any user label. */
    fun upsert(list: MutableList<Target>, incoming: Target): Boolean {
        val idx = list.indexOfFirst { it.id == incoming.id }
        return if (idx >= 0) {
            val old = list[idx]
            val merged = old.copy(
                lastSeen = incoming.lastSeen,
                lastAddr = incoming.lastAddr.ifBlank { old.lastAddr },
                platform = incoming.platform.ifBlank { old.platform }
            )
            val changed = merged != old
            list[idx] = merged
            changed
        } else {
            list.add(incoming)
            true
        }
    }

    fun rename(list: MutableList<Target>, id: String, label: String) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(label = label)
            save(list)
        }
    }

    fun remove(list: MutableList<Target>, id: String) {
        list.removeAll { it.id == id }
        save(list)
        if (selectedId() == id) select("")
    }
}