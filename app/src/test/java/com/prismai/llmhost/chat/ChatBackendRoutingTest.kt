package com.prismai.llmhost.chat

import com.prismai.llmhost.work.ExecutionTargetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatBackendRoutingTest {

    @Test
    fun chatBackendAndExecutionTargetAreIndependentAxes() {
        val chatLocal = ChatBackend.LOCAL_LLAMA
        val chatCloud = ChatBackend.PRISMATIX_CLOUD

        val workLocal = ExecutionTargetId.ANDROID_LOCAL
        val workBabel = ExecutionTargetId.BABEL_HOST

        // Verify they are separate types and values
        assertNotEquals(chatLocal.name, workLocal.name)
        assertNotEquals(chatCloud.name, workBabel.name)

        assertEquals("LOCAL_LLAMA", chatLocal.name)
        assertEquals("PRISMATIX_CLOUD", chatCloud.name)
        assertEquals("ANDROID_LOCAL", workLocal.name)
        assertEquals("BABEL_HOST", workBabel.name)
    }
}
