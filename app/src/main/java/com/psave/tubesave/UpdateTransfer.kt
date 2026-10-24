package com.psave.tubesave

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** No cookies, arbitrary hosts, external storage, or existing music files. */
internal class UpdateTransfer(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val allow: (URI) -> Boolean = UpdatePolicy::trustedRedirect
) {
    private fun connect(url: String, cancelled: () -> Unit): HttpURLConnection {
        var uri = URI(url)
        repeat(6) {
            cancelled()
            require(allow(uri)) { "등록된 배포 주소가 아니에요." }
            val connection = open(uri.toURL()).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val target = connection.getHeaderField("Location") ?: throw IOException("이동 주소가 없어요.")
                    uri = uri.resolve(target)
                    connection.disconnect()
                } else {
                    if (code !in 200..299) throw IOException("HTTP $code")
                    return connection
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("업데이트 주소가 너무 여러 번 바뀌었어요.")
    }

    fun metadata(url: String): String {
        val connection = connect(url) {}
        try {
            connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(32768)
                return bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    fun download(release: UpdateRelease, target: File, cancelled: () -> Unit, progress: (Int) -> Unit) {
        var complete = false
        var connection: HttpURLConnection? = null
        try {
            cancelled()
            connection = connect(release.url, cancelled)
            val declared = connection.contentLengthLong
            if (declared >= 0 && declared != release.size) throw IOException("파일 크기가 일치하지 않아요.")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        cancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > release.size || total > UpdatePolicy.MAX_APK_BYTES) throw IOException("파일 크기가 너무 커요.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress((total * 100 / release.size).toInt())
                    }
                }
            }
            cancelled()
            if (total != release.size || hex(digest.digest()) != release.sha256.lowercase())
                throw IOException("파일이 손상되어 설치하지 않았어요.")
            complete = true
        } finally {
            connection?.disconnect()
            if (!complete) target.delete()
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IOException("설정 파일이 너무 커요.")
            output.write(buffer, 0, count)
        }
    }

    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return hex(digest.digest())
        }
    }
}
