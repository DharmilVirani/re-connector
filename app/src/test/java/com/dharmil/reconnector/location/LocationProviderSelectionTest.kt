package com.dharmil.reconnector.location

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationProviderSelectionTest {
    @Test
    fun usesNetworkProviderWhenFineLocationIsAllowedButGpsIsDisabled() {
        assertEquals("network", selectLocationProvider(true, gpsEnabled = false, networkEnabled = true))
    }

    @Test
    fun prefersGpsWhenFineLocationIsAllowedAndGpsIsEnabled() {
        assertEquals("gps", selectLocationProvider(true, gpsEnabled = true, networkEnabled = true))
    }

    @Test
    fun fallsBackToEnabledGpsProviderWhenOnlyCoarsePermissionIsAvailable() {
        assertEquals("gps", selectLocationProvider(false, gpsEnabled = true, networkEnabled = false))
    }

    @Test
    fun returnsNoProviderWhenLocationServicesAreDisabled() {
        assertNull(selectLocationProvider(false, gpsEnabled = false, networkEnabled = false))
    }

    @Test
    fun rejectsInvalidCoordinateBeforeItCanReachNavigationState() {
        assertThrows(IllegalArgumentException::class.java) {
            locationFixFromFields(
                latitude = 91.0,
                longitude = 73.8,
                timestampMillis = 1L,
                horizontalAccuracyMeters = null,
            )
        }
    }

    @Test
    fun cleansUpRequestThatCompletesDuringRegistration() {
        val completed = AtomicBoolean(false)
        var cleanupCount = 0

        registerLocationRequest(
            completed = completed,
            register = { completed.set(true) },
            cleanup = { cleanupCount++ },
        )

        assertEquals(1, cleanupCount)
    }

    @Test
    fun doesNotRegisterAfterRequestWasAlreadyCancelled() {
        val completed = AtomicBoolean(true)
        var registrationCount = 0

        registerLocationRequest(
            completed = completed,
            register = { registrationCount++ },
            cleanup = {},
        )

        assertEquals(0, registrationCount)
    }
}
