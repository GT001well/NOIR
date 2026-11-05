/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import java.io.File

/**
 * Remembers endpoint extraction results so repeat scans only touch new APKs.
 * Keyed by package + APK path; Android changes the path whenever the app updates.
 */
object Cache {

    private val dir = File(System.getProperty("user.home"), ".noir").apply { mkdirs() }
    // Bump when extraction logic changes: old entries may encode failed scans.
    private const val CACHE_VERSION = "v3"
    private val file = File(dir, "endpoints-$CACHE_VERSION.tsv")
    private val map = HashMap<String, String>()

    init {
        if (file.exists()) {
            runCatching {
                file.forEachLine { line ->
                    val sep = line.indexOf('\t')
                    if (sep > 0) map[line.substring(0, sep)] = line.substring(sep + 1)
                }
            }
        }
    }

    // 键里带设备作用域：同一台电脑接两台设备时，同包同路径的端点不会互相串。
    private fun key(scope: String, pkg: String, path: String) = "$scope|$pkg|$path"

    fun get(scope: String, pkg: String, path: String): List<String>? {
        val k = key(scope, pkg, path)
        if (!map.containsKey(k)) return null
        return map[k].orEmpty().split(' ').filter { it.isNotBlank() }
    }

    fun put(scope: String, pkg: String, path: String, urls: List<String>) {
        map[key(scope, pkg, path)] = urls.joinToString(" ")
    }

    fun flush() {
        runCatching {
            file.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }
}
