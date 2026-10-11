package com.lusion.sgauto

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ServiceCommandOwnerTest {
    @Test fun queuedCommandsSurviveMonitorReplacementAndDeferRelease() = runTest {
        val executor = CommandExecutor()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val blocker = launch { executor.execute { started.complete(Unit); release.await() } }
        started.await()
        val owner = ServiceCommandOwner(this)
        val events = mutableListOf<String>()
        owner.admit { executor.execute { events.add("open") } }
        owner.admit { executor.execute { events.add("close") } }
        assertTrue(owner.hasCommands)
        val monitor = launch { owner.awaitIdle() }
        yield()
        monitor.cancelAndJoin()
        assertTrue(owner.hasCommands) // soft stop must defer; cancellation belongs only to monitor
        assertTrue(events.isEmpty())
        release.complete(Unit)
        blocker.join()
        owner.awaitIdle()
        assertEquals(listOf("open", "close"), events)
        assertFalse(owner.hasCommands)
    }

    @Test fun actualServiceCancellationStillDropsQueuedAdmission() = runTest {
        val executor = CommandExecutor()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val blocker = launch { executor.execute { started.complete(Unit); release.await() } }
        started.await()
        val service = CoroutineScope(coroutineContext + SupervisorJob())
        val owner = ServiceCommandOwner(service)
        var sent = false
        owner.admit { executor.execute { sent = true } }
        yield()
        service.cancel()
        owner.awaitIdle()
        release.complete(Unit)
        blocker.join()
        assertFalse(sent)
        assertFalse(owner.hasCommands)
    }

    @Test fun handledFailureReleasesOwnershipWithoutReplay() = runTest {
        val owner = ServiceCommandOwner(this)
        var attempts = 0
        owner.admit { try { attempts++; error("rejected") } catch (_: IllegalStateException) { } }
        owner.awaitIdle()
        assertFalse(owner.hasCommands)
        assertEquals(1, attempts)
    }
}
