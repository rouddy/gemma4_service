package com.example.gemma4service

import com.example.gemma4service.inference.GemmaInferenceEngine
import com.example.gemma4service.model.LlmRequest
import com.example.gemma4service.model.LlmState
import com.example.gemma4service.service.LlmQueueManager
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import com.example.gemma4service.ILlmCallback

class LlmQueueManagerTest {

    private lateinit var mockEngine: GemmaInferenceEngine
    private lateinit var queueManager: LlmQueueManager

    @Before
    fun setUp() {
        mockEngine = mock(GemmaInferenceEngine::class.java)
        `when`(mockEngine.generate(anyString())).thenReturn(Observable.empty())
        queueManager = LlmQueueManager(mockEngine)
    }

    @Test
    fun `enqueue adds request and emits Waiting state`() {
        val states = mutableListOf<LlmState>()
        queueManager.stateStream.subscribe { states.add(it) }

        val callback = mock(ILlmCallback::class.java)
        val request = LlmRequest("req1", "Hello", callback)
        queueManager.enqueue(request)

        assertTrue(states.any { it is LlmState.Waiting && (it as LlmState.Waiting).requestId == "req1" })
    }

    @Test
    fun `cancel removes request from queue`() {
        val callback = mock(ILlmCallback::class.java)
        val request1 = LlmRequest("req1", "Hello", callback)
        val request2 = LlmRequest("req2", "World", callback)

        // block processing so req2 stays in queue
        `when`(mockEngine.generate(anyString())).thenReturn(Observable.never())
        queueManager.enqueue(request1)
        queueManager.enqueue(request2)

        assertEquals(1, queueManager.queueSize())
        val cancelled = queueManager.cancel("req2")
        assertTrue(cancelled)
        assertEquals(0, queueManager.queueSize())
    }

    @Test
    fun `getQueueSize returns correct count`() {
        `when`(mockEngine.generate(anyString())).thenReturn(Observable.never())
        val callback = mock(ILlmCallback::class.java)
        assertEquals(0, queueManager.queueSize())
        queueManager.enqueue(LlmRequest("r1", "p1", callback))
        queueManager.enqueue(LlmRequest("r2", "p2", callback))
        // r1 is processing, r2 is in queue
        assertEquals(1, queueManager.queueSize())
    }
}
