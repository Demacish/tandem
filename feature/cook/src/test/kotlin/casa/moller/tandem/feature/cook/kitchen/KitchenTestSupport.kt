package casa.moller.tandem.feature.cook.kitchen

import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Fas 5 (kok lane, plan §8.7 #41): the kitchen display's test data, from the sample meal plan. */
internal object KitchenSamples {
    private val pasta = SampleRecipes.MushroomPasta
    private val buns = SampleRecipes.CinnamonBuns

    /** Today: the pasta for dinner and the buns for coffee; tomorrow: leftovers and pea soup. */
    val meals: KitchenUiState = KitchenUiState(
        loading = false,
        todayMeals = listOf(
            KitchenMeal(id = 1, title = pasta.name, recipeId = pasta.id, imageUrl = null, servings = 4.0, mealType = "Middag"),
            KitchenMeal(id = 4, title = buns.name, recipeId = buns.id, imageUrl = null, servings = 1.0, mealType = "Fika"),
        ),
        tomorrowMeals = listOf(
            KitchenMeal(id = 2, title = "Rester", recipeId = null, imageUrl = null, servings = 1.0, mealType = "Lunch"),
            KitchenMeal(
                id = 3,
                title = SampleRecipes.PeaSoup.name,
                recipeId = SampleRecipes.PeaSoup.id,
                imageUrl = null,
                servings = 6.0,
                mealType = "Middag",
            ),
        ),
    )

    /** A timer of [recipeName] made at [clock]'s now, [elapsed] into [duration]. */
    fun timer(
        id: Long,
        label: String,
        duration: Duration,
        clock: TestClock,
        elapsed: Duration = Duration.ZERO,
        state: TimerState = TimerState.RUNNING,
        recipeId: Long? = pasta.id,
        recipeName: String? = pasta.name,
    ): CookTimer {
        val created = clock.now() - elapsed
        return CookTimer(
            id = id,
            label = label,
            recipeId = recipeId,
            stepIndex = 1,
            recipeName = recipeName,
            duration = duration,
            state = state,
            endsAt = if (state == TimerState.PAUSED) null else created + duration,
            pausedRemaining = if (state == TimerState.PAUSED) duration - elapsed else null,
            createdAt = created,
        )
    }

    /** The pasta's timer counting down (7:39 left), the buns rising (paused) and one that rings. */
    fun withTimers(clock: TestClock, ringing: Boolean = false): KitchenUiState = meals.copy(
        timers = listOfNotNull(
            timer(3, "Svampen · ca 5 minuter", 5.minutes, clock, elapsed = 5.minutes, state = TimerState.RINGING).takeIf { ringing },
            timer(1, "Pastan · 10 min", 10.minutes, clock, elapsed = 2.minutes + 21.seconds),
            timer(
                id = 2,
                label = "Jäsning · 1 timme",
                duration = 1.hours,
                clock = clock,
                elapsed = 20.minutes,
                state = TimerState.PAUSED,
                recipeId = buns.id,
                recipeName = buns.name,
            ),
        ),
    )
}
