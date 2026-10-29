package casa.moller.tandem.core.testing.time

import androidx.compose.runtime.getValue
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.testing.data.SamplePantry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

/**
 * Fas S (hemma-ui lane): a [CalendarDays] the test moves by hand ([day]), e.g. past midnight to see
 * "går ut i morgon" become "går ut i dag". Starts on [SamplePantry.TODAY].
 */
class FakeCalendarDays(start: LocalDate = SamplePantry.TODAY) : CalendarDays {
    val day = MutableStateFlow(start)

    override fun today(): LocalDate = day.value

    override fun observeToday(): Flow<LocalDate> = day
}
