package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.testing.FakeImportDoneNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §8.7 "Import klar": when an AI import says it is done, and what keeps it alive until then. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiImportWatchTest {
    private val notifier = FakeImportDoneNotifier()
    private var visible = false
    private var keepAlives = 0
    private val watch = AiImportWatch(AppVisibility { visible }, notifier) { keepAlives++ }

    private val recipe: Outcome<RecipeImportResult> = Outcome.Success(RecipeImportResult(draft = RecipeDraft(name = "Pannkakor")))
    private val failure: Outcome<RecipeImportResult> = Outcome.Failure(TandemError.NetworkUnavailable)

    @Test
    fun aRecipeFinishedWithTheAppAwayIsAnnouncedByName() = runTest {
        assertEquals(recipe, watch.whileRunning(stillWanted = { true }) { recipe })
        assertEquals(listOf("finished Pannkakor"), notifier.calls)
        assertEquals(1, keepAlives)
    }

    @Test
    fun aFailureWithTheAppAwayIsAnnouncedToo() = runTest {
        assertEquals(failure, watch.whileRunning(stillWanted = { true }) { failure })
        assertEquals(listOf("failed"), notifier.calls)
    }

    @Test
    fun withTheAppOnScreenTheScreenSaysIt() = runTest {
        visible = true
        watch.whileRunning(stillWanted = { true }) { recipe }
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun itMattersWhereTheAppIsWhenTheImportEnds() = runTest {
        watch.whileRunning(stillWanted = { true }) {
            visible = true
            recipe
        }
        assertTrue("came back meanwhile", notifier.calls.isEmpty())
        visible = true
        watch.whileRunning(stillWanted = { true }) {
            visible = false
            recipe
        }
        assertEquals("left meanwhile", listOf("finished Pannkakor"), notifier.calls)
    }

    @Test
    fun anAnswerForAnAccountNoLongerActiveSaysNothing() = runTest {
        watch.whileRunning(stillWanted = { false }) { recipe }
        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun aCancelledImportSaysNothingAndFreesTheKeepAlive() = runTest {
        val started = CompletableDeferred<Unit>()
        val import = launch {
            watch.whileRunning(stillWanted = { true }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        import.cancelAndJoin()
        assertTrue(notifier.calls.isEmpty())
        watch.awaitNoneRunning()
    }

    @Test
    fun theKeepAliveEndsWithTheLastImport() = runTest {
        val release = CompletableDeferred<Unit>()
        val import = async(start = CoroutineStart.UNDISPATCHED) {
            watch.whileRunning(stillWanted = { true }) {
                release.await()
                recipe
            }
        }
        val keepAlive = launch(start = CoroutineStart.UNDISPATCHED) { watch.awaitNoneRunning() }
        assertFalse("still waiting for the answer", keepAlive.isCompleted)
        release.complete(Unit)
        import.await()
        keepAlive.join()
    }

    @Test
    fun afterARestartNothingIsWaitedFor() = runTest {
        watch.awaitNoneRunning()
    }
}
