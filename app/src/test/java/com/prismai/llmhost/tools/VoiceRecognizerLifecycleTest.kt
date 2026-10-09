package com.prismai.llmhost.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognizerLifecycleTest {

    /**
     * onResults and onError both cleared isListening without destroying the
     * recognizer, and stopListening() early-returns once isListening is false,
     * so shutdown() could never clean it up. The recognizer leaked on the
     * normal success path.
     */
    @Test
    fun completionReleasesTheRecognizerEvenWhenNotListening() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        assertTrue(lifecycle.isCreated)

        lifecycle.onTerminated()

        assertTrue("terminated session must release the recognizer", lifecycle.releaseRequested)
    }

    @Test
    fun repeatedTerminationReleasesOnlyOnce() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        lifecycle.onTerminated()
        lifecycle.onTerminated()
        assertEquals(1, lifecycle.releaseCount)
    }

    @Test
    fun shutdownReleasesAnAbandonedRecognizer() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        lifecycle.onTerminated()   // clears listening state without destroying
        lifecycle.shutdown()      // previously could not clean up
        assertTrue(lifecycle.releaseRequested)
    }
}
