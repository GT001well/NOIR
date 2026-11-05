/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertTrue

class RulesTest {

    @Test
    fun `sensitive list covers coarse location and legacy storage`() {
        // 只申请近似定位、或者还在用 READ_EXTERNAL_STORAGE 的应用，必须能进候选。
        assertTrue("ACCESS_COARSE_LOCATION" in Rules.SENSITIVE)
        assertTrue("READ_EXTERNAL_STORAGE" in Rules.SENSITIVE)
        assertTrue("WRITE_EXTERNAL_STORAGE" in Rules.SENSITIVE)
    }

    @Test
    fun `sensitive list covers messaging and call intents`() {
        assertTrue("SEND_SMS" in Rules.SENSITIVE)
        assertTrue("CALL_PHONE" in Rules.SENSITIVE)
        assertTrue("READ_MEDIA_AUDIO" in Rules.SENSITIVE)
    }

    @Test
    fun `every sensitive data permission maps to a data type`() {
        // 能力型权限（悬浮窗、写系统设置、装应用）本来就不对应任何一类用户数据，排除掉。
        val capabilities = setOf("SYSTEM_ALERT_WINDOW", "WRITE_SECURE_SETTINGS", "REQUEST_INSTALL_PACKAGES")
        val unmapped = Rules.SENSITIVE
            .filterNot { it in capabilities }
            .filter { Exposure.dataTypes(listOf(it)).isEmpty() }
        assertTrue(unmapped.isEmpty(), "unmapped sensitive permissions: $unmapped")
    }
}
