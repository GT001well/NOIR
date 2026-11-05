/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.Device
import app.noir.core.ReportMeta
import app.noir.core.adb.Adb
import app.noir.core.platform.Platform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyTest {

    private fun fixture(scriptBody: String): Pair<File, ScanResult> {
        val dir = Files.createTempDirectory("noir-apply").toFile()
        val state = File(dir, "state")
        val script = File(dir, "fake-adb")
        script.writeText(
            """
            #!/bin/sh
            STATE="$state"
            cmd="${'$'}4"
            $scriptBody
            """.trimIndent() + "\n"
        )
        script.setExecutable(true)
        val result = ScanResult(
            device = Device("serial", "device", "PLK110", "p1"),
            apps = emptyList(),
            outDir = dir,
            android = "16",
            installed = 0,
            paths = emptyMap(),
            meta = ReportMeta(
                tool = "NOIR test",
                host = "test host",
                android = "16",
                sdk = "36",
                build = "PLK110_16.0.0",
                fingerprint = "test/fingerprint",
                user = "0",
                generatedAt = "2026-11-04T00:00:00Z"
            )
        )
        return script to result
    }

    @Test
    fun `failed appops keeps stdout and stderr in applied txt`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            case "${'$'}cmd" in
              *"cmd appops get"*) echo "PROJECT_MEDIA: default"; exit 0 ;;
              *"cmd appops set"*) echo "java.lang.SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES" >&2; exit 255 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("failed (255) [system permission]"))
        assertTrue(applied.contains("SecurityException"))
        assertTrue(applied.contains("before: PROJECT_MEDIA: default"))
        assertTrue(applied.contains("after:  PROJECT_MEDIA: default"))
        assertTrue(applied.contains("user: 0"))
        // 回滚在执行前就落盘，命令失败也留着，避免设备实际改了却无记录
        assertTrue(rollback.contains("adb shell cmd appops set --user 0 com.example PROJECT_MEDIA default"))
    }

    @Test
    fun `successful appops records the precise previous mode`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            n=${'$'}(cat "${'$'}STATE" 2>/dev/null || echo 0)
            n=${'$'}((n + 1))
            echo "${'$'}n" > "${'$'}STATE"
            case "${'$'}cmd" in
              *"cmd appops get"*)
                if [ "${'$'}n" -eq 1 ]; then echo "PROJECT_MEDIA: default"; else echo "PROJECT_MEDIA: ignore"; fi
                exit 0 ;;
              *"cmd appops set"*) exit 0 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("cmd appops set --user 0 com.example PROJECT_MEDIA ignore\tok (0)"))
        assertTrue(applied.contains("after:  PROJECT_MEDIA: ignore"))
        assertTrue(rollback.contains("adb shell cmd appops set --user 0 com.example PROJECT_MEDIA default"))
    }

    @Test
    fun `exit 0 without a mode change is not reported as success`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            case "${'$'}cmd" in
              *"cmd appops get"*) echo "PROJECT_MEDIA: default"; exit 0 ;;
              *"cmd appops set"*) exit 0 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("not applied (still default)"))
        // set 已经跑过一次，回滚记录必须留着，否则真改了也改不回来
        assertTrue(rollback.contains("adb shell cmd appops set --user 0 com.example PROJECT_MEDIA default"))
    }

    @Test
    fun `unreadable current mode is skipped instead of guessed`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            case "${'$'}cmd" in
              *"cmd appops get"*) echo "NONE"; echo "No operations."; exit 0 ;;
              *"cmd appops set"*) echo "should not run" > "${'$'}STATE"; exit 0 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("skipped: cannot read the current mode"))
        assertFalse(rollback.contains("adb shell cmd appops set"))
    }
}
