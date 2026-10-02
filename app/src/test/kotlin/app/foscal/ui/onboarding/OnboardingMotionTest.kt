package app.foscal.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingMotionTest {

    @Test
    fun `phase is clamped to its window`() {
        assertEquals(0f, OnboardingMotion.phase(0.1f, start = 0.2f, length = 0.4f), 0f)
        assertEquals(0.5f, OnboardingMotion.phase(0.4f, start = 0.2f, length = 0.4f), 1e-6f)
        assertEquals(1f, OnboardingMotion.phase(0.9f, start = 0.2f, length = 0.4f), 0f)
    }

    @Test
    fun `backOut starts at zero, ends at one and overshoots in between`() {
        assertEquals(0f, OnboardingMotion.backOut(0f), 1e-6f)
        assertEquals(1f, OnboardingMotion.backOut(1f), 1e-6f)
        assertTrue((1..99).any { OnboardingMotion.backOut(it / 100f) > 1f })
    }

    @Test
    fun `the finished intro is the static mark`() {
        assertEquals(1f, OnboardingMotion.groundScale(1f), 1e-6f)
        assertEquals(1f, OnboardingMotion.todayScale(1f), 1e-6f)
        repeat(8) { assertEquals(1f, OnboardingMotion.cellScale(1f, it), 1e-6f) }
    }

    @Test
    fun `days appear in reading order and today lands last`() {
        val firstVisible = (0 until 8).map { cell ->
            (0..1000).first { OnboardingMotion.cellScale(it / 1000f, cell) > 0f }
        }
        assertEquals(firstVisible.sorted(), firstVisible)
        val todayVisible = (0..1000).first { OnboardingMotion.todayScale(it / 1000f) > 0f }
        assertTrue(firstVisible.all { it < todayVisible })
        assertEquals(0f, OnboardingMotion.todayScale(OnboardingMotion.TodayStart), 0f)
    }

    @Test
    fun `the scan wave lights one diagonal at a time`() {
        val atStart = OnboardingMotion.scanIntensity(1f / 6f, row = 0, col = 0, rows = 5, cols = 7)
        val farCorner = OnboardingMotion.scanIntensity(1f / 6f, row = 4, col = 6, rows = 5, cols = 7)
        assertEquals(1f, atStart, 1e-6f)
        assertEquals(0f, farCorner, 0f)
    }

    @Test
    fun `every cell is filled once the grid has settled`() {
        repeat(35) { assertEquals(1f, OnboardingMotion.settleFill(1f, it, 35), 1e-6f) }
        assertEquals(0f, OnboardingMotion.settleFill(0f, 34, 35), 0f)
    }

    @Test
    fun `each wait outlasts its own animation once the step has arrived`() {
        assertTrue(OnboardingMotion.StepArrivedMillis + OnboardingMotion.ScanPeriodMillis <= OnboardingMotion.PreparingMillis)
        assertTrue(OnboardingMotion.StepArrivedMillis + OnboardingMotion.SettleMillis < OnboardingMotion.SettlingMillis)
    }
}
