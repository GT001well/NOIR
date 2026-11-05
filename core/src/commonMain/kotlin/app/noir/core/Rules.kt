/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

/**
 * The heuristics that turn raw permissions and URL strings into a risk signal.
 *
 * Everything here is deliberately simple and explainable: no ML, no opaque scoring.
 * A finding is always traceable to a permission or a string that exists in the APK.
 */
object Rules {

    val SENSITIVE: List<String> = listOf(
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION",
        "ACCESS_MEDIA_LOCATION",
        "READ_CONTACTS",
        "READ_SMS",
        "RECEIVE_SMS",
        "SEND_SMS",
        "READ_CALL_LOG",
        "CALL_PHONE",
        "ANSWER_PHONE_CALLS",
        "READ_PHONE_STATE",
        "READ_PHONE_NUMBERS",
        "READ_PRIVILEGED_PHONE_STATE",
        "RECORD_AUDIO",
        "BODY_SENSORS",
        "CAMERA",
        "PACKAGE_USAGE_STATS",
        "QUERY_ALL_PACKAGES",
        "GET_ACCOUNTS",
        "READ_CALENDAR",
        "MANAGE_EXTERNAL_STORAGE",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE",
        "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO",
        "READ_MEDIA_AUDIO",
        "SYSTEM_ALERT_WINDOW",
        "REQUEST_INSTALL_PACKAGES",
        "FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "CAPTURE_AUDIO_OUTPUT",
        "CAPTURE_VOICE_COMMUNICATION_OUTPUT",
        "WRITE_SECURE_SETTINGS"
    )

    private val UPLOAD = Regex(
        "/(report|upload|collect|track|stat|beacon|log)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    private val COMMAND = Regex(
        "/(command|cmd|dispatch|execute|script)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    fun isUpload(url: String): Boolean = UPLOAD.containsMatchIn(url)

    fun isCommand(url: String): Boolean = COMMAND.containsMatchIn(url)

    fun score(
        permissions: List<String>,
        endpoints: List<String>,
        isSystem: Boolean,
        debuggable: Boolean
    ): Int {
        var s = permissions.size
        if ("QUERY_ALL_PACKAGES" in permissions) s += 2
        if ("READ_PRIVILEGED_PHONE_STATE" in permissions) s += 3
        if ("FOREGROUND_SERVICE_MEDIA_PROJECTION" in permissions) s += 4
        if (isSystem) s += 2
        if (debuggable) s += 2
        if (endpoints.any { isCommand(it) }) s += 2
        if (endpoints.any { isUpload(it) }) s += 3
        return s
    }
}
