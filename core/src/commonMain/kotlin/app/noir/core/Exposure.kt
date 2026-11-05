package app.noir.core

/** What kind of user data a permission can expose, independent of the app. */
enum class DataType(val label: String) {
    LOCATION("位置"),
    CONTACTS("通讯录"),
    SMS("短信"),
    CALLS("通话记录"),
    MIC("麦克风"),
    CAMERA("相机"),
    SCREEN("屏幕"),
    APPS("应用列表"),
    IDENTITY("设备标识"),
    FILES("文件/相册"),
    CALENDAR("日历"),
    ACCOUNTS("账号"),
    HEALTH("健康/传感器")
}

/**
 * Maps permissions to data categories and correlates them with upload endpoints.
 *
 * This is the part that answers "what could leave the device", not just
 * "what permission did it declare".
 */
object Exposure {

    private val MAP: Map<String, Set<DataType>> = mapOf(
        "ACCESS_FINE_LOCATION" to setOf(DataType.LOCATION),
        "ACCESS_COARSE_LOCATION" to setOf(DataType.LOCATION),
        "ACCESS_BACKGROUND_LOCATION" to setOf(DataType.LOCATION),
        "READ_CONTACTS" to setOf(DataType.CONTACTS),
        "READ_SMS" to setOf(DataType.SMS),
        "RECEIVE_SMS" to setOf(DataType.SMS),
        "SEND_SMS" to setOf(DataType.SMS),
        "READ_CALL_LOG" to setOf(DataType.CALLS),
        "CALL_PHONE" to setOf(DataType.CALLS),
        "ANSWER_PHONE_CALLS" to setOf(DataType.CALLS),
        "READ_PHONE_STATE" to setOf(DataType.IDENTITY),
        "READ_PHONE_NUMBERS" to setOf(DataType.IDENTITY),
        "READ_PRIVILEGED_PHONE_STATE" to setOf(DataType.IDENTITY),
        "RECORD_AUDIO" to setOf(DataType.MIC),
        "BODY_SENSORS" to setOf(DataType.HEALTH),
        "CAPTURE_AUDIO_OUTPUT" to setOf(DataType.MIC),
        "CAPTURE_VOICE_COMMUNICATION_OUTPUT" to setOf(DataType.MIC),
        "CAMERA" to setOf(DataType.CAMERA),
        "FOREGROUND_SERVICE_MEDIA_PROJECTION" to setOf(DataType.SCREEN),
        "QUERY_ALL_PACKAGES" to setOf(DataType.APPS),
        "PACKAGE_USAGE_STATS" to setOf(DataType.APPS),
        "MANAGE_EXTERNAL_STORAGE" to setOf(DataType.FILES),
        "READ_MEDIA_IMAGES" to setOf(DataType.FILES),
        "READ_MEDIA_VIDEO" to setOf(DataType.FILES),
        "READ_MEDIA_AUDIO" to setOf(DataType.FILES),
        "READ_EXTERNAL_STORAGE" to setOf(DataType.FILES),
        "WRITE_EXTERNAL_STORAGE" to setOf(DataType.FILES),
        "ACCESS_MEDIA_LOCATION" to setOf(DataType.LOCATION),
        "READ_CALENDAR" to setOf(DataType.CALENDAR),
        "GET_ACCOUNTS" to setOf(DataType.ACCOUNTS)
    )

    fun dataTypes(permissions: List<String>): Set<DataType> =
        permissions.flatMap { MAP[it].orEmpty() }.toSortedSet(compareBy { it.ordinal })

    fun hostOf(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return host.takeIf { it.contains('.') && it.isNotBlank() }
    }

    /** Distinct upload hosts for an app, if it has any upload endpoint. */
    fun uploadHosts(app: AppEntry): List<String> =
        app.uploadEndpoints.mapNotNull { hostOf(it) }.distinct().sorted()

    /**
     * Correlation: data categories the app can read + hosts it can upload to.
     * Empty when there is no upload endpoint — capability alone is not a leak.
     */
    fun leaks(app: AppEntry): Map<DataType, List<String>> {
        val hosts = uploadHosts(app)
        if (hosts.isEmpty()) return emptyMap()
        return dataTypes(app.permissions).associateWith { hosts }
    }
}
