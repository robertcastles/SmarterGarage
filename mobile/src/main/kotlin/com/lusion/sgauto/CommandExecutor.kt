package com.lusion.sgauto

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancel queued work freely; once dispatch begins, finish it before admitting another command. */
internal class CommandExecutor {
    private val mutex = Mutex()

    suspend fun <T> execute(dispatch: suspend () -> T): T = mutex.withLock {
        withContext(NonCancellable) { dispatch() }
    }
}
