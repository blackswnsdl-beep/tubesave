package com.psave.tubesave

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class UpdateTransferTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var directory: File
    private val bytes=ByteArray(24000) { (it % 251).toByte() }
    private lateinit var base: String

    @Before fun setup() {
        directory=Files.createTempDirectory("update-test").toFile()
        server=ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        base="http://127.0.0.1:"+server.localPort
        serverThread=Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout=3000
                        val reader=socket.getInputStream().bufferedReader()
                        val path=reader.readLine().split(" ")[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val response=when(path) {
                            "/failure" -> "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" to ByteArray(0)
                            "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: https://untrusted.example/file.apk\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" to ByteArray(0)
                            "/large" -> "HTTP/1.1 200 OK\r\nContent-Length: 40000\r\nConnection: close\r\n\r\n" to ByteArray(40000)
                            else -> "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" to bytes
                        }
                        socket.getOutputStream().apply { write(response.first.toByteArray()); write(response.second); flush() }
                    }
                } catch (ignored: IOException) { /* test client may intentionally cancel */ }
            }
        }.apply { isDaemon=true; start() }
    }
    @After fun clean() { server.close(); serverThread.join(1000); directory.deleteRecursively() }
    private fun release(path: String="/ok") = UpdateRelease(6,base+path,
        UpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.size.toLong(),"com.psave.tubesave","test")
    private fun transfer()=UpdateTransfer(allow={it.host=="127.0.0.1" && it.port==server.localPort})
    private fun fails(block: () -> Unit) {
        try { block(); fail("Transfer must fail") } catch (expected: IOException) { }
    }
    @Test fun verifiesActualBytesAndReportsProgress() {
        val target=File(directory,"update.apk")
        val progress=mutableListOf<Int>()
        transfer().download(release(),target,{},{progress.add(it)})
        assertArrayEquals(bytes,target.readBytes())
        assertEquals(100,progress.last())
    }
    @Test fun corruptHashRemovesOnlyTheIncompleteUpdate() {
        val target=File(directory,"update.apk")
        val music=File(directory,"song.mp3").apply { writeText("keep") }
        fails { transfer().download(release().copy(sha256="0".repeat(64)),target,{},{}) }
        assertFalse(target.exists()); assertEquals("keep",music.readText())
    }
    @Test fun wrongSizeIsRejectedAndCleaned() {
        val target=File(directory,"update.apk")
        fails { transfer().download(release().copy(size=1),target,{},{}) }
        assertFalse(target.exists())
    }
    @Test fun httpFailureDoesNotLeaveAnInstallableFile() {
        val target=File(directory,"update.apk")
        fails { transfer().download(release("/failure"),target,{},{}) }
        assertFalse(target.exists())
    }
    @Test fun untrustedRedirectIsNeverOpened() {
        val target=File(directory,"update.apk")
        try { transfer().download(release("/redirect"),target,{},{}); fail() } catch (expected: IllegalArgumentException) { }
        assertFalse(target.exists())
    }
    @Test fun cancellationBeforeStartLeavesNoFile() {
        val target=File(directory,"update.apk")
        try { transfer().download(release(),target,{throw CancellationException()},{}); fail() } catch (expected: CancellationException) { }
        assertFalse(target.exists())
    }
    @Test fun cancellationDuringReadRemovesPartialFileAndCanRetry() {
        val target=File(directory,"update.apk")
        var checks=0
        try { transfer().download(release(),target,{if (++checks>=4) throw CancellationException()},{}); fail() } catch (expected: CancellationException) { }
        assertFalse(target.exists())
        transfer().download(release(),target,{},{})
        assertArrayEquals(bytes,target.readBytes())
    }
    @Test fun offlineFailureLeavesNoFile() {
        val target=File(directory,"update.apk")
        val offline=UpdateTransfer(open={throw IOException("offline")},allow={true})
        fails { offline.download(release(),target,{},{}) }
        assertFalse(target.exists())
    }
    @Test fun metadataHasAStrictSizeLimit() {
        fails { transfer().metadata(base+"/large") }
    }
}
