package app.guantera

import app.guantera.presentation.onboarding.FirstUseDialog
import app.guantera.presentation.onboarding.FirstUseStep
import app.guantera.presentation.onboarding.OnboardingPresenter
import app.guantera.presentation.port.FirstUseStore
import app.guantera.presentation.port.FormOutcome
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.ImportOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFirstUse : FirstUseStore {
    override val isComplete = MutableStateFlow<Boolean?>(false)
    val calls = mutableListOf<String>()
    val answers = ArrayDeque<FormOutcome>()
    var stopped = 0

    private fun write(name: String, after: () -> Unit = {}): FormOutcome {
        val outcome = answers.removeFirstOrNull() ?: FormOutcome.Saved
        if (outcome == FormOutcome.Saved) { calls += name; after() }
        return outcome
    }

    override suspend fun saveOdometer(km: Int) = write("odometer:$km")
    override suspend fun saveCurrency(currency: String) = write("currency:$currency")
    override suspend fun loadCatalog() = write("catalog")
    override suspend fun markComplete() = write("complete") { isComplete.value = true }
    override fun confirmStopped() { stopped += 1 }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPresenterTest {
    private val data = FakeData()
    private val store = FakeFirstUse()
    private val backups = app.guantera.FakeBackups()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun TestScope.presenter() = OnboardingPresenter(data, store, backups, backgroundScope).also { p ->
        backgroundScope.launch { p.state.collect {} }
        backgroundScope.launch { p.visible.collect {} }
    }

    @Test fun `it is shown only while first use was not completed`() = test {
        val p = presenter()
        assertTrue(p.visible.value)
        store.isComplete.value = true
        assertFalse(p.visible.value)
    }

    @Test fun `with an odometer the three steps save it, the currency and the catalog`() = test {
        val p = presenter()
        assertEquals(FirstUseStep.Location, p.state.value.step)
        p.continueFromLocation()
        assertEquals(FirstUseStep.Odometer, p.state.value.step)
        p.saveOdometer("48.250")
        assertEquals(FirstUseStep.Currency, p.state.value.step)
        p.finish("US$")
        assertEquals(listOf("odometer:48250", "currency:US$", "catalog", "complete"), store.calls)
        assertFalse(p.visible.value)
    }

    @Test fun `without an odometer the step is skipped and first use still completes`() = test {
        val p = presenter()
        p.continueFromLocation()
        p.skipOdometer()
        assertEquals(FirstUseStep.Currency, p.state.value.step)
        p.finish("$")
        assertEquals(listOf("currency:$", "catalog", "complete"), store.calls)
        assertFalse(p.visible.value)
    }

    @Test fun `an odometer that is not a number is refused on the spot and nothing is written`() = test {
        val p = presenter()
        p.continueFromLocation()
        p.saveOdometer("48k")
        assertTrue(p.state.value.odometerInvalid)
        assertEquals(FirstUseStep.Odometer, p.state.value.step)
        assertTrue(store.calls.isEmpty())
        p.saveOdometer("")
        assertTrue(p.state.value.odometerInvalid)
    }

    @Test fun `a blank currency falls back to the peso sign`() = test {
        val p = presenter()
        p.finish("  ")
        assertEquals("currency:$", store.calls.first())
    }

    @Test fun `unknown movement asks if the car is parked and the step is retried after yes`() = test {
        val p = presenter()
        p.continueFromLocation()
        store.answers += FormOutcome.NeedsConfirmation
        p.saveOdometer("1000")
        assertEquals(FirstUseDialog.AskStopped, p.state.value.dialog)
        assertEquals(FirstUseStep.Odometer, p.state.value.step)
        p.answerStopped(true)
        assertEquals(1, store.stopped)
        assertEquals(FirstUseStep.Currency, p.state.value.step)
        assertEquals(listOf("odometer:1000"), store.calls)
    }

    @Test fun `no to the parked question drops the step and tells the domain nothing`() = test {
        val p = presenter()
        p.continueFromLocation()
        store.answers += FormOutcome.NeedsConfirmation
        p.saveOdometer("1000")
        p.answerStopped(false)
        assertEquals(0, store.stopped)
        assertEquals(FirstUseStep.Odometer, p.state.value.step)
        assertEquals(null, p.state.value.dialog)
    }

    @Test fun `a failed write offers a retry and keeps the step`() = test {
        val p = presenter()
        p.continueFromLocation()
        store.answers += FormOutcome.StorageFull
        p.saveOdometer("1000")
        assertEquals(FirstUseDialog.SaveFailed(diskFull = true), p.state.value.dialog)
        assertEquals(FirstUseStep.Odometer, p.state.value.step)
        p.retryWrite()
        assertEquals(FirstUseStep.Currency, p.state.value.step)
    }

    @Test fun `a car that moves blocks the step`() = test {
        val p = presenter()
        store.answers += FormOutcome.CarMoving
        p.finish("$")
        assertEquals(FirstUseDialog.CarMoving, p.state.value.dialog)
        assertTrue(p.visible.value)
    }

    @Test fun `importing from the pendrive completes first use, and a bad copy leaves it open`() = test {
        val p = presenter()
        backups.importResult = ImportOutcome.Damaged
        p.importFromDrive()
        assertEquals(FirstUseDialog.ImportFailed(ImportOutcome.Damaged), p.state.value.dialog)
        assertTrue(p.visible.value)
        p.dismissDialog()
        backups.importResult = ImportOutcome.Done(30)
        p.importFromDrive()
        assertEquals(listOf("complete"), store.calls)
        assertFalse(p.visible.value)
    }

    @Test fun `the GPS warning follows the real state`() = test {
        val p = presenter()
        data.status.value = data.status.value.copy(gps = GpsStatus.NoPermission)
        assertEquals(GpsStatus.NoPermission, p.state.value.gps)
        data.status.value = data.status.value.copy(gps = GpsStatus.Off)
        assertEquals(GpsStatus.Off, p.state.value.gps)
    }
}
