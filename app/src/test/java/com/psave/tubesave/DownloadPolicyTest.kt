package com.psave.tubesave

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    private val now = 30 * 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L

    @Test fun oldInstalledAndRemoteSettingsMigrate() {
        for (value in listOf(null, "", " youtube:player_client=android_vr "))
            assertEquals("youtube:player_client=default", DownloadPolicy.extractorArgs(value))
    }
    @Test fun customRemoteSettingsRemainUsable() {
        assertEquals("youtube:player_client=web", DownloadPolicy.extractorArgs("youtube:player_client=web"))
    }
    @Test fun firstDownloadUpdatesEvenWithOldLifetimeFlag() {
        assertTrue(DownloadPolicy.shouldUpdate(0, 0, 0, now))
    }
    @Test fun successfulUpdateLastsOneDay() {
        assertFalse(DownloadPolicy.shouldUpdate(now - hour, now - hour, 0, now))
        assertTrue(DownloadPolicy.shouldUpdate(now - 24 * hour, now - 24 * hour, 0, now))
    }
    @Test fun downloadFailureSchedulesRecoveryWithoutRequestStorm() {
        assertFalse(DownloadPolicy.shouldUpdate(now - 1, now - 1, 2, now))
        assertTrue(DownloadPolicy.shouldUpdate(now - hour, now - hour, 2, now))
    }
    @Test fun failedOfflineUpdateUsesCooldown() {
        assertFalse(DownloadPolicy.shouldUpdate(0, now - 1, 0, now))
        assertTrue(DownloadPolicy.shouldUpdate(0, now - hour, 0, now))
    }
    @Test fun clockMovingBackwardsDoesNotFreezeUpdates() {
        assertTrue(DownloadPolicy.shouldUpdate(now + hour, now + hour, 0, now))
    }
}
