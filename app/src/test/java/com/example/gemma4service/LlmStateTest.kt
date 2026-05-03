package com.example.gemma4service

import com.example.gemma4service.model.LlmState
import org.junit.Assert.*
import org.junit.Test

class LlmStateTest {

    @Test
    fun `Waiting state holds requestId and queuePosition`() {
        val state = LlmState.Waiting("req1", 3)
        assertEquals("req1", state.requestId)
        assertEquals(3, state.queuePosition)
    }

    @Test
    fun `Processing state holds requestId and partialText`() {
        val state = LlmState.Processing("req2", "Hello")
        assertEquals("req2", state.requestId)
        assertEquals("Hello", state.partialText)
    }

    @Test
    fun `Completed state holds requestId and fullText`() {
        val state = LlmState.Completed("req3", "Hello World")
        assertEquals("req3", state.requestId)
        assertEquals("Hello World", state.fullText)
    }

    @Test
    fun `Error state holds requestId and reason`() {
        val state = LlmState.Error("req4", "cancelled")
        assertEquals("req4", state.requestId)
        assertEquals("cancelled", state.reason)
    }
}
