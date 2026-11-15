package casa.moller.tandem.feature.recipes.inbox

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeInboxRepository
import casa.moller.tandem.core.testing.data.FakeRecipeImportRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleInbox
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import casa.moller.tandem.feature.recipes.navigation.RecipeInboxKey
import casa.moller.tandem.feature.recipes.recipeimport.RecipeImportAction
import casa.moller.tandem.feature.recipes.recipeimport.RecipeImportEffect
import casa.moller.tandem.feature.recipes.recipeimport.RecipeImportViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (rss lane, plan §10 "ett inlägg … kan importeras med två tryck via samma granskningsvy som
 * #18"): the inbox on core:testing's fakes. Tap 1 opens a post's preview, tap 2 opens the import
 * review with its address, which fetches it at once (no third tap) and marks the post imported when
 * the review is accepted. The inbox refreshes when it opens, keeps the stored posts on a failed
 * refresh, guests cannot import, and dismissing marks a post read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val inbox = FakeRecipeInboxRepository()
    private val permissions = FakeSpacePermissionsRepository()

    private fun viewModel(key: RecipeInboxKey = RecipeInboxKey(), handle: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(key, handle, inbox, permissions)

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    @Test
    fun aPostIsImportedWithTwoTapsThroughTheImportReview() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        val post = SampleInbox.posts.first()

        // Tap 1: the post's preview.
        vm.onAction(InboxAction.Open(post.id))
        assertEquals(post, vm.uiState.value.preview)
        // Tap 2: "Importera".
        vm.onAction(InboxAction.Import(post.id))
        val key = effects.filterIsInstance<InboxEffect.OpenImport>().single().key
        assertEquals(RecipeImportKey(sharedText = post.url, inboxEntryId = post.id, autoImport = true), key)
        assertNull(vm.uiState.value.preview)

        // The import review (#18) opened with that key: the result is there without another tap.
        val imports = FakeRecipeImportRepository()
        val review = RecipeImportViewModel(key, SavedStateHandle(), imports, permissions, inbox)
        collect(review.uiState)
        val reviewEffects = effectsOf(review.effects)
        assertEquals(1, imports.imported.size)
        assertTrue(review.uiState.value.result != null)
        // Accepting it opens the editor and remembers the post as imported.
        review.onAction(RecipeImportAction.OpenEditor)
        assertEquals(1, reviewEffects.filterIsInstance<RecipeImportEffect.OpenEditor>().size)
        assertTrue(vm.uiState.value.entries.first { it.id == post.id }.imported)
    }

    @Test
    fun theInboxRefreshesWhenItOpensAndKeepsThePostsWhenThatFails() = runTest {
        inbox.refreshFailure = InboxProblem.UNREACHABLE
        val vm = viewModel()
        collect(vm.uiState)

        assertEquals(listOf("refresh"), inbox.calls)
        val state = vm.uiState.value
        assertEquals(SampleInbox.posts, state.entries)
        assertEquals(InboxProblem.UNREACHABLE, state.problem)
        assertFalse(state.refreshing)

        inbox.refreshFailure = null
        vm.onAction(InboxAction.Refresh)
        assertNull(vm.uiState.value.problem)
        assertEquals(2, vm.uiState.value.newCount)
    }

    @Test
    fun aPostTappedOnIdagOpensItsPreviewAndSurvivesARecreation() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(RecipeInboxKey(entryId = 12), handle)
        collect(vm.uiState)
        assertEquals(12L, vm.uiState.value.preview?.id)
        vm.onAction(InboxAction.ClosePreview)

        val again = viewModel(RecipeInboxKey(entryId = 12), handle)
        collect(again.uiState)
        assertNull("closed stays closed", again.uiState.value.preview)
    }

    @Test
    fun aGuestReadsButCannotImport() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(InboxAction.Open(11))
        vm.onAction(InboxAction.Import(11))
        assertEquals(emptyList<InboxEffect>(), effects.filterIsInstance<InboxEffect.OpenImport>())
    }

    @Test
    fun markReadHidesThePostAndOpenInBrowserGivesItsAddress() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)

        vm.onAction(InboxAction.OpenInBrowser(12))
        vm.onAction(InboxAction.Dismiss(11))

        assertEquals(InboxEffect.OpenUrl(SampleInbox.posts[1].url), effects.first())
        assertEquals(InboxEffect.Message(R.string.inbox_marked_read), effects.last())
        assertTrue("dismiss 11" in inbox.calls)
        assertEquals(listOf(12L, 13L), vm.uiState.value.entries.map { it.id })
    }

    @Test
    fun withoutASetUpInboxNothingIsAsked() = runTest {
        inbox.mutableState.value = SampleInbox.notSetUp
        val vm = viewModel()
        collect(vm.uiState)
        assertFalse(vm.uiState.value.inUse)
        assertEquals(emptyList<String>(), inbox.calls)
    }

    @Test
    fun demoModeShowsItsInboxWithoutRefreshing() = runTest {
        inbox.mutableState.value = SampleInbox.demo
        val vm = viewModel()
        collect(vm.uiState)
        assertTrue(vm.uiState.value.inUse && vm.uiState.value.isDemo)
        assertEquals(emptyList<String>(), inbox.calls)
    }
}
