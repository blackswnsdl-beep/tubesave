package com.psave.tubesave

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal object UpdateVerifier {
    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags())

    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { UpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: UpdateRelease) {
        require(file.isFile && file.length() == release.size && UpdateTransfer.sha256(file) == release.sha256.lowercase()) {
            "업데이트 파일이 손상되었어요."
        }
        val current = installed(context)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags())
            ?: throw IllegalArgumentException("업데이트 파일을 읽을 수 없어요.")
        UpdatePolicy.validateIdentity(release, version(current), context.packageName, signers(current),
            archive.packageName, version(archive), signers(archive))
    }
}
