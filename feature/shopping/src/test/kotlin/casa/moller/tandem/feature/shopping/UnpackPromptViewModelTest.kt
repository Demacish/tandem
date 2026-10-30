package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Fas S review (fix-ui): "Inte nu" on Handla's Packa upp card hides it until tomorrow, the items stay unpackable. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnpackPromptViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pantry = FakePantryRepository()
    private val calendar = FakeCalendarDays()
    private val dismissals = UnpackPromptDismissals()

    private fun viewModel() = UnpackPromptViewModel(pantry, FakeSpacePermissionsRepository(), calendar, dismissals)

    @Test
    fun notNowHidesTheCardForTheRestOfTheDayOnly() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.count.collect {} }
        val waiting = SamplePantry.unpack.size
        assertEquals(waiting, vm.count.value)

        vm.dismiss()
        assertEquals(0, vm.count.value)
        // Nothing was unpacked or skipped: Hemma's Packa upp still has every item.
        assertEquals(waiting, pantry.unpackable.value.size)
        // A new ViewModel (Handla opened again) keeps it hidden today.
        val again = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.count.collect {} }
        assertEquals(0, again.count.value)

        calendar.day.value = SamplePantry.TODAY.plus(DatePeriod(days = 1))
        assertEquals(waiting, vm.count.value)
    }
}
