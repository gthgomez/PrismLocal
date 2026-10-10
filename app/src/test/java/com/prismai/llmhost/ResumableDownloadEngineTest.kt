package com.prismai.llmhost

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

class ResumableDownloadEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        serverPort = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun parseContentRange_handlesValidAndInvalidHeaders() {
        val parsed = ResumableDownloadEngine.parseContentRange("bytes 100-199/1000")
        assertNotNull(parsed)
        assertEquals(100L, parsed!!.start)
        assertEquals(199L, parsed.end)
        assertEquals(1000L, parsed.total)

        val starTotal = ResumableDownloadEngine.parseContentRange("bytes 0-50/*")
        assertNotNull(starTotal)
        assertEquals(0L, starTotal!!.start)
        assertEquals(50L, starTotal.end)
        assertNull(starTotal.total)

        assertNull(ResumableDownloadEngine.parseContentRange(null))
        assertNull(ResumableDownloadEngine.parseContentRange("invalid"))
        assertNull(ResumableDownloadEngine.parseContentRange("bytes foo-bar/baz"))
        assertNull("end before start is malformed", ResumableDownloadEngine.parseContentRange("bytes 50-20/100"))
        assertNull("end at the declared total is malformed", ResumableDownloadEngine.parseContentRange("bytes 0-100/100"))
        assertNull(
            "a well-formed range embedded in a malformed header must be rejected",
            ResumableDownloadEngine.parseContentRange("garbage bytes 0-50/100"),
        )
        assertNull(
            "trailing garbage after a valid range must be rejected",
            ResumableDownloadEngine.parseContentRange("bytes 0-50/100 trailing"),
        )
        assertNull(
            "extra segments after a valid range must be rejected",
            ResumableDownloadEngine.parseContentRange("bytes 0-50/100;foo"),
        )

        assertEquals(1000L, ResumableDownloadEngine.parseContentRangeTotal("bytes 100-199/1000"))
        assertEquals(500L, ResumableDownloadEngine.parseContentRangeTotal("bytes */500"))
    }

    @Test
    fun serverReturning206WithWrongStart_discardsPartialFileAndRedownloadsFromZero() = runBlocking {
        val fullData = ByteArray(100) { (it % 10 + '0'.code).toByte() }
        var requestCount = 0

        server.createContext("/model.gguf") { exchange ->
            requestCount++
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                // Return 206 with wrong start: client asked for 50-, but server claims 20-
                exchange.responseHeaders.set("Content-Range", "bytes 20-99/100")
                exchange.responseHeaders.set("ETag", "\"tag1\"")
                exchange.sendResponseHeaders(206, 80)
                exchange.responseBody.write(fullData, 20, 80)
            } else {
                // Redownload from zero
                exchange.responseHeaders.set("ETag", "\"tag1\"")
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(fullData)
            }
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(ByteArray(50) { 'X'.code.toByte() })
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "tag1").put("expected_size", 100L).toString())

        val engine = ResumableDownloadEngine()
        engine.downloadResumable(
            downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
            target = target,
            expectedSize = 100L,
        )

        assertEquals(2, requestCount)
        assertEquals(100L, target.length())
        assertArrayEquals(fullData, target.readBytes())
    }

    @Test
    fun serverReturning200OnRangeRequest_resetsAndDownloadsFromZeroWithoutAppending() = runBlocking {
        val fullData = ByteArray(80) { 'Y'.code.toByte() }

        server.createContext("/model.gguf") { exchange ->
            // Ignore Range and return full 200
            exchange.responseHeaders.set("ETag", "\"tag1\"")
            exchange.sendResponseHeaders(200, 80)
            exchange.responseBody.write(fullData)
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(ByteArray(40) { 'X'.code.toByte() })
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "tag1").put("expected_size", 80L).toString())

        val engine = ResumableDownloadEngine()
        engine.downloadResumable(
            downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
            target = target,
            expectedSize = 80L,
        )

        assertEquals(80L, target.length())
        assertArrayEquals(fullData, target.readBytes())
    }

    @Test
    fun serverReportingDifferentTotalThanExpected_failsCleanlyAndDeletesPartialFile() = runBlocking {
        server.createContext("/model.gguf") { exchange ->
            exchange.responseHeaders.set("Content-Range", "bytes 0-49/200")
            exchange.sendResponseHeaders(206, 50)
            exchange.responseBody.write(ByteArray(50))
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        val meta = File(tempFolder.root, "test.part.meta")

        val engine = ResumableDownloadEngine()
        try {
            engine.downloadResumable(
                downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
                target = target,
                expectedSize = 100L,
            )
            fail("Expected IllegalStateException due to size mismatch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Remote model size changed"))
        }

        assertFalse("Target should be deleted on size mismatch", target.exists())
        assertFalse("Meta should be deleted on size mismatch", meta.exists())
    }

    @Test
    fun remoteEtagChangeBetweenResume_invalidatesPartialFileAndRestarts() = runBlocking {
        val v2Data = ByteArray(100) { '2'.code.toByte() }
        var requestCount = 0

        server.createContext("/model.gguf") { exchange ->
            requestCount++
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                // Resume request receives a newer ETag
                exchange.responseHeaders.set("ETag", "\"v2-etag\"")
                exchange.responseHeaders.set("Content-Range", "bytes 50-99/100")
                exchange.sendResponseHeaders(206, 50)
                exchange.responseBody.write(ByteArray(50))
            } else {
                // Redownload from zero with new ETag
                exchange.responseHeaders.set("ETag", "\"v2-etag\"")
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(v2Data)
            }
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(ByteArray(50) { '1'.code.toByte() })
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "v1-etag").put("expected_size", 100L).toString())

        val engine = ResumableDownloadEngine()
        engine.downloadResumable(
            downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
            target = target,
            expectedSize = 100L,
        )

        assertEquals(2, requestCount)
        assertEquals(100L, target.length())
        assertArrayEquals(v2Data, target.readBytes())
    }

    @Test
    fun downloadExceedingMaxModelBytes_terminatesAndCleansUp() = runBlocking {
        server.createContext("/large.gguf") { exchange ->
            exchange.sendResponseHeaders(200, 200)
            exchange.responseBody.write(ByteArray(200))
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        val engine = ResumableDownloadEngine(maxModelBytes = 100L)

        try {
            engine.downloadResumable(
                downloadUrl = "http://127.0.0.1:$serverPort/large.gguf",
                target = target,
                expectedSize = 200L,
            )
            fail("Expected size limit failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exceeds maximum size limit"))
        }

        assertFalse(target.exists())
    }

    @Test
    fun insufficientStorageSpace_abortsCleanly() = runBlocking {
        server.createContext("/model.gguf") { exchange ->
            exchange.sendResponseHeaders(200, 50)
            exchange.responseBody.write(ByteArray(50))
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        val engine = ResumableDownloadEngine(
            minStorageReserveBytes = 1000L,
            getUsableSpace = { 500L }, // less than 1000 reserve
        )

        try {
            engine.downloadResumable(
                downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
                target = target,
                expectedSize = 50L,
            )
            fail("Expected storage space failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Insufficient storage space"))
        }

        assertFalse(target.exists())
    }

    @Test
    fun successfulResumableDownload_tracksProgressAndResumedOffset() = runBlocking {
        val initialPart = ByteArray(40) { 'A'.code.toByte() }
        val remainingPart = ByteArray(60) { 'B'.code.toByte() }

        server.createContext("/model.gguf") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            assertEquals("bytes=40-", rangeHeader)
            exchange.responseHeaders.set("Content-Range", "bytes 40-99/100")
            exchange.responseHeaders.set("ETag", "\"tag1\"")
            exchange.sendResponseHeaders(206, 60)
            exchange.responseBody.write(remainingPart)
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(initialPart)
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "tag1").put("expected_size", 100L).toString())

        var progressResumedFrom: Long? = null
        var lastBytesDone: Long = 0

        val engine = ResumableDownloadEngine()
        engine.downloadResumable(
            downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
            target = target,
            expectedSize = 100L,
            onProgress = { bytesDone, total, resumedFrom ->
                progressResumedFrom = resumedFrom
                lastBytesDone = bytesDone
            },
        )

        assertEquals(100L, target.length())
        assertEquals(40L, progressResumedFrom)
        assertEquals(100L, lastBytesDone)

        val combined = target.readBytes()
        assertEquals(100, combined.size)
        assertArrayEquals(initialPart, combined.copyOfRange(0, 40))
        assertArrayEquals(remainingPart, combined.copyOfRange(40, 100))
    }

    @Test
    fun rangeBodyShorterThanDeclared_failsAndRetainsResumablePartialFile() = runBlocking {
        server.createContext("/model.gguf") { exchange ->
            // Declares the range through byte 99 but only sends 30 bytes.
            exchange.responseHeaders.set("Content-Range", "bytes 40-99/100")
            exchange.responseHeaders.set("ETag", "\"tag1\"")
            exchange.sendResponseHeaders(206, 30)
            exchange.responseBody.write(ByteArray(30) { 'Z'.code.toByte() })
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(ByteArray(40) { 'A'.code.toByte() })
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "tag1").put("expected_size", 100L).toString())

        val engine = ResumableDownloadEngine()
        try {
            engine.downloadResumable(
                downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
                target = target,
                expectedSize = 100L,
            )
            fail("Expected an incomplete-download failure")
        } catch (e: IncompleteDownloadException) {
            assertTrue(e.message!!.contains("Incomplete download"))
        }

        assertEquals(
            "a truncated transfer with validators must keep its resumable prefix",
            70L,
            target.length(),
        )
        assertTrue("the validators must be kept so the retry can resume", meta.exists())
    }

    @Test
    fun truncatedDownloadWithValidators_retainsPrefixAndResumesOnNextAttempt() = runBlocking {
        val fullData = ByteArray(100) { (it % 10 + '0'.code).toByte() }
        var requestCount = 0

        server.createContext("/model.gguf") { exchange ->
            requestCount++
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.set("ETag", "\"tag1\"")
            if (range == null) {
                // First attempt: a fresh, valid 206 that is cut short after 40 bytes.
                exchange.responseHeaders.set("Content-Range", "bytes 0-99/100")
                exchange.sendResponseHeaders(206, 40)
                exchange.responseBody.write(fullData, 0, 40)
            } else {
                // Retry: serve the remainder so the transfer completes.
                exchange.responseHeaders.set("Content-Range", "bytes 40-99/100")
                exchange.sendResponseHeaders(206, 60)
                exchange.responseBody.write(fullData, 40, 60)
            }
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        val meta = File(tempFolder.root, "test.part.meta")
        val engine = ResumableDownloadEngine()

        try {
            engine.downloadResumable("http://127.0.0.1:$serverPort/model.gguf", target, 100L)
            fail("Expected the first attempt to fail as incomplete")
        } catch (e: IncompleteDownloadException) {
            // expected
        }
        assertEquals("the verified prefix is retained", 40L, target.length())
        assertTrue("validators are retained for the resume", meta.exists())

        engine.downloadResumable("http://127.0.0.1:$serverPort/model.gguf", target, 100L)
        assertEquals("the retry must resume rather than restart", 2, requestCount)
        assertEquals(100L, target.length())
        assertArrayEquals(fullData, target.readBytes())
    }

    @Test
    fun truncatedDownloadWithoutValidators_discardsPartialFile() = runBlocking {
        server.createContext("/model.gguf") { exchange ->
            // A short 206 with no ETag/Last-Modified: nothing ties the bytes to
            // the artifact, so the prefix cannot be safely resumed.
            exchange.responseHeaders.set("Content-Range", "bytes 0-99/100")
            exchange.sendResponseHeaders(206, 40)
            exchange.responseBody.write(ByteArray(40) { 'T'.code.toByte() })
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        val meta = File(tempFolder.root, "test.part.meta")
        val engine = ResumableDownloadEngine()
        try {
            engine.downloadResumable("http://127.0.0.1:$serverPort/model.gguf", target, 100L)
            fail("Expected an incomplete-download failure")
        } catch (e: IncompleteDownloadException) {
            assertTrue(e.message!!.contains("Incomplete download"))
        }

        assertFalse("without validators the partial must be discarded", target.exists())
        assertFalse("without validators the meta must be discarded", meta.exists())
    }

    @Test
    fun resumeWithoutCorroboratingValidators_restartsFromZeroInsteadOfAppending() = runBlocking {
        val fullData = ByteArray(100) { '5'.code.toByte() }
        var requestCount = 0

        server.createContext("/model.gguf") { exchange ->
            requestCount++
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                // Valid 206 range, but no ETag/Last-Modified to tie it to the
                // already-downloaded prefix.
                exchange.responseHeaders.set("Content-Range", "bytes 40-99/100")
                exchange.sendResponseHeaders(206, 60)
                exchange.responseBody.write(ByteArray(60) { '9'.code.toByte() })
            } else {
                exchange.responseHeaders.set("ETag", "\"tag1\"")
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(fullData)
            }
            exchange.close()
        }

        val target = File(tempFolder.root, "test.part")
        target.writeBytes(ByteArray(40) { 'A'.code.toByte() })
        val meta = File(tempFolder.root, "test.part.meta")
        meta.writeText(JSONObject().put("etag", "tag1").put("expected_size", 100L).toString())

        val engine = ResumableDownloadEngine()
        engine.downloadResumable(
            downloadUrl = "http://127.0.0.1:$serverPort/model.gguf",
            target = target,
            expectedSize = 100L,
        )

        assertEquals("an unverifiable resume must restart rather than append", 2, requestCount)
        assertEquals(100L, target.length())
        assertArrayEquals(fullData, target.readBytes())
    }
}
