package com.prismai.llmhost.generation

import com.prismai.llmhost.generation.GenerationOrchestrator.GenerationLaunch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer may clear its draft only for a launch result that means the message was actually
 * accepted. This pins the mapping used by [com.prismai.llmhost.service.InferenceService.generateSafely]'s
 * `onAccepted`.
 */
class SendLaunchAcceptanceTest {

    @Test
    fun startedAndHandledCountAsAccepted() {
        assertTrue(GenerationLaunch.STARTED.acceptsMessage())
        assertTrue(GenerationLaunch.HANDLED.acceptsMessage())
    }

    @Test
    fun refusedIsNotAccepted() {
        assertFalse(
            "a refused launch must leave the composer draft intact",
            GenerationLaunch.REFUSED.acceptsMessage(),
        )
    }
}
