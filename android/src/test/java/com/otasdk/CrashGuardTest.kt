package com.otasdk

import android.util.Log
import com.otasdk.CrashGuard.Decision
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CrashGuardTest {

    private lateinit var prefs: OTAPrefs
    private lateinit var bundleManager: BundleManager
    private lateinit var crashGuard: CrashGuard

    @Before
    fun setup() {
        // android.util.Log is a stub that throws in local unit tests.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        prefs = mockk(relaxed = true)
        bundleManager = mockk(relaxed = true)
        crashGuard = CrashGuard(prefs, bundleManager)
        every { prefs.crashThreshold } returns 3
        every { prefs.maxUnconfirmedLaunches } returns 5
        every { prefs.rollbackOnCrash } returns true
        every { prefs.fatalJsCrashHash } returns ""
        every { prefs.loadFailedHash } returns ""
        every { prefs.awaitingJsStart } returns false
        every { bundleManager.bundleProblem(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun activeBundle(confirmed: Boolean, crashes: Int = 0, launches: Int = 0) {
        every { prefs.activeBundlePath } returns "/ota/abc/index.android.bundle"
        every { prefs.activeBundleHash } returns "abc"
        every { prefs.activeBundleConfirmed } returns confirmed
        every { prefs.crashCount } returns crashes
        every { prefs.launchCount } returns launches
    }

    private fun evaluate(
        hasOtaBundle: Boolean = true,
        confirmed: Boolean = false,
        failedToStart: Boolean = false,
        rollbackOnCrash: Boolean = true,
        diedOfJsFatal: Boolean = false,
        crashes: Int = 0,
        launches: Int = 0,
        crashThreshold: Int = 3,
        maxUnconfirmedLaunches: Int = 5,
    ) = CrashGuard.evaluateLaunch(
        hasOtaBundle, confirmed, failedToStart, rollbackOnCrash, diedOfJsFatal, crashes, launches, crashThreshold, maxUnconfirmedLaunches,
    )

    // ── evaluateLaunch ────────────────────────────────────────────────

    @Test
    fun `evaluateLaunch does not guard the packaged bundle`() {
        assertEquals(Decision.NOT_GUARDED, evaluate(hasOtaBundle = false, diedOfJsFatal = true, crashes = 99))
    }

    @Test
    fun `evaluateLaunch does not guard a confirmed bundle`() {
        assertEquals(Decision.NOT_GUARDED, evaluate(confirmed = true, diedOfJsFatal = true, crashes = 99))
    }

    @Test
    fun `evaluateLaunch loads an unconfirmed bundle within its limits`() {
        assertEquals(Decision.LOAD, evaluate(crashes = 2, launches = 4))
    }

    @Test
    fun `evaluateLaunch rolls back a bundle that failed to start`() {
        assertEquals(Decision.ROLL_BACK_LOAD_FAILED, evaluate(failedToStart = true))
    }

    @Test
    fun `without rollbackOnCrash, crashes after the bundle started never roll back`() {
        assertEquals(Decision.LOAD, evaluate(rollbackOnCrash = false, diedOfJsFatal = true))
        assertEquals(Decision.LOAD, evaluate(rollbackOnCrash = false, crashes = 99))
        assertEquals(Decision.LOAD, evaluate(rollbackOnCrash = false, launches = 99))
    }

    @Test
    fun `without rollbackOnCrash, a bundle that failed to start still rolls back`() {
        assertEquals(
            Decision.ROLL_BACK_LOAD_FAILED,
            evaluate(rollbackOnCrash = false, failedToStart = true, crashes = 1),
        )
    }

    @Test
    fun `evaluateLaunch rolls back after one fatal JS error`() {
        assertEquals(Decision.ROLL_BACK_JS_FATAL, evaluate(diedOfJsFatal = true))
    }

    @Test
    fun `evaluateLaunch rolls back after threshold crashes`() {
        assertEquals(Decision.ROLL_BACK_CRASHES, evaluate(crashes = 3))
    }

    @Test
    fun `evaluateLaunch rolls back a bundle that is never confirmed`() {
        assertEquals(Decision.ROLL_BACK_NOT_CONFIRMED, evaluate(launches = 5))
    }

    @Test
    fun `evaluateLaunch treats limits below one as one`() {
        assertEquals(Decision.LOAD, evaluate(crashThreshold = 0, maxUnconfirmedLaunches = 0))
        assertEquals(Decision.ROLL_BACK_CRASHES, evaluate(crashes = 1, crashThreshold = 0))
    }

    // ── recordPreviousSession ─────────────────────────────────────────

    @Test
    fun `a session that died in the foreground counts as a crash`() {
        activeBundle(confirmed = false)
        every { prefs.sessionInForeground } returns true

        crashGuard.recordPreviousSession(exitRecordedAsCrash = false)

        verify { prefs.endPreviousSession(crashed = true) }
    }

    @Test
    fun `an exit the OS recorded as a crash counts as a crash`() {
        activeBundle(confirmed = false)
        every { prefs.sessionInForeground } returns false

        crashGuard.recordPreviousSession(exitRecordedAsCrash = true)

        verify { prefs.endPreviousSession(crashed = true) }
    }

    @Test
    fun `a session that ended in the background is not a crash`() {
        activeBundle(confirmed = false)
        every { prefs.sessionInForeground } returns false

        crashGuard.recordPreviousSession(exitRecordedAsCrash = false)

        verify { prefs.endPreviousSession(crashed = false) }
    }

    @Test
    fun `sessions of a confirmed bundle are never counted`() {
        activeBundle(confirmed = true)
        every { prefs.sessionInForeground } returns true

        crashGuard.recordPreviousSession(exitRecordedAsCrash = true)

        verify(exactly = 0) { prefs.endPreviousSession(any()) }
        verify { prefs.sessionInForeground = false }
    }

    // ── Broken bundles ────────────────────────────────────────────────

    @Test
    fun `a bundle that cannot load is rolled back before it is tried`() {
        activeBundle(confirmed = false)
        var path: String? = "/ota/abc/index.android.bundle"
        every { prefs.activeBundlePath } answers { path }
        every { bundleManager.bundleProblem("/ota/abc/index.android.bundle") } returns "truncated"
        every { bundleManager.rollbackToPrevious(any()) } answers { path = "/ota/good/index.android.bundle" }

        assertTrue(crashGuard.checkBeforeLoad())
        verify(exactly = 1) { bundleManager.rollbackToPrevious("bundle_invalid") }
    }

    @Test
    fun `a confirmed bundle damaged on disk is rolled back too`() {
        activeBundle(confirmed = true)
        var path: String? = "/ota/abc/index.android.bundle"
        every { prefs.activeBundlePath } answers { path }
        every { bundleManager.bundleProblem(any()) } returns "the bundle file is missing"
        every { bundleManager.rollbackToPrevious(any()) } answers { path = null }

        assertTrue(crashGuard.checkBeforeLoad())
        verify { bundleManager.rollbackToPrevious("bundle_invalid") }
    }

    @Test
    fun `a broken rollback target is rolled past, to the packaged bundle`() {
        activeBundle(confirmed = false)
        val paths = ArrayDeque(listOf<String?>("/ota/abc/b", "/ota/prev/b", null))
        every { prefs.activeBundlePath } answers { paths.first() }
        every { bundleManager.bundleProblem(any()) } returns "corrupt"
        every { bundleManager.rollbackToPrevious(any()) } answers { paths.removeFirst() }

        assertTrue(crashGuard.checkBeforeLoad())
        verify(exactly = 2) { bundleManager.rollbackToPrevious("bundle_invalid") }
    }

    @Test
    fun `a bundle whose JS never started is rolled back after one launch`() {
        activeBundle(confirmed = false)
        var awaiting = true
        var failedHash = ""
        every { prefs.awaitingJsStart } answers { awaiting }
        every { prefs.awaitingJsStart = any() } answers { awaiting = firstArg() }
        every { prefs.loadFailedHash } answers { failedHash }
        every { prefs.loadFailedHash = any() } answers { failedHash = firstArg() }

        crashGuard.recordPreviousSession(exitRecordedAsCrash = false)

        assertTrue(crashGuard.checkBeforeLoad())
        verify { bundleManager.rollbackToPrevious("bundle_load_failed") }
    }

    @Test
    fun `going to the background before JS starts is not a load failure`() {
        activeBundle(confirmed = false)
        every { prefs.awaitingJsStart } returns true

        crashGuard.onBackground()

        verify { prefs.awaitingJsStart = false }
    }

    // ── checkBeforeLoad ───────────────────────────────────────────────

    @Test
    fun `checkBeforeLoad rolls back with the reason`() {
        activeBundle(confirmed = false, crashes = 3)

        assertTrue(crashGuard.checkBeforeLoad())
        verify { bundleManager.rollbackToPrevious("crash_threshold") }
    }

    @Test
    fun `by default a crash while the app is in use does not roll back`() {
        activeBundle(confirmed = false, crashes = 3)
        every { prefs.rollbackOnCrash } returns false
        every { prefs.fatalJsCrashHash } returns "abc"

        assertFalse(crashGuard.checkBeforeLoad())
        verify(exactly = 0) { bundleManager.rollbackToPrevious(any()) }
    }

    @Test
    fun `checkBeforeLoad rolls back a bundle that died of a fatal JS error`() {
        activeBundle(confirmed = false)
        every { prefs.fatalJsCrashHash } returns "abc"

        assertTrue(crashGuard.checkBeforeLoad())
        verify { bundleManager.rollbackToPrevious("js_fatal") }
    }

    @Test
    fun `checkBeforeLoad ignores a fatal JS error from another bundle`() {
        activeBundle(confirmed = false)
        every { prefs.fatalJsCrashHash } returns "older-bundle"

        assertFalse(crashGuard.checkBeforeLoad())
    }

    @Test
    fun `checkBeforeLoad never rolls back a confirmed bundle`() {
        activeBundle(confirmed = true, crashes = 10, launches = 10)

        assertFalse(crashGuard.checkBeforeLoad())
        verify(exactly = 0) { bundleManager.rollbackToPrevious(any()) }
    }

    // ── Foreground tracking, launches, JS fatal ───────────────────────

    @Test
    fun `recordLaunchAttempt counts a launch of an unconfirmed bundle`() {
        activeBundle(confirmed = false, launches = 1)

        crashGuard.recordLaunchAttempt()

        verify { prefs.launchCount = 2 }
    }

    @Test
    fun `recordLaunchAttempt does not count the packaged bundle`() {
        every { prefs.activeBundlePath } returns null

        crashGuard.recordLaunchAttempt()

        verify(exactly = 0) { prefs.launchCount = any() }
    }

    @Test
    fun `foreground sessions are only tracked while unconfirmed`() {
        activeBundle(confirmed = true)
        crashGuard.onForeground()
        verify(exactly = 0) { prefs.sessionInForeground = true }

        activeBundle(confirmed = false)
        crashGuard.onForeground()
        verify { prefs.sessionInForeground = true }
    }

    @Test
    fun `recordJsFatal blames the active unconfirmed bundle`() {
        activeBundle(confirmed = false)

        crashGuard.recordJsFatal()

        verify { prefs.fatalJsCrashHash = "abc" }
    }

    // ── markStable ────────────────────────────────────────────────────

    @Test
    fun `markStable confirms an unconfirmed bundle`() {
        activeBundle(confirmed = false, crashes = 2)

        crashGuard.markStable()

        verify { prefs.confirmActiveBundle() }
    }

    @Test
    fun `markStable does nothing for an already confirmed bundle`() {
        activeBundle(confirmed = true)

        crashGuard.markStable()

        verify(exactly = 0) { prefs.confirmActiveBundle() }
    }

    // ── Full sequences ────────────────────────────────────────────────

    /** Plays launches against an in-memory copy of the guard's prefs. */
    private fun simulate(sessions: List<Boolean>): List<Boolean> {
        var crashes = 0
        var launches = 0
        var inForeground = false
        every { prefs.activeBundlePath } returns "/ota/abc/index.android.bundle"
        every { prefs.activeBundleHash } returns "abc"
        every { prefs.activeBundleConfirmed } returns false
        every { prefs.crashCount } answers { crashes }
        every { prefs.launchCount } answers { launches }
        every { prefs.launchCount = any() } answers { launches = firstArg() }
        every { prefs.sessionInForeground } answers { inForeground }
        every { prefs.sessionInForeground = any() } answers { inForeground = firstArg() }
        every { prefs.endPreviousSession(any()) } answers {
            inForeground = false
            if (firstArg()) crashes++
        }

        return sessions.map { crashes ->
            crashGuard.recordPreviousSession(exitRecordedAsCrash = false)
            val rolledBack = crashGuard.checkBeforeLoad()
            if (!rolledBack) {
                crashGuard.recordLaunchAttempt()
                crashGuard.onForeground()
                if (!crashes) crashGuard.onBackground() // user closes the app
            }
            rolledBack
        }
    }

    @Test
    fun `a bundle that crashes every launch is rolled back after threshold crashes`() {
        assertEquals(listOf(false, false, false, true), simulate(List(4) { true }))
    }

    @Test
    fun `a user closing the app quickly is not a crash, until the never-confirmed limit`() {
        assertEquals(listOf(false, false, false, false, false, true), simulate(List(6) { false }))
    }
}
