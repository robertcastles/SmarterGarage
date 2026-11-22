package com.lusion.sgauto

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class AutoArrivalLeaseTest {
    @Test fun statusChangeRejectsPendingLocationBeforeArrivalAdmission() {
        val lease = AutoArrivalLease()
        val pendingLocation = lease.generation
        lease.invalidate()
        assertNull(lease.admit(pendingLocation))
        assertFalse(lease.active)
        assertNotNull(lease.admit(lease.generation))
    }

    @Test fun onlyOneArrivalCanOwnTheSurface() {
        val lease = AutoArrivalLease()
        val token = lease.admit(lease.generation)!!
        assertNull(lease.admit(lease.generation))
        assertTrue(lease.active)
        assertTrue(lease.release(token))
        assertFalse(lease.active)
        assertFalse(lease.release(token))
    }

    @Test fun disconnectRejectsOldLocationAndExpiryCallbacks() {
        val lease = AutoArrivalLease()
        val generation = lease.generation
        val sessionGeneration = lease.sessionGeneration
        val token = lease.admit(generation)!!
        lease.invalidateSession()
        assertNull(lease.admit(generation))
        assertNull(lease.admitForSession(sessionGeneration))
        assertFalse(lease.release(token))
        val replacement = lease.admit(lease.generation)!!
        assertFalse(lease.release(token))
        assertTrue(lease.active)
        assertTrue(lease.release(replacement))
    }

    @Test fun startupDelaySurvivesDoorMovementAndAdmitsLatestStatusOnlyOnce() = runTest {
        val lease = AutoArrivalLease()
        val sessionGeneration = lease.sessionGeneration
        var status = "CLOSED"
        val posted = mutableListOf<String>()
        val startup = launch {
            delay(10_000.milliseconds)
            if (lease.admitForSession(sessionGeneration) != null) posted.add(status)
        }
        delay(2_000.milliseconds)
        status = "OPENING"
        lease.invalidate()
        delay(4_000.milliseconds)
        status = "OPEN"
        lease.invalidate()
        startup.join()
        assertEquals(listOf("OPEN"), posted)
        assertNull(lease.admitForSession(sessionGeneration))
    }

    @Test fun statusChangeDuringLocationLookupUsesCurrentAdmissionGeneration() {
        val lease = AutoArrivalLease()
        val pendingSession = lease.sessionGeneration
        val pendingStatus = lease.generation
        lease.invalidate()
        assertNull(lease.admit(pendingStatus))
        assertNotNull(lease.admitForSession(pendingSession))
    }

    @Test fun reconnectCannotReviveOldStartupEvenWhenNoArrivalWasActive() {
        val lease = AutoArrivalLease()
        val oldSession = lease.sessionGeneration
        lease.invalidateSession()
        assertNull(lease.admitForSession(oldSession))
        assertNotNull(lease.admitForSession(lease.sessionGeneration))
    }

    @Test fun staleExpiryCannotReleaseNewArrival() {
        val lease = AutoArrivalLease()
        val old = lease.admit(lease.generation)!!
        assertTrue(lease.release(old))
        val current = lease.admit(lease.generation)!!
        assertFalse(lease.release(old))
        assertTrue(lease.release(current))
    }
}
