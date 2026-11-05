/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

import app.noir.core.Device
import app.noir.core.platform.Exec
import java.io.File
import java.util.concurrent.TimeUnit

data class ExecResult(
    val code: Int,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false
) {
    val ok: Boolean get() = code == 0 && !timedOut
}

/** Thin wrapper around the platform `adb` binary. */
class Adb(private val bin: String = System.getenv("ADB") ?: "adb") {

    fun exec(args: List<String>, timeoutSec: Long = 180): ExecResult {
        val process = ProcessBuilder(Exec.argv(bin, args))
            .redirectErrorStream(true)
            .start()
        // 放到独立线程里读：在主线程读到 EOF 再等退出，进程卡住不关 stdout 时超时永远不生效。
        val buffer = StringBuffer()
        val pump = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { buffer.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }
        val finished = if (timeoutSec <= 0) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        }
        if (!finished) {
            process.destroyForcibly()
            pump.join(500)
            return ExecResult(-1, buffer.toString(), timedOut = true)
        }
        // 进程已退出，正常情况读线程立刻收尾；给足时间，避免大输出被静默截断。
        pump.join(30_000)
        return ExecResult(process.exitValue(), buffer.toString(), truncated = pump.isAlive)
    }

    /**
     * Runs a command and delivers stdout line by line. Used by the device-side
     * scan so the progress bar can advance once per APK.
     */
    fun execStreaming(
        args: List<String>,
        timeoutSec: Long = 0,
        onLine: (String) -> Unit
    ): ExecResult {
        val process = ProcessBuilder(Exec.argv(bin, args))
            .redirectErrorStream(true)
            .start()
        val output = StringBuffer()
        // 同样放独立线程读，否则超时只在读完输出后才生效。
        val pump = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        output.append(line).append('\n')
                        onLine(line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = if (timeoutSec <= 0) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        }
        if (!finished) {
            process.destroyForcibly()
            pump.join(500)
            return ExecResult(-1, output.toString(), timedOut = true)
        }
        pump.join(30_000)
        return ExecResult(process.exitValue(), output.toString(), truncated = pump.isAlive)
    }

    fun shell(serial: String?, command: String, timeoutSec: Long = 180): ExecResult {
        val args = mutableListOf<String>()
        if (serial != null) {
            args += "-s"
            args += serial
        }
        args += "shell"
        args += command
        return exec(args, timeoutSec)
    }

    fun devices(): List<Device> {
        val result = exec(listOf("devices", "-l"), timeoutSec = 30)
        if (!result.ok) return emptyList()
        val lines = result.output.lineSequence().toList()
        // 找不到表头说明输出不是预期的设备列表（daemon 日志、错误信息），不要硬解析。
        val header = lines.indexOfFirst { it.startsWith("List of devices attached") }
        if (header < 0) return emptyList()
        val body = lines.drop(header + 1)
        return body.asSequence()
            .filter { it.isNotBlank() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size < 2) return@mapNotNull null
                var model = ""
                var product = ""
                parts.drop(2).forEach { field ->
                    if (field.startsWith("model:")) model = field.removePrefix("model:")
                    if (field.startsWith("product:")) product = field.removePrefix("product:")
                }
                Device(serial = parts[0], state = parts[1], model = model, product = product)
            }
            .toList()
    }

    fun push(serial: String, local: File, remote: String): ExecResult =
        exec(listOf("-s", serial, "push", local.absolutePath, remote), timeoutSec = 120)

    fun pull(serial: String, remote: String, local: File): ExecResult {
        local.parentFile?.mkdirs()
        return exec(listOf("-s", serial, "pull", remote, local.absolutePath), timeoutSec = 600)
    }
}
