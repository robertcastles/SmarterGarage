package com.lusion.sgauto

import org.junit.Assert.*
import org.junit.Test

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
        val token = lease.admit(generation)!!
        lease.invalidate()
        assertNull(lease.admit(generation))
        assertFalse(lease.release(token))
        val replacement = lease.admit(lease.generation)!!
        assertFalse(lease.release(token))
        assertTrue(lease.active)
        assertTrue(lease.release(replacement))
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

