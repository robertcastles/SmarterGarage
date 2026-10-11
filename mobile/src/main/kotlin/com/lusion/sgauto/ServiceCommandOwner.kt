package com.lusion.sgauto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Confined to the service's main dispatcher. Monitor cancellation never owns these jobs. */
internal class ServiceCommandOwner(private val scope: CoroutineScope) {
    private val commands = mutableSetOf<Job>()
    val hasCommands: Boolean get() = commands.isNotEmpty()

    fun admit(operation: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { operation() }
        commands.add(job)
        job.invokeOnCompletion { commands.remove(job) }
        job.start()
    }

    suspend fun awaitIdle() {
        while (commands.isNotEmpty()) commands.toList().forEach { it.join() }
    }
}
