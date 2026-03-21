package com.otasdk

import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CrashGuardTest {

    private lateinit var prefs: OTAPrefs
    private lateinit var crashGuard: CrashGuard

    @Before
    fun setup() {
        prefs = mockk(relaxed = true)
        crashGuard = CrashGuard(prefs, threshold = 3)
    }

    @Test
    fun `onAppStart resets crash count when bundle hash changed`() {
        every { prefs.activeBundleHash } returns "new-hash"
        every { prefs.lastAppliedBundleHash } returns "old-hash"

        val shouldRollback = crashGuard.onAppStart()

        assertFalse(shouldRollback)
        verify { prefs.lastAppliedBundleHash = "new-hash" }
        verify { prefs.resetCrashCount() }
    }

    @Test
    fun `onAppStart increments crash count for same bundle`() {
        every { prefs.activeBundleHash } returns "same-hash"
        every { prefs.lastAppliedBundleHash } returns "same-hash"
        every { prefs.crashCount } returns 1

        val shouldRollback = crashGuard.onAppStart()

        assertFalse(shouldRollback)
        verify { prefs.crashCount = 2 }
    }

    @Test
    fun `onAppStart returns true when crash threshold is reached`() {
        every { prefs.activeBundleHash } returns "bad-hash"
        every { prefs.lastAppliedBundleHash } returns "bad-hash"
        every { prefs.crashCount } returns 2 // +1 = 3 = threshold

        val shouldRollback = crashGuard.onAppStart()

        assertTrue(shouldRollback)
    }

    @Test
    fun `markStable resets crash count when non-zero`() {
        every { prefs.crashCount } returns 2

        crashGuard.markStable()

        verify { prefs.resetCrashCount() }
    }

    @Test
    fun `markStable does not reset when crash count is already zero`() {
        every { prefs.crashCount } returns 0

        crashGuard.markStable()

        verify(exactly = 0) { prefs.resetCrashCount() }
    }
}
