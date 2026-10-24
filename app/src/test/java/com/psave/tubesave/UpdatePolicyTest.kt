package com.psave.tubesave

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val url = "https://github.com/blackswnsdl-beep/tubesave/releases/download/v1.5/app-debug.apk"
    private val release = UpdateRelease(6, url, "a".repeat(64), 42, "com.psave.tubesave", "test")

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Must reject untrusted update") } catch (expected: IllegalArgumentException) { }
    }

    @Test fun acceptsOnlyTheOwnedHttpsReleasePath() {
        assertTrue(UpdatePolicy.trustedApk(URI(url)))
        for (bad in listOf(url.replace("https:", "http:"), url.replace("blackswnsdl-beep", "someone"),
            url.replace("github.com", "github.com.evil.example"), url + "?other=true",
            "https://user@github.com/blackswnsdl-beep/tubesave/releases/download/v1.5/app.apk",
            url.replace("v1.5", ".."), url.replace("app-debug.apk", "%61pp.apk")))
            assertFalse(bad, UpdatePolicy.trustedApk(URI(bad)))
    }
    @Test fun onlyAllowsKnownHttpsRedirectHosts() {
        assertTrue(UpdatePolicy.trustedRedirect(URI("https://release-assets.githubusercontent.com/asset?token=test")))
        assertFalse(UpdatePolicy.trustedRedirect(URI("http://release-assets.githubusercontent.com/asset")))
        assertFalse(UpdatePolicy.trustedRedirect(URI("https://evil.example/asset")))
    }
    @Test fun metadataIsPinnedToOneRepositoryFile() {
        assertTrue(UpdatePolicy.trustedMetadata(URI(UpdatePolicy.CONFIG_URL + "?t=1")))
        assertFalse(UpdatePolicy.trustedMetadata(URI(UpdatePolicy.CONFIG_URL.replace("main/", "other/"))))
        assertFalse(UpdatePolicy.trustedMetadata(URI(UpdatePolicy.CONFIG_URL.replace("https:", "http:"))))
    }
    @Test fun refusesDowngradeAndSameVersion() {
        rejected { UpdatePolicy.validateRelease(release.copy(version=5), 5, release.applicationId) }
        rejected { UpdatePolicy.validateRelease(release.copy(version=4), 5, release.applicationId) }
    }
    @Test fun requiresHashAndBoundedSizeAndAppId() {
        for (bad in listOf(release.copy(sha256=""), release.copy(sha256="z".repeat(64)),
            release.copy(size=0), release.copy(size=UpdatePolicy.MAX_APK_BYTES+1), release.copy(applicationId="other.app")))
            rejected { UpdatePolicy.validateRelease(bad,5,release.applicationId) }
    }
    @Test fun sameInstalledSignerAndVersionAreRequired() {
        UpdatePolicy.validateIdentity(release,5,release.applicationId,setOf("trusted"),release.applicationId,6,setOf("trusted"))
        rejected { UpdatePolicy.validateIdentity(release,5,release.applicationId,setOf("trusted"),release.applicationId,6,setOf("unknown")) }
        rejected { UpdatePolicy.validateIdentity(release,5,release.applicationId,emptySet(),release.applicationId,6,emptySet()) }
        rejected { UpdatePolicy.validateIdentity(release,5,release.applicationId,setOf("trusted"),release.applicationId,7,setOf("trusted")) }
        rejected { UpdatePolicy.validateIdentity(release,5,release.applicationId,setOf("trusted"),"other.app",6,setOf("trusted")) }
        rejected { UpdatePolicy.validateIdentity(release,5,release.applicationId,setOf("trusted"),release.applicationId,6,setOf("trusted","unknown")) }
    }
    @Test fun musicDownloadsDeferInstallation() {
        assertFalse(UpdatePolicy.mayInstall(true))
        assertTrue(UpdatePolicy.mayInstall(false))
    }
    @Test fun duplicateClicksAreIgnoredAndCancelOrFailureAllowsRetry() {
        val gate=UpdateFlowGate()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        gate.release()
        assertTrue(gate.acquire())
        gate.release()
        assertTrue(gate.acquire())
    }
}
