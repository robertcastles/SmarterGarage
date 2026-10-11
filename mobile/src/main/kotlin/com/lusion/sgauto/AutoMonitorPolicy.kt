package com.lusion.sgauto

internal object AutoMonitorPolicy {
    fun mayFinish(watchUntilClosed: Boolean, result: StatusFetchResult): Boolean {
        val status = (result as? StatusFetchResult.Fresh)?.status ?: return false
        return status == GarageUtility.GarageStatus.CLOSED ||
            (!watchUntilClosed && status == GarageUtility.GarageStatus.OPEN)
    }
}
