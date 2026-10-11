package com.lusion.sgauto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandExecutorTest {
    @Test fun commandsAreSerializedEvenWhenFirstOwnerIsCancelled() = runTest {
        val executor = CommandExecutor()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch {
            executor.execute {
                events.add("open-start")
                started.complete(Unit)
                release.await()
                events.add("open-end")
            }
        }
        started.await()
        first.cancel()
        val second = launch { executor.execute { events.add("close") } }
        yield()
        assertEquals(listOf("open-start"), events)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("open-start", "open-end", "close"), events)
    }

    @Test fun cancelledQueuedCommandIsNotDispatched() = runTest {
        val executor = CommandExecutor()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var dispatched = false
        val first = launch { executor.execute { started.complete(Unit); release.await() } }
        started.await()
        val queued = launch { executor.execute { dispatched = true } }
        yield()
        queued.cancel()
        release.complete(Unit)
        first.join()
        queued.join()
        assertEquals(false, dispatched)
    }

    @Test fun failureReleasesExecutionOwnership() = runTest {
        val executor = CommandExecutor()
        try { executor.execute { error("dispatch failed") } } catch (_: IllegalStateException) { }
        assertEquals("next", executor.execute { "next" })
    }
}
