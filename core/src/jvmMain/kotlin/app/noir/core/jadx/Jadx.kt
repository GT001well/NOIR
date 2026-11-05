/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.jadx

import app.noir.core.platform.Exec
import java.io.File
import java.util.concurrent.TimeUnit

data class JadxHit(val file: String, val line: Int, val text: String)

/** Drives the external `jadx` CLI and greps its output. */
class Jadx(private val bin: String = System.getenv("JADX") ?: "jadx") {

    fun available(): Boolean = try {
        val process = ProcessBuilder(Exec.argv(bin, listOf("--version"))).redirectErrorStream(true).start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        process.inputStream.close()
        finished
    } catch (e: Exception) {
        false
    }

    fun decompile(apk: File, outDir: File, noRes: Boolean = true, timeoutSec: Long = 900): Int {
        outDir.mkdirs()
        val args = mutableListOf(bin, "-d", outDir.absolutePath)
        if (noRes) args += "--no-res"
        args += apk.absolutePath

        // 输出落临时文件：不把 jadx 的全部日志读进内存，进程不退出时按超时强杀。
        val log = File.createTempFile("noir-jadx-", ".log")
        val process = ProcessBuilder(Exec.argv(bin, args.drop(1)))
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            log.delete()
            return -1
        }
        val code = process.exitValue()
        log.delete()
        return code
    }

    fun grep(dir: File, patterns: List<String>, maxHits: Int = 200): List<JadxHit> {
        val hits = ArrayList<JadxHit>()
        if (!dir.exists()) return hits

        val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        val files = dir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .toList()

        for (file in files) {
            if (hits.size >= maxHits) break
            val lines = try {
                file.readLines()
            } catch (e: Exception) {
                continue
            }
            for ((index, line) in lines.withIndex()) {
                if (hits.size >= maxHits) break
                if (regexes.any { it.containsMatchIn(line) }) {
                    hits.add(JadxHit(file.relativeTo(dir).path, index + 1, line.trim().take(220)))
                }
            }
        }
        return hits
    }
}
