package com.dharmil.reconnector.navigation.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadBudgetTest {
    @Test
    fun stopsBeforeEitherPublishedProviderLimit() {
        assertFalse(OfflineDownloadBudget.exceeded(2_799L, 31_999_999L))
        assertTrue(OfflineDownloadBudget.exceeded(2_800L, 0L))
        assertTrue(OfflineDownloadBudget.exceeded(0L, 32_000_000L))
    }
}
