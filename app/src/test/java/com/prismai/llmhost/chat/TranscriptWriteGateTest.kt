package com.prismai.llmhost.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptWriteGateTest {
    @Test
    fun newerSnapshotRejectsAnOlderPublish() {
        val gate = TranscriptWriteGate()
        val first = gate.snapshotRevision("chat-a")
        val second = gate.snapshotRevision("chat-a")
        var published = false

        assertFalse(gate.publish("chat-a", first) { published = true })
        assertFalse(published)
        assertTrue(gate.publish("chat-a", second) { published = true })
        assertTrue(published)
    }

    @Test
    fun queuedSnapshotCannotPublishAfterClearInvalidatesItsRevision() {
        val gate = TranscriptWriteGate()
        val revision = gate.snapshotRevision("chat-a")
        var published = false

        gate.invalidateAndRun("chat-a") { }
        val accepted = gate.publish("chat-a", revision) { published = true }

        assertFalse(accepted)
        assertFalse(published)
    }

    @Test
    fun deletedOwnerCannotPublishEvenWithRevisionCapturedAfterDeletion() {
        val gate = TranscriptWriteGate()
        gate.invalidateAndRun("deleted-chat", retireOwner = true) { }
        var published = false

        val accepted = gate.publish(
            "deleted-chat",
            gate.snapshotRevision("deleted-chat"),
        ) { published = true }

        assertFalse(accepted)
        assertFalse(published)
    }

    @Test
    fun deleteWaitsForActivePublicationThenRemovesItAndRejectsQueuedSnapshot() {
        val gate = TranscriptWriteGate()
        val revision = gate.snapshotRevision("chat-a")
        val writerInsideGate = CountDownLatch(1)
        val allowWriterToFinish = CountDownLatch(1)
        val deletionStarted = CountDownLatch(1)
        val deletionFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var storedTranscript = false
        try {
            executor.submit {
                gate.publish("chat-a", revision) {
                    writerInsideGate.countDown()
                    check(allowWriterToFinish.await(2, TimeUnit.SECONDS))
                    storedTranscript = true
                }
            }
            assertTrue(writerInsideGate.await(2, TimeUnit.SECONDS))

            val deletion = executor.submit {
                deletionStarted.countDown()
                gate.invalidateAndRun("chat-a") { storedTranscript = false }
                deletionFinished.countDown()
            }
            assertTrue(deletionStarted.await(2, TimeUnit.SECONDS))
            assertFalse("delete must wait for the active atomic publication", deletionFinished.await(30, TimeUnit.MILLISECONDS))
            allowWriterToFinish.countDown()
            deletion.get(2, TimeUnit.SECONDS)

            assertFalse(storedTranscript)
            assertFalse(gate.publish("chat-a", revision) { storedTranscript = true })
            assertFalse(storedTranscript)
        } finally {
            allowWriterToFinish.countDown()
            executor.shutdownNow()
        }
    }
}
