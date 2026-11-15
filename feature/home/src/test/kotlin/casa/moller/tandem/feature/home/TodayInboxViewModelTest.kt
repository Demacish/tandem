package casa.moller.tandem.feature.home

import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeInboxRepository
import casa.moller.tandem.core.testing.data.SampleInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fas 5 (rss lane, plan §7 #35): what Idag's "Nytt i inkorgen" shows, from the inbox's stored posts. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayInboxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun theViewModelShowsTheNewPostsOfASetUpInbox() = runTest {
        val more = SampleInbox.posts.take(2).map { it.copy(id = it.id + 100) }
        val inbox = FakeRecipeInboxRepository(posts = SampleInbox.posts + more)
        val vm = TodayInboxViewModel(inbox)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertTrue(state.visible)
        assertEquals(TodayInboxViewModel.MAX_POSTS, state.entries.size)
        assertTrue(state.entries.none { it.imported })
        assertEquals(4, state.newCount)

        vm.setShown(false)
        assertEquals("today false", inbox.calls.last())
        assertFalse(vm.uiState.value.visible)
        assertTrue("still offered in Anpassa Idag", vm.uiState.value.available)

        inbox.mutableState.value = SampleInbox.notSetUp
        assertFalse(vm.uiState.value.available)
        inbox.mutableState.value = SampleInbox.demo
        assertTrue(vm.uiState.value.visible)
    }
}
