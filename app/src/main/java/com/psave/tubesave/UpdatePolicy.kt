package com.psave.tubesave

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal data class UpdateRelease(
    val version: Long, val url: String, val sha256: String,
    val size: Long, val applicationId: String, val message: String
)

internal object UpdatePolicy {
    const val CONFIG_URL = "https://raw.githubusercontent.com/blackswnsdl-beep/tubesave/main/tubesave_config.json"
    const val MAX_APK_BYTES = 250L * 1024 * 1024
    private val apkPath = Regex("/blackswnsdl-beep/tubesave/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

    private fun https(uri: URI) = uri.scheme == "https" && uri.userInfo == null &&
        uri.port in listOf(-1, 443) && uri.fragment == null

    fun trustedMetadata(uri: URI): Boolean = https(uri) &&
        uri.host == "raw.githubusercontent.com" && uri.rawPath == URI(CONFIG_URL).rawPath

    fun trustedApk(uri: URI): Boolean = https(uri) && uri.host == "github.com" &&
        uri.query == null && apkPath.matches(uri.rawPath ?: "") && uri.normalize() == uri

    fun trustedRedirect(uri: URI): Boolean = trustedApk(uri) ||
        (https(uri) && uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))

    fun validateRelease(release: UpdateRelease, currentVersion: Long, applicationId: String) {
        require(release.version > currentVersion) { "새 버전이 아니어서 설치하지 않았어요." }
        require(release.applicationId == applicationId) { "다른 앱의 업데이트라 설치하지 않았어요." }
        require(trustedApk(URI(release.url))) { "등록된 배포 주소가 아니에요." }
        require(Regex("[0-9a-fA-F]{64}").matches(release.sha256)) { "업데이트 검증 정보가 없어요." }
        require(release.size in 1..MAX_APK_BYTES) { "업데이트 파일 크기가 올바르지 않아요." }
    }

    fun validateIdentity(
        release: UpdateRelease, currentVersion: Long, installedPackage: String,
        installedSigners: Set<String>, archivePackage: String, archiveVersion: Long,
        archiveSigners: Set<String>
    ) {
        validateRelease(release, currentVersion, installedPackage)
        require(archivePackage == installedPackage && archiveVersion == release.version) {
            "업데이트 앱 정보가 일치하지 않아요."
        }
        require(installedSigners.isNotEmpty() && installedSigners == archiveSigners) {
            "기존 앱과 서명이 달라 설치하지 않았어요. 기존 앱을 지우지 마세요."
        }
    }

    fun mayInstall(musicBusy: Boolean) = !musicBusy
}

/** One owner covers the prompt, download and installer handoff. */
internal class UpdateFlowGate {
    private val active = AtomicBoolean(false)
    fun acquire() = active.compareAndSet(false, true)
    fun release() { active.set(false) }
}
