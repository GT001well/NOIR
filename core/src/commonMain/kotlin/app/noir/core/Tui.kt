/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

/**
 * Monospace dashboard renderer.
 *
 * Width is computed with East-Asian wide characters counted as 2 columns and
 * ANSI escapes stripped, so the frame stays aligned regardless of labels or
 * colour. `ascii = true` swaps every glyph for plain ASCII (legacy conhost).
 */
object Tui {

    /** Visible columns of a string (CJK = 2, ANSI = 0). Exposed for tests. */
    fun visibleWidth(text: String): Int = displayWidth(text)

    /** Cuts a plain (colourless) line to the terminal width. */
    fun clip(text: String, columns: Int = terminalColumns()): String {
        if (displayWidth(text) <= columns) return text
        val sb = StringBuilder()
        var used = 0
        for (c in text) {
            val w = charWidth(c)
            if (used + w > columns - 1) break
            sb.append(c)
            used += w
        }
        return sb.append('…').toString()
    }

    /** Terminal width, from COLUMNS when available. */
    fun terminalColumns(): Int =
        System.getenv("COLUMNS")?.toIntOrNull()?.takeIf { it in 40..400 } ?: 80

    private val LOGO = listOf(
        "███╗   ██╗ ██████╗ ██╗██████╗ ",
        "████╗  ██║██╔═══██╗██║██╔══██╗",
        "██╔██╗ ██║██║   ██║██║██████╔╝",
        "██║╚██╗██║██║   ██║██║██╔══██╗",
        "██║ ╚████║╚██████╔╝██║██║  ██║",
        "╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝"
    )

    fun dashboard(
        device: Device,
        apps: List<AppEntry>,
        recommendations: List<Recommendation>,
        android: String = "?",
        host: String = "?",
        installed: Int = apps.size,
        ascii: Boolean = false,
        color: Boolean = false
    ): String {
        val s = Style(color && !ascii)
        val sb = StringBuilder()

        // never wider than the terminal, never absurdly wide on a big screen
        val totalWidth = (terminalColumns() - 2).coerceIn(50, 100)
        val W = totalWidth - 2

        val cornerTL = if (ascii) "+" else "╭"
        val cornerTR = if (ascii) "+" else "╮"
        val cornerBL = if (ascii) "+" else "╰"
        val cornerBR = if (ascii) "+" else "╯"
        val vbar = if (ascii) "|" else "│"
        val hbar = if (ascii) "-" else "─"
        val teeL = if (ascii) "+" else "├"
        val teeR = if (ascii) "+" else "┤"

        fun border(open: String, close: String) {
            sb.append(open).append(hbar.repeat(W)).append(close).append('\n')
        }

        fun line(content: String = "") {
            val safe = if (displayWidth(content) > W) truncate(visible(content), W) else content
            val pad = W - displayWidth(safe)
            sb.append(vbar).append(safe)
            if (pad > 0) sb.append(" ".repeat(pad))
            sb.append(vbar).append('\n')
        }

        fun section(label: String) {
            val text = " $label "
            val fill = W - 1 - displayWidth(text)
            sb.append(teeL).append(hbar).append(s.bold(text)).append(hbar.repeat(maxOf(0, fill))).append(teeR).append('\n')
        }

        border(cornerTL, cornerTR)

        // ── brand block ────────────────────────────────────────────────────
        if (ascii || W < 72) {
            line("  ${s.bold("NOIR")}  ${s.dim("android leak audit")}")
            line("  ${s.dim("monospace edition")}")
            line()
        } else {
            val tagline = listOf(
                s.bold("android leak audit"),
                s.dim("monospace edition"),
                "",
                s.dim(host.take(W - 38))
            )
            LOGO.forEachIndexed { i, logoLine ->
                val right = tagline.getOrElse(i) { "" }
                line("  " + s.gradient(logoLine, CYAN, VIOLET) + "   " + right)
            }
            line()
        }

        // ── device ─────────────────────────────────────────────────────────
        section("DEVICE")
        line("  ${s.dim(padRight("model", 9))} ${device.model.ifBlank { "unknown" }}")
        line("  ${s.dim(padRight("device", 9))} ${device.serial}  ${s.dim("·")}  ${device.product.ifBlank { "—" }}")
        line("  ${s.dim(padRight("android", 9))} $android  ${s.dim("·")}  $installed installed  ${s.dim("·")}  ${s.bold(apps.size.toString())} flagged")
        line()

        // ── exposure ───────────────────────────────────────────────────────
        section("EXPOSURE")
        val uncategorised = apps.count { it.category == Category.OTHER }
        val grouped = apps.filter { it.category != Category.OTHER }
            .groupBy { it.category }.mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(9)
        val max = grouped.maxOfOrNull { it.second } ?: 1
        val barWidth = (W - 36).coerceIn(6, 30)
        grouped.forEach { (category, count) ->
            val pct = if (apps.isEmpty()) 0 else count * 100 / apps.size
            line(
                "  ${padRight(category.label, 12)}  ${bar(count, max, barWidth, s, ascii)}  " +
                    "${padLeft(count.toString(), 4)}  ${s.dim(padLeft("$pct%", 4))}"
            )
        }
        if (uncategorised > 0) {
            line("  ${s.dim(padRight("未分类", 12))}  ${s.dim("· ".repeat(13))}  ${s.dim(padLeft(uncategorised.toString(), 4))}")
        }
        line()

        // ── findings ───────────────────────────────────────────────────────
        section("TOP FINDINGS")
        val pkgWidth = (W - 32).coerceIn(8, 44)
        line(
            "  ${s.dim(padLeft("#", 2))} ${s.dim(padLeft("SCORE", 5))}  " +
                s.dim(padRight("CATEGORY", 10)) + "  " +
                s.dim(padRight("PACKAGE", pkgWidth)) + "  " +
                s.dim(padLeft("UP", 2)) + " " + s.dim(padLeft("CMD", 3))
        )
        apps.take(10).forEachIndexed { index, app ->
            val score = s.risk(app.score, padLeft(app.score.toString(), 5))
            line(
                "  ${s.dim(padLeft((index + 1).toString(), 2))} $score  " +
                    "${padRight(app.category.label, 10)}  " +
                    "${padRight(truncate(app.pkg, pkgWidth), pkgWidth)}  " +
                    "${padLeft(app.uploadEndpoints.size.toString(), 2)} " +
                    padLeft(app.commandEndpoints.size.toString(), 3)
            )
        }
        line()

        // ── plan ───────────────────────────────────────────────────────────
        section("PLAN")
        val high = recommendations.count { it.risk == "HIGH" }
        val med = recommendations.count { it.risk == "MED" }
        val low = recommendations.count { it.risk == "LOW" }
        line(
            "  ${s.dim("risk")}      " +
                s.fg(248, 113, 113, "● HIGH $high") + "   " +
                s.fg(251, 191, 36, "● MED $med") + "   " +
                s.fg(52, 211, 153, "● LOW $low")
        )
        val byAction = recommendations.groupBy { it.action.label }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(6)
        if (byAction.isNotEmpty()) {
            val shown = byAction.take(3).joinToString("   ") { (label, count) -> "$label $count" }
            val rest = byAction.size - 3
            val suffix = if (rest > 0) "   ${s.dim("+$rest more")}" else ""
            line("  ${s.dim("actions")}   $shown$suffix")
        }
        line("  ${s.dim("auto-safe")} ${s.fg(52, 211, 153, recommendations.count { it.autoSafe }.toString())} ${s.dim("reversible appops, plan + predicted rollback written next to the report")}")
        line()

        border(cornerBL, cornerBR)
        return sb.toString()
    }

    // ── style ──────────────────────────────────────────────────────────────

    private val CYAN = intArrayOf(34, 211, 238)
    private val VIOLET = intArrayOf(167, 139, 250)

    private class Style(private val enabled: Boolean) {
        fun bold(text: String) = wrap("1", text)
        fun dim(text: String) = wrap("38;2;128;128;140", text)
        fun fg(r: Int, g: Int, b: Int, text: String) =
            if (!enabled) text else "\u001b[38;2;$r;$g;${b}m$text\u001b[0m"

        fun gradient(text: String, from: IntArray, to: IntArray): String {
            if (!enabled || text.isEmpty()) return text
            val sb = StringBuilder()
            val n = text.length
            text.forEachIndexed { i, c ->
                val t = if (n <= 1) 0.0 else i.toDouble() / (n - 1)
                val r = lerp(from[0], to[0], t)
                val g = lerp(from[1], to[1], t)
                val b = lerp(from[2], to[2], t)
                sb.append("\u001b[38;2;$r;$g;${b}m").append(c)
            }
            return sb.append("\u001b[0m").toString()
        }

        fun risk(score: Int, text: String): String = when {
            score >= 40 -> fg(248, 113, 113, text)
            score >= 25 -> fg(251, 191, 36, text)
            else -> fg(52, 211, 153, text)
        }

        private fun wrap(code: String, text: String) =
            if (!enabled) text else "\u001b[${code}m$text\u001b[0m"

        private fun lerp(a: Int, b: Int, t: Double) = (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    private fun bar(value: Int, max: Int, width: Int, s: Style, ascii: Boolean): String {
        val filled = if (max <= 0) 0 else (value.toLong() * width / max).toInt().coerceIn(0, width)
        val full = if (ascii) '#' else '█'
        val empty = if (ascii) '.' else '░'
        val on = buildString { repeat(filled) { append(full) } }
        val off = buildString { repeat(width - filled) { append(empty) } }
        return s.gradient(on, CYAN, VIOLET) + s.fg(60, 60, 72, off)
    }

    // ── width ──────────────────────────────────────────────────────────────

    private val ANSI = Regex("\u001B\\[[0-9;]*m")

    private fun visible(s: String) = s.replace(ANSI, "")

    private fun padLeft(s: String, width: Int): String {
        val pad = width - displayWidth(s)
        return if (pad > 0) " ".repeat(pad) + s else s
    }

    private fun padRight(s: String, width: Int): String {
        val pad = width - displayWidth(s)
        return if (pad > 0) s + " ".repeat(pad) else s
    }

    private fun truncate(s: String, width: Int): String {
        if (displayWidth(s) <= width) return s
        val sb = StringBuilder()
        var used = 0
        for (c in s) {
            val w = charWidth(c)
            if (used + w > width - 1) break
            sb.append(c)
            used += w
        }
        return sb.append('…').toString()
    }

    private fun displayWidth(s: String): Int = visible(s).sumOf { charWidth(it) }

    private fun charWidth(c: Char): Int {
        val code = c.code
        return when {
            code < 0x20 -> 0
            code in 0x1100..0x115F -> 2
            code in 0x2E80..0xA4CF -> 2
            code in 0xAC00..0xD7A3 -> 2
            code in 0xF900..0xFAFF -> 2
            code in 0xFE30..0xFE4F -> 2
            code in 0xFF00..0xFF60 -> 2
            code in 0xFFE0..0xFFE6 -> 2
            code in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }
}
