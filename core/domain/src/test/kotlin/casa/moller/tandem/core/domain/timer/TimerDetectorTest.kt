package casa.moller.tandem.core.domain.timer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Table-driven tests for [TimerDetector] (plan 5.6 and 6.7 "Språkberoende logik").
 * Every case lists the full expected result, so an extra (false positive) match fails too.
 */
class TimerDetectorTest {

    /** One expected detection: the exact matched text and the duration range in seconds. */
    data class Expected(val label: String, val minSeconds: Long, val maxSeconds: Long = minSeconds, val approximate: Boolean = false)

    data class Case(val text: String, val expected: List<Expected>) {
        override fun toString(): String = "\"$text\""
    }

    private val detector = TimerDetector()

    @ParameterizedTest(name = "sv {0}")
    @MethodSource("swedishCases")
    fun swedish(case: Case) = check(case)

    @ParameterizedTest(name = "en {0}")
    @MethodSource("englishCases")
    fun english(case: Case) = check(case)

    @ParameterizedTest(name = "no timer in {0}")
    @MethodSource("falsePositiveCases")
    fun falsePositives(case: Case) = check(case)

    private fun check(case: Case) {
        val actual = detector.detect(case.text).map {
            Expected(it.label, it.minSeconds, it.maxSeconds, it.isApproximate)
        }
        assertEquals(case.expected, actual, "text: ${case.text}")
    }

    @Test
    fun spansPointIntoTheOriginalText() {
        val text = "Koka i ca 20–25 min. Låt sedan vila 1 tim och 15 minuter."
        val timers = detector.detect(text)
        assertEquals(2, timers.size)
        for (timer in timers) {
            assertEquals(timer.label, text.substring(timer.start, timer.end))
        }
        assertEquals(7, timers[0].start)
        assertEquals(1200L..1500L, timers[0].durationSeconds)
        assertTrue(timers[0].isRange)
    }

    @Test
    fun spansAreSortedAndNeverOverlap() {
        val text = "Stek 3 min, vänd, stek 2–3 min till. Bake 1 hour 15 minutes, then rest 10 minutes."
        val timers = detector.detect(text)
        assertEquals(listOf("3 min", "2–3 min", "1 hour 15 minutes", "10 minutes"), timers.map { it.label })
        timers.zipWithNext { a, b -> assertTrue(a.end <= b.start, "$a overlaps $b") }
    }

    @Test
    fun languageIsGuessedFromTheTextNotTheApp() {
        assertEquals("sv", detector.guessLanguage("Koka pastan i saltat vatten och låt rinna av."))
        assertEquals("en", detector.guessLanguage("Boil the pasta in salted water and drain it."))
        assertNull(detector.guessLanguage("12 345"))
    }

    @Test
    fun matchesCarryTheLanguageOfTheRuleSetThatFoundThem() {
        assertEquals(listOf("sv"), detector.detect("Låt vila en kvart.").map { it.languageTag })
        assertEquals(listOf("en"), detector.detect("Let it rest for half an hour.").map { it.languageTag })
    }

    @Test
    fun swedishRecipeInEnglishTextStillFindsSwedishUnits() {
        // Mixed text: the Swedish rule set still runs even when English wins the guess.
        val timers = detector.detect("Cook the rice, then let it rest 5 minuter in the pot.")
        assertEquals(listOf("5 minuter"), timers.map { it.label })
        assertEquals(300L, timers.single().minSeconds)
    }

    @Test
    fun newLanguageIsDataOnly() {
        val german = TimerLanguageRules(
            languageTag = "de",
            units = mapOf("minuten" to 60L, "min" to 60L, "stunde" to 3600L, "stunden" to 3600L),
            numberWords = mapOf("eine" to 1.0, "zwei" to 2.0),
            rangeConnectors = setOf("-", "bis"),
            compoundConnectors = setOf("und"),
            approximations = setOf("etwa", "ca"),
            hintWords = setOf("und", "der", "die", "das", "mit"),
        )
        val timers = TimerDetector(rules = listOf(german)).detect("Etwa 10 bis 12 Minuten kochen, dann eine Stunde ruhen.")
        assertEquals(
            listOf(Expected("Etwa 10 bis 12 Minuten", 600, 720, true), Expected("eine Stunde", 3600)),
            timers.map { Expected(it.label, it.minSeconds, it.maxSeconds, it.isApproximate) },
        )
    }

    @Test
    fun emptyAndBlankTextGiveNothing() {
        assertEquals(emptyList<DetectedTimer>(), detector.detect(""))
        assertEquals(emptyList<DetectedTimer>(), detector.detect("   \n\t "))
    }

    companion object {
        private fun case(text: String, vararg expected: Expected) = Case(text, expected.toList())
        private fun t(label: String, min: Long, max: Long = min, approx: Boolean = false) = Expected(label, min, max, approx)

        @JvmStatic
        fun swedishCases(): List<Case> = listOf(
            case("Koka i 10 min.", t("10 min", 600)),
            case("Koka i 10 minuter.", t("10 minuter", 600)),
            case("KOKA 10 MINUTER", t("10 MINUTER", 600)),
            case("Vänta 1 minut.", t("1 minut", 60)),
            case("Rör i 30 sek.", t("30 sek", 30)),
            case("Mixa 45 sekunder.", t("45 sekunder", 45)),
            case("Låt jäsa 2 timmar.", t("2 timmar", 7200)),
            case("Låt jäsa 1 timme.", t("1 timme", 3600)),
            case("Grädda 1 tim.", t("1 tim", 3600)),
            case("Sjud 3 h under lock.", t("3 h", 10800)),
            case("Stek 10min per sida.", t("10min", 600)),
            case("Koka 20–25 min.", t("20–25 min", 1200, 1500)),
            case("Koka 20-25 minuter.", t("20-25 minuter", 1200, 1500)),
            case("Koka 20 - 25 min.", t("20 - 25 min", 1200, 1500)),
            case("Stek 3 till 4 minuter per sida.", t("3 till 4 minuter", 180, 240)),
            case("Koka 3 eller 4 minuter.", t("3 eller 4 minuter", 180, 240)),
            case("Koka mellan 10 och 12 minuter.", t("mellan 10 och 12 minuter", 600, 720)),
            case("Grädda i ca 20–25 min.", t("ca 20–25 min", 1200, 1500, approx = true)),
            case("Grädda ca. 40 min.", t("ca. 40 min", 2400, approx = true)),
            case("Låt stå cirka 10 minuter.", t("cirka 10 minuter", 600, approx = true)),
            case("Koka ungefär 2 timmar.", t("ungefär 2 timmar", 7200, approx = true)),
            case("Puttra c:a 15 min.", t("c:a 15 min", 900, approx = true)),
            case("Låt vila i drygt en timme.", t("drygt en timme", 3600, approx = true)),
            case("Vila 1 tim och 15 minuter.", t("1 tim och 15 minuter", 4500)),
            case("Grädda 1 h 15 min.", t("1 h 15 min", 4500)),
            case("Grädda 1 timme 30 minuter.", t("1 timme 30 minuter", 5400)),
            case("Låt stå 1 timme och en kvart.", t("1 timme och en kvart", 4500)),
            case("Koka 1 tim 2 min 30 sek.", t("1 tim 2 min 30 sek", 3750)),
            case("Koka 1,5 timme.", t("1,5 timme", 5400)),
            case("Koka ½ timme.", t("½ timme", 1800)),
            case("Koka 1 ½ timme.", t("1 ½ timme", 5400)),
            case("Koka 1½ timme.", t("1½ timme", 5400)),
            case("Låt vila en halvtimme.", t("en halvtimme", 1800)),
            case("Låt vila en halv timme.", t("en halv timme", 1800)),
            case("Låt vila en kvart.", t("en kvart", 900)),
            case("Låt dra tre kvart.", t("tre kvart", 2700)),
            case("Sjud en och en halv timme.", t("en och en halv timme", 5400)),
            case("Sjud 1 timme och en halv.", t("1 timme och en halv", 5400)),
            case("Sjud halvannan timme.", t("halvannan timme", 5400)),
            case("Koka två timmar.", t("två timmar", 7200)),
            case("Ge degen en 10 minuters vila.", t("10 minuters", 600)),
            case("Efter 2 timmars marinering är köttet mört.", t("2 timmars", 7200)),
            case("Grädda 45 min–1 tim.", t("45 min–1 tim", 2700, 3600)),
            case("Koka ½–1 timme.", t("½–1 timme", 1800, 3600)),
            case("Koka 1–1,5 timme.", t("1–1,5 timme", 3600, 5400)),
            case("Låt vila en halvtimme till en timme.", t("en halvtimme till en timme", 1800, 3600)),
            case(
                "Koka i 5 minuter, vänd och koka 3 minuter till.",
                t("5 minuter", 300),
                t("3 minuter", 180),
            ),
            case("Vispa minst 5 min.", t("5 min", 300)),
            case("Rör ner 2 msk och koka 5 min.", t("5 min", 300)),
            case("Sätt ugnen på 200 grader och grädda i 25 minuter.", t("25 minuter", 1500)),
            case("Koka upp och låt sjuda 15–20 min under lock. Servera.", t("15–20 min", 900, 1200)),
            case("Lägg i min gryta och koka 10 minuter.", t("10 minuter", 600)),
            // Compound number words are one word in Swedish.
            case("Koka trettiofem minuter.", t("trettiofem minuter", 2100)),
            case("Låt vila tjugoen minuter.", t("tjugoen minuter", 1260)),
            case("Grädda sjuttiofem minuter.", t("sjuttiofem minuter", 4500)),
            case("Koka arton minuter.", t("arton minuter", 1080)),
            // Phase 1b (timer lane): the forms listed for the timer chips.
            case("Låt jäsa 1,5 timmar.", t("1,5 timmar", 5400)),
            case("Låt jäsa 1.5 timmar.", t("1.5 timmar", 5400)),
            case("Grädda ca 20–25 minuter.", t("ca 20–25 minuter", 1200, 1500, approx = true)),
            case("Grädda ca 20-25 minuter.", t("ca 20-25 minuter", 1200, 1500, approx = true)),
            case("Grädda 1 h 30 min.", t("1 h 30 min", 5400)),
            case("Grädda 1 tim 30 min.", t("1 tim 30 min", 5400)),
            case("Rör i 30 sekunder.", t("30 sekunder", 30)),
            case("Koka en minut.", t("en minut", 60)),
            case("Koka två minuter.", t("två minuter", 120)),
            case("Koka sex minuter.", t("sex minuter", 360)),
            case("Koka nio minuter.", t("nio minuter", 540)),
            case("Koka tio minuter.", t("tio minuter", 600)),
            case("Koka elva minuter.", t("elva minuter", 660)),
            case("Koka femton minuter.", t("femton minuter", 900)),
            case("Koka nitton minuter.", t("nitton minuter", 1140)),
            case("Koka tjugo minuter.", t("tjugo minuter", 1200)),
            case("Koka tre timmar.", t("tre timmar", 10800)),
            case("Koka fem till sju minuter.", t("fem till sju minuter", 300, 420)),
            case("Grädda 40 min. Låt sedan stå över natten.", t("40 min", 2400)),
        )

        @JvmStatic
        fun englishCases(): List<Case> = listOf(
            case("Boil for 10 minutes.", t("10 minutes", 600)),
            case("Cook 1 minute.", t("1 minute", 60)),
            case("Whisk for 30 seconds.", t("30 seconds", 30)),
            case("Pulse 45 secs.", t("45 secs", 45)),
            case("Sear 20 sec per side.", t("20 sec", 20)),
            case("Bake 2 hours.", t("2 hours", 7200)),
            case("Bake 1 hour.", t("1 hour", 3600)),
            case("Roast 1 hr.", t("1 hr", 3600)),
            case("Braise 3 hrs.", t("3 hrs", 10800)),
            case("Rest 5 mins.", t("5 mins", 300)),
            case("Simmer 15 min.", t("15 min", 900)),
            case("Slow cook 8 h.", t("8 h", 28800)),
            case("Bake 1 hour 15 minutes.", t("1 hour 15 minutes", 4500)),
            case("Bake 1 hour and 15 minutes.", t("1 hour and 15 minutes", 4500)),
            case("Bake 1 hr 15 min.", t("1 hr 15 min", 4500)),
            case("Simmer 10 to 12 minutes.", t("10 to 12 minutes", 600, 720)),
            case("Simmer 10-12 minutes.", t("10-12 minutes", 600, 720)),
            case("Simmer 10–12 min.", t("10–12 min", 600, 720)),
            case("Cook 2 or 3 minutes.", t("2 or 3 minutes", 120, 180)),
            case("Bake between 25 and 30 minutes.", t("between 25 and 30 minutes", 1500, 1800)),
            case("Bake about 20 minutes.", t("about 20 minutes", 1200, approx = true)),
            case("Bake approximately 1 hour.", t("approximately 1 hour", 3600, approx = true)),
            case("Rest approx. 30 min.", t("approx. 30 min", 1800, approx = true)),
            case("Cook around 5 minutes.", t("around 5 minutes", 300, approx = true)),
            case("Simmer roughly 2 hours.", t("roughly 2 hours", 7200, approx = true)),
            case("Microwave ~10 min.", t("~10 min", 600, approx = true)),
            case("Let it rest for half an hour.", t("half an hour", 1800)),
            case("Simmer for an hour and a half.", t("an hour and a half", 5400)),
            case("Bake 1 1/2 hours.", t("1 1/2 hours", 5400)),
            case("Bake 1.5 hours.", t("1.5 hours", 5400)),
            case("Bake one and a half hours.", t("one and a half hours", 5400)),
            case("Bake for two hours.", t("two hours", 7200)),
            case("Cook for a minute more.", t("a minute", 60)),
            case("Give the dough a 10-minute rest.", t("10-minute", 600)),
            case("Chill for a half-hour.", t("a half-hour", 1800)),
            case("Bake 45 minutes to 1 hour.", t("45 minutes to 1 hour", 2700, 3600)),
            case("Bake 1/2 hour.", t("1/2 hour", 1800)),
            case("Bake 3/4 hour.", t("3/4 hour", 2700)),
            case("Bake 1-1/2 hours.", t("1-1/2 hours", 5400)),
            case("Bake about 1 to 1 1/2 hours.", t("about 1 to 1 1/2 hours", 3600, 5400, approx = true)),
            case("Boil 10 minutes or until golden.", t("10 minutes", 600)),
            case("Preheat the oven to 400°F and bake 25 minutes.", t("25 minutes", 1500)),
            case("Marinate at least 4 hours, preferably 24 hours.", t("4 hours", 14400), t("24 hours", 86400)),
            // Compound number words: one number, never a range ("thirty to five").
            case("Simmer thirty-five minutes.", t("thirty-five minutes", 2100)),
            case("Bake twenty five minutes.", t("twenty five minutes", 1500)),
            case("Cook twenty-two minutes.", t("twenty-two minutes", 1320)),
            case("Braise for fifty-five minutes.", t("fifty-five minutes", 3300)),
            case("Pulse thirty-five seconds.", t("thirty-five seconds", 35)),
            case("Bake one-and-a-half hours.", t("one-and-a-half hours", 5400)),
            case("Rest seventeen minutes.", t("seventeen minutes", 1020)),
            case("Bake twenty to twenty-five minutes.", t("twenty to twenty-five minutes", 1200, 1500)),
            // Phase 1b (timer lane): numbers in words up to twenty, ranges and short units.
            case("Boil one minute.", t("one minute", 60)),
            case("Boil three minutes.", t("three minutes", 180)),
            case("Boil ten minutes.", t("ten minutes", 600)),
            case("Boil eleven minutes.", t("eleven minutes", 660)),
            case("Boil twelve minutes.", t("twelve minutes", 720)),
            case("Boil nineteen minutes.", t("nineteen minutes", 1140)),
            case("Boil twenty minutes.", t("twenty minutes", 1200)),
            case("Bake 20–25 minutes.", t("20–25 minutes", 1200, 1500)),
            case("Bake 20 - 25 mins.", t("20 - 25 mins", 1200, 1500)),
            case("Bake 1 h 30 min.", t("1 h 30 min", 5400)),
            case("Braise 2 hrs 15 mins.", t("2 hrs 15 mins", 8100)),
            case("Rest 90 seconds.", t("90 seconds", 90)),
            case("Bake 40 minutes, then leave overnight.", t("40 minutes", 2400)),
        )

        @JvmStatic
        fun falsePositiveCases(): List<Case> = listOf(
            // Swedish
            case("Sätt ugnen på 180 grader."),
            case("Ugn: 225°C, mitten av ugnen."),
            case("Tillsätt 2 msk smör."),
            case("Receptet räcker till 4 portioner."),
            case("Min mamma lagade alltid den här."),
            case("Det här är min mammas recept."),
            case("Ge 2 till min mamma."),
            case("Vispa minst en natt i kylen."),
            case("Servera kl. 18."),
            case("Servera kl. 18.30 med sallad."),
            case("Se sidan 12."),
            case("Tillsätt 2 kvistar timjan."),
            case("Väg upp 3 hg ost."),
            case("500 g köttfärs och 1 tsk salt."),
            case("Vänta en sekund."),
            case("Mikra 3:30 min."),
            case("Ett minimum av socker."),
            case("Steg 2: blanda allt."),
            case("Koka upp i en stor kastrull."),
            // Phase 1b (timer lane): no timer for overnight, temperatures, amounts or clock times.
            case("Låt stå över natten."),
            case("Låt degen jäsa över natten i kylen."),
            case("Sätt ugnen på 200 grader."),
            case("Grädda i 200 °C."),
            case("Tillsätt 2 dl grädde."),
            case("Tillsätt 1,5 dl mjölk och 3 msk smör."),
            case("Ät kl. 18."),
            case("Ät kl 18 eller kl. 19.30."),
            case("Servera 18:30."),
            case("Ugnen ska vara 175 grader varmluft."),
            // English
            case("Preheat the oven to 350°F."),
            case("Bake at 200 degrees."),
            case("Serves 4."),
            case("Add 2 tbsp butter."),
            case("See page 12."),
            case("Add a second egg."),
            case("Add 2 minced garlic cloves."),
            case("Use at least 2 cups of flour."),
            case("Step 3: mix well."),
            case("Serve at 6 pm."),
            case("Cook for a few minutes."),
            case("Hours of fun in the kitchen."),
            case("Use a 9 inch pan."),
            case("Chill overnight."),
            case("Leave it over night."),
            case("Bake at 200 degrees for a while."),
            case("Add 2 cups of stock."),
            case("Serve at 6:30."),
            // An unknown hyphenated word after a number word is no range.
            case("Bake twenty-something minutes."),
            case("Mix thirty-ish minutes."),
        )
    }
}
