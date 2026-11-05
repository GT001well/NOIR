/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

import app.noir.core.apk.ApkScan
import java.io.File

data class PackagePath(val pkg: String, val path: String)

/**
 * Collects URL strings from every installed APK.
 *
 * The fast path runs entirely on the device (unzip + strings), so only a few KB
 * cross the USB link. Progress is streamed per package: the shell script prints
 * one marker after each APK, which lets the CLI redraw its bar for every app
 * instead of every batch.
 *
 * If the device's `unzip` cannot read an APK at all, the script reports it and
 * the host pulls that file and parses it locally.
 */
object DeviceScan {

    private const val FALLBACK = "F "
    private const val RESULT = "R "
    private const val PROGRESS = "P "

    fun packagePaths(adb: Adb, serial: String): Map<String, String> {
        val res = adb.shell(serial, "pm list packages -f")
        // 超时 / 非零退出 / 读不全时，半份清单不能当成完整设备清单
        if (!res.ok || res.truncated) return emptyMap()
        val map = HashMap<String, String>()
        res.output.lineSequence().forEach { line ->
            if (!line.startsWith("package:")) return@forEach
            val body = line.removePrefix("package:")
            val idx = body.lastIndexOf('=')
            if (idx <= 0) return@forEach
            val path = body.substring(0, idx)
            val pkg = body.substring(idx + 1).trim()
            if (pkg.isNotEmpty()) map[pkg] = path
        }
        return map
    }

    fun hasDeviceTools(adb: Adb, serial: String): Boolean {
        // grep 也要检查：缺它时每条 APK 都抽不出域名，会伪装成「干净的设备」。
        val out = adb.shell(serial, "which unzip strings grep").output
        return out.contains("unzip") && out.contains("strings") && out.contains("grep")
    }

    fun urls(
        adb: Adb,
        serial: String,
        targets: List<PackagePath>,
        workDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        failures: MutableSet<String> = mutableSetOf()
    ): Map<String, List<String>> {
        if (targets.isEmpty()) return emptyMap()
        workDir.mkdirs()

        if (!hasDeviceTools(adb, serial)) {
            return urlsByPulling(adb, serial, targets, workDir, onProgress, failures)
        }

        val result = HashMap<String, List<String>>()
        val pendingFallback = ArrayList<String>()

        val list = File(workDir, "targets.txt")
        list.writeText(targets.joinToString("\n", postfix = "\n") { "${it.pkg} ${it.path}" })

        val script = File(workDir, "scan.sh")
        script.writeText(
            """
            #!/system/bin/sh
            TMP=/data/local/tmp/noir_dex.bin
            while read p f; do
              : > ${'$'}TMP
              # 动态枚举全部分片；老 ROM 的 unzip 不认 -Z1 时退回固定名单。
              DEXES=${'$'}(unzip -Z1 "${'$'}f" 2>/dev/null | grep -E '^classes[0-9]*\.dex${'$'}')
              [ -z "${'$'}DEXES" ] && DEXES="classes.dex classes2.dex classes3.dex classes4.dex \
                  classes5.dex classes6.dex classes7.dex classes8.dex classes9.dex classes10.dex \
                  classes11.dex classes12.dex classes13.dex classes14.dex classes15.dex"
              for d in ${'$'}DEXES; do
                unzip -p "${'$'}f" "${'$'}d" 2>/dev/null >> ${'$'}TMP
              done
              if [ ! -s ${'$'}TMP ]; then
                echo "F ${'$'}p"
              else
                u=${'$'}(strings < ${'$'}TMP | grep -oE 'https?://[A-Za-z0-9._~:/?#@!&()*+,;=%-]+' \
                    | sort -u | head -400 | tr '\n' ' ')
                [ -n "${'$'}u" ] && echo "R ${'$'}p|${'$'}u"
              fi
              rm -f ${'$'}TMP
              echo "P ${'$'}p"
            done < /data/local/tmp/noir_targets.txt
            echo "#DONE"
            """.trimIndent()
        )

        try {
            adb.push(serial, script, "/data/local/tmp/noir_scan.sh")
            adb.push(serial, list, "/data/local/tmp/noir_targets.txt")

            var done = 0
            val total = targets.size
            val processed = HashSet<String>()

            val execution = adb.execStreaming(
                listOf("-s", serial, "shell", "sh /data/local/tmp/noir_scan.sh"),
                timeoutSec = 1800
            ) { line ->
                when {
                    line.startsWith(RESULT) -> {
                        val body = line.removePrefix(RESULT)
                        val pkg = body.substringBefore('|')
                        val found = body.substringAfter('|', "").split(' ').filter { it.isNotBlank() }
                        if (pkg.isNotEmpty() && found.isNotEmpty()) result[pkg] = found
                    }
                    line.startsWith(FALLBACK) -> pendingFallback.add(line.removePrefix(FALLBACK).trim())
                    line.startsWith(PROGRESS) -> {
                        done++
                        processed.add(line.removePrefix(PROGRESS).trim())
                        onProgress(done, total)
                    }
                }
            }

            if (done == 0 && targets.isNotEmpty()) {
                // The script never ran. Fall back to pulling everything rather than
                // returning a plausible-looking empty report.
                println("  ! device-side scan produced no progress; pulling ${targets.size} APKs instead")
                return urlsByPulling(adb, serial, targets, workDir, onProgress, failures)
            }

            val unfinished = targets.map { it.pkg }.filterNot { it in processed }
            if (execution.code != 0 || unfinished.isNotEmpty()) {
                println(
                    "  ! device-side scan incomplete (exit ${execution.code}, " +
                        "${total - unfinished.size}/$total processed); unfinished packages will be retried"
                )
                // 没跑完的包不能当成「零端点」，否则会被缓存成干净结果
                failures.addAll(unfinished)
            }

            if (pendingFallback.isNotEmpty()) {
                println("  ! ${pendingFallback.size} APK(s) could not be read by the device's unzip; pulling instead")
                val fallbackTargets = targets.filter { it.pkg in pendingFallback }
                result.putAll(
                    urlsByPulling(adb, serial, fallbackTargets, workDir, { index, count ->
                        print("\r  pulling $index/$count")
                        System.out.flush()
                    }, failures)
                )
                println()
            }
        } finally {
            // Short timeout: the device may already be gone by the time we get here.
            adb.shell(serial, "rm -f /data/local/tmp/noir_scan.sh /data/local/tmp/noir_targets.txt", timeoutSec = 10)
        }

        return result
    }

    private fun urlsByPulling(
        adb: Adb,
        serial: String,
        targets: List<PackagePath>,
        workDir: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        failures: MutableSet<String> = mutableSetOf()
    ): Map<String, List<String>> {
        val result = HashMap<String, List<String>>()
        val apkDir = File(workDir, "apks").apply { mkdirs() }
        targets.forEachIndexed { index, target ->
            val local = File(apkDir, "${target.pkg}.apk")
            local.delete() // 旧文件可能是上一个版本，不能当成本次结果
            val pulled = adb.pull(serial, target.path, local)
            if (!pulled.ok || !local.exists()) {
                local.delete()
                failures.add(target.pkg)
            } else {
                val found = runCatching { ApkScan.urls(local) }.getOrNull()
                if (found == null) {
                    // 解析异常，不能当成「零个端点」写进缓存
                    failures.add(target.pkg)
                } else if (found.isNotEmpty()) {
                    result[target.pkg] = found
                }
            }
            onProgress(index + 1, targets.size)
        }
        return result
    }
}
