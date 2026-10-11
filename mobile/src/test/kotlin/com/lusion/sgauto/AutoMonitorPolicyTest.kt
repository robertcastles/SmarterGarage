package com.lusion.sgauto

import com.lusion.sgauto.GarageUtility.GarageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMonitorPolicyTest {
    @Test fun disconnectWatchRequiresClosed() {
        assertFalse(AutoMonitorPolicy.mayFinish(true, StatusFetchResult.Fresh(GarageStatus.OPEN, 1)))
        assertTrue(AutoMonitorPolicy.mayFinish(true, StatusFetchResult.Fresh(GarageStatus.CLOSED, 1)))
    }
    @Test fun normalSessionMayFinishOpen() {
        assertTrue(AutoMonitorPolicy.mayFinish(false, StatusFetchResult.Fresh(GarageStatus.OPEN, 1)))
    }
    @Test fun unconfirmedPollsNeverFinish() {
        listOf(true, false).forEach { watch ->
            assertFalse(AutoMonitorPolicy.mayFinish(watch, StatusFetchResult.Failed()))
            assertFalse(AutoMonitorPolicy.mayFinish(watch, StatusFetchResult.Skipped(StatusFetchResult.Reason.BUDGET)))
            assertFalse(AutoMonitorPolicy.mayFinish(watch, StatusFetchResult.Fresh(GarageStatus.OPENING, 1)))
        }
    }
}
