/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.platform

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloads the external tools NOIR needs into `~/.noir/tools`, so a fresh
 * machine does not need Homebrew/scoop/apt before the first run.
 *
 * Nothing is downloaded without an explicit yes (or `--yes`).
 */
object Setup {

    // 哈希需按官方发布值填写。
    private const val ADB_DARWIN_SHA256 = ""
    private const val ADB_LINUX_SHA256 = ""
    private const val ADB_WINDOWS_SHA256 = ""
    private const val JADX_SHA256 = ""

    const val ADB_DARWIN = "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
    const val ADB_LINUX = "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
    const val ADB_WINDOWS = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    const val JADX_ZIP = "https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip"

    fun toolsDir(): File = File(System.getProperty("user.home"), ".noir/tools").apply { mkdirs() }

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        val expectedSha256 = when (url) {
            ADB_DARWIN -> ADB_DARWIN_SHA256
            ADB_LINUX -> ADB_LINUX_SHA256
            ADB_WINDOWS -> ADB_WINDOWS_SHA256
            JADX_ZIP -> JADX_SHA256
            else -> ""
        }
        var current = URL(url)
        repeat(6) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "NOIR/0.1")

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) return false
                current = URL(current, location)
                return@repeat
            }
            if (code != 200) {
                conn.disconnect()
                return false
            }

            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            conn.disconnect()
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (expectedSha256.isEmpty()) {
                println("warning: SHA-256 is not configured for $url; skipping verification")
            } else if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                println("warning: SHA-256 mismatch for $url; deleting $dest")
                dest.delete()
                return false
            }
            return true
        }
        return false
    }

    fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        val destRootPath = destRoot.canonicalPath + File.separator
        var skipped = 0
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                if (entry.name.startsWith("/")) {
                    skipped++
                    return@forEach
                }
                val target = File(destRoot, entry.name).canonicalFile
                if (!target.canonicalPath.startsWith(destRootPath)) {
                    skipped++
                    return@forEach
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        if (skipped > 0) {
            println("warning: skipped $skipped unsafe zip entr${if (skipped == 1) "y" else "ies"}")
        }
    }

    fun installAdb(onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val url = when {
            Platform.isWindows -> ADB_WINDOWS
            Platform.isMac -> ADB_DARWIN
            else -> ADB_LINUX
        }
        val dir = toolsDir()
        val zip = File(dir, "platform-tools.zip")
        if (!download(url, zip, onProgress)) return null
        unzip(zip, dir)
        zip.delete()

        val adb = if (Platform.isWindows) {
            File(dir, "platform-tools/adb.exe")
        } else {
            File(dir, "platform-tools/adb").apply { setExecutable(true) }
        }
        return if (adb.exists()) adb.absolutePath else null
    }

    fun installJadx(onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val dir = File(toolsDir(), "jadx")
        val zip = File(toolsDir(), "jadx.zip")
        if (!download(JADX_ZIP, zip, onProgress)) return null
        unzip(zip, dir)
        zip.delete()

        val bin = if (Platform.isWindows) {
            File(dir, "bin/jadx.bat")
        } else {
            File(dir, "bin/jadx").apply { setExecutable(true) }
        }
        return if (bin.exists()) bin.absolutePath else null
    }
}
