package casa.moller.tandem.feature.recipes.recipeimport

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeImportRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleImports
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.editor.SavedDraft
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import java.io.File
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
import org.junit.rules.TemporaryFolder

/**
 * "Importera recept" on core:testing's fakes (Fas 3, import lane): a shared link is sent as a
 * link, a text as text (a link inside a short shared text wins), the result opens the editor
 * with the draft (another picture chosen), a photo goes to the AI import, every problem has its
 * message, a shared file is handed to the route once, and the input survives process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val imports = FakeRecipeImportRepository()
    private val permissions = FakeSpacePermissionsRepository()

    private fun viewModel(key: RecipeImportKey = RecipeImportKey(), handle: SavedStateHandle = SavedStateHandle()) =
        RecipeImportViewModel(key, handle, imports, permissions)

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    @Test
    fun aSharedLinkIsFetchedAndOpensTheEditorWithTheDraft() = runTest {
        val vm = viewModel(RecipeImportKey(sharedText = "Pannkakor https://example.com/recept/pannkakor"))
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        assertEquals("Pannkakor https://example.com/recept/pannkakor", vm.uiState.value.input)
        assertTrue(vm.uiState.value.canImport && vm.uiState.value.aiAvailable)

        vm.onAction(RecipeImportAction.Import)
        assertEquals(listOf(RecipeImportSource.Link("https://example.com/recept/pannkakor")), imports.imported)
        val result = vm.uiState.value.result!!
        assertEquals("Pannkakor", result.draft.name)
        assertFalse(vm.uiState.value.working)

        vm.onAction(RecipeImportAction.ChooseImage(result.imageUrls[1]))
        vm.onAction(RecipeImportAction.OpenEditor)
        val opened = effects.filterIsInstance<RecipeImportEffect.OpenEditor>().single()
        val draft = SavedDraft.decode(opened.importedDraft)!!
        assertEquals("Pannkakor", draft.name)
        assertEquals(result.imageUrls[1], draft.imageUrl)
        assertEquals(2, draft.steps.size)
    }

    @Test
    fun aTextIsSentAsTextAndABareLinkAsALink() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.SetInput("Pannkakor\n2,5 dl vetemjöl\n6 dl mjölk\n3 ägg"))
        vm.onAction(RecipeImportAction.Import)
        assertEquals(RecipeImportSource.Text("Pannkakor\n2,5 dl vetemjöl\n6 dl mjölk\n3 ägg"), imports.imported.single())
        vm.onAction(RecipeImportAction.Clear)
        assertNull(vm.uiState.value.result)
        vm.onAction(RecipeImportAction.SetInput("  https://example.com/x  "))
        vm.onAction(RecipeImportAction.Import)
        assertEquals(RecipeImportSource.Link("https://example.com/x"), imports.imported.last())
        // A link inside a long text is not pulled out: the text is the recipe.
        val long = "https://example.com/y " + "x".repeat(500)
        assertEquals(RecipeImportSource.Text(long), RecipeImportViewModel.sourceOf(long))
        assertEquals(RecipeImportSource.Link("https://example.com/y"), RecipeImportViewModel.sourceOf("Se https://example.com/y."))
        assertNull(RecipeImportViewModel.sourceOf("   "))
    }

    @Test
    fun aPhotoGoesToTheAiImport() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.ImportFile(byteArrayOf(1, 2, 3), "image/jpeg", "recept.jpg"))
        val sent = imports.imported.single() as RecipeImportSource.File
        assertEquals("image/jpeg", sent.mimeType)
        assertEquals(3, sent.bytes.size)
        assertEquals(SampleImports.pancakes, vm.uiState.value.result)
    }

    // Plan §8.4: several photographed pages are one recipe; the review has the original.

    private fun page(name: String): String = temp.newFile(name).apply { writeBytes(byteArrayOf(1)) }.path

    @Test
    fun pagesAreCollectedAndTheLastCanBeRemoved() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        val first = page("1.jpg")
        val second = page("2.jpg")
        vm.onAction(RecipeImportAction.AddPage(first))
        vm.onAction(RecipeImportAction.AddPage(second))
        assertEquals(listOf(first, second), vm.uiState.value.pages)
        assertTrue("nothing is sent before Skicka", imports.imported.isEmpty())

        vm.onAction(RecipeImportAction.RemoveLastPage)
        assertEquals(listOf(first), vm.uiState.value.pages)
        assertFalse(File(second).exists())
    }

    @Test
    fun pagesSurviveProcessDeathButNotTheCachesCleaning() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        collect(vm.uiState)
        val kept = page("1.jpg")
        val cleared = page("2.jpg")
        vm.onAction(RecipeImportAction.AddPage(kept))
        vm.onAction(RecipeImportAction.AddPage(cleared))
        File(cleared).delete()

        val again = viewModel(handle = handle)
        collect(again.uiState)
        assertEquals(listOf(kept), again.uiState.value.pages)
    }

    @Test
    fun sentPagesGoAndTheFileStaysForTheReviewAndGoesWithTheEditor() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        val first = page("1.jpg")
        val joined = page("joined.pdf")
        vm.onAction(RecipeImportAction.AddPage(first))
        vm.onAction(RecipeImportAction.ImportFile(byteArrayOf(1, 2), "application/pdf", "recept.pdf", joined))

        assertTrue(vm.uiState.value.pages.isEmpty())
        assertFalse("the page has done its part", File(first).exists())
        assertEquals(joined, vm.uiState.value.sourcePath)

        vm.onAction(RecipeImportAction.OpenEditor)
        assertEquals(joined, (effects.last() as RecipeImportEffect.OpenEditor).sourcePath)
        assertTrue("the editor shows it now", File(joined).exists())
    }

    @Test
    fun aFailedSendKeepsThePagesToTryAgain() = runTest {
        imports.failure = RecipeImportProblem.FILE_TOO_LARGE.toError()
        val vm = viewModel()
        collect(vm.uiState)
        val first = page("1.jpg")
        val joined = page("joined.pdf")
        vm.onAction(RecipeImportAction.AddPage(first))
        vm.onAction(RecipeImportAction.ImportFile(ByteArray(1), "application/pdf", "recept.pdf", joined))

        assertEquals(R.string.recipe_import_file_too_large, vm.uiState.value.problem)
        assertEquals(R.string.recipe_import_hint_file_too_large, vm.uiState.value.problemHint)
        assertEquals(listOf(first), vm.uiState.value.pages)
        assertFalse(File(joined).exists())
        assertNull(vm.uiState.value.sourcePath)
    }

    @Test
    fun leavingTheResultDeletesTheOriginal() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        val photo = page("photo.jpg")
        vm.onAction(RecipeImportAction.ImportFile(byteArrayOf(1), "image/jpeg", "recept.jpg", photo))
        assertEquals(photo, vm.uiState.value.sourcePath)
        vm.onAction(RecipeImportAction.Clear)
        assertNull(vm.uiState.value.sourcePath)
        assertFalse(File(photo).exists())
    }

    @Test
    fun aKeptOriginalIsGoneAfterProcessDeathWithItsResult() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        collect(vm.uiState)
        val photo = page("photo.jpg")
        vm.onAction(RecipeImportAction.ImportFile(byteArrayOf(1), "image/jpeg", "recept.jpg", photo))

        viewModel(handle = handle)
        assertFalse(File(photo).exists())
    }

    // Fas 4 "Fota recept": what was read on the phone stays there.

    @Test
    fun scannedTextStaysATextEvenWithALinkInIt() = runTest {
        val text = "Pannkakor från https://example.com/recept"
        val vm = viewModel(RecipeImportKey(sharedText = text, scanned = true))
        collect(vm.uiState)
        assertTrue(vm.uiState.value.scanned)
        vm.onAction(RecipeImportAction.Import)
        assertEquals(RecipeImportSource.Text(text), imports.imported.single())
    }

    // Every problem says what to do about it.

    @Test
    fun aProblemHasAHintOfItsOwn() {
        fun hint(problem: Int?) = RecipeImportUiState(problem = problem).problemHint
        assertEquals(R.string.recipe_import_problem_hint, hint(R.string.recipe_import_nothing_found))
        assertEquals(R.string.recipe_import_hint_ai_unavailable, hint(R.string.recipe_import_ai_unavailable))
        assertEquals(R.string.recipe_import_hint_demo, hint(R.string.recipe_import_demo_problem))
        assertEquals(R.string.recipe_import_hint_nothing_in_file, hint(R.string.recipe_import_nothing_in_file))
        assertNull(hint(R.string.recipe_import_read_only))
        assertNull(hint(null))
    }

    // #38: a page that never loaded is not a page without a recipe.

    @Test
    fun aPageThatCouldNotBeLoadedSaysSo() = runTest {
        val link = "https://example.com/blockerad"
        imports.linkFailure = RecipeImportProblem.NOTHING_FOUND.toError()
        val vm = viewModel(RecipeImportKey(sharedText = link))
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.Import)
        vm.onAction(RecipeImportAction.PageUnavailable)
        assertNull(vm.uiState.value.pageReading)
        assertEquals(R.string.recipe_import_page_unavailable, vm.uiState.value.problem)
        assertEquals("nothing is sent as if it were the page", 1, imports.imported.size)
    }

    @Test
    fun anHttpLinkIsLoadedOverHttpsAndKeptForTheServer() {
        assertEquals("https://example.com/x", PageReading("http://example.com/x").loadUrl)
        assertEquals("https://example.com/x", PageReading("HTTP://example.com/x").loadUrl)
        assertEquals("https://example.com/x", PageReading("https://example.com/x").loadUrl)
    }

    @Test
    fun aLinkTheServerCannotReadIsReadInTheApp() = runTest {
        // #38: the site blocks the server; the page's HTML from the app's WebView goes instead.
        val link = "https://example.com/blockerad"
        imports.linkFailure = RecipeImportProblem.NOTHING_FOUND.toError()
        val vm = viewModel(RecipeImportKey(sharedText = link))
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.Import)
        assertEquals(PageReading(link), vm.uiState.value.pageReading)
        assertNull(vm.uiState.value.problem)

        vm.onAction(RecipeImportAction.PageLoaded("<html>sidan</html>"))
        assertEquals(RecipeImportSource.Page(link, "<html>sidan</html>"), imports.imported.last())
        assertNull(vm.uiState.value.pageReading)
        assertEquals(SampleImports.pancakes, vm.uiState.value.result)
        // A late load of the same page changes nothing.
        vm.onAction(RecipeImportAction.PageLoaded("<html>igen</html>"))
        assertEquals(2, imports.imported.size)
    }

    // Fas 4 review (fix-data; plan §8.4 step 3): an Instagram link behind a login wall.
    private fun TestScope.nothingOnThePage(shared: String): RecipeImportViewModel {
        imports.linkFailure = RecipeImportProblem.NOTHING_FOUND.toError()
        val vm = viewModel(RecipeImportKey(sharedText = shared))
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.Import)
        imports.failure = RecipeImportProblem.NOTHING_FOUND.toError()
        vm.onAction(RecipeImportAction.PageLoaded("<html>logga in</html>", "Logga in för att se"))
        imports.failure = null
        return vm
    }

    @Test
    fun theSharedCaptionIsKeptAndGoesToTheAiOnlyWhenAskedFor() = runTest {
        val link = "https://example.com/p/abc"
        val vm = nothingOnThePage("Pannkakor: 3 dl mjöl, 6 dl mjölk $link")
        assertEquals("Pannkakor: 3 dl mjöl, 6 dl mjölk", vm.uiState.value.pageReading?.caption)
        assertTrue(vm.uiState.value.canInterpretText)
        assertTrue("nothing goes to the AI by itself", imports.imported.none { it is RecipeImportSource.AiText })

        vm.onAction(RecipeImportAction.InterpretText)

        assertEquals(RecipeImportSource.AiText("Pannkakor: 3 dl mjöl, 6 dl mjölk"), imports.imported.last())
        assertNull(vm.uiState.value.pageReading)
        assertEquals(SampleImports.pancakes.draft.name, vm.uiState.value.result?.draft?.name)
    }

    @Test
    fun withoutACaptionTheAiGetsThePagesText() = runTest {
        val vm = nothingOnThePage("https://example.com/p/abc")
        assertTrue(vm.uiState.value.canInterpretText)
        vm.onAction(RecipeImportAction.InterpretText)
        assertEquals(RecipeImportSource.AiText("Logga in för att se"), imports.imported.last())
    }

    @Test
    fun withoutAnAiProviderTheCaptionIsReadOnTheDevice() = runTest {
        imports.aiAvailable.value = false
        val vm = nothingOnThePage("3 dl mjöl och 6 dl mjölk https://example.com/p/abc")
        assertTrue(vm.uiState.value.canInterpretText)
        vm.onAction(RecipeImportAction.InterpretText)
        assertEquals(RecipeImportSource.Text("3 dl mjöl och 6 dl mjölk"), imports.imported.last())
    }

    @Test
    fun withoutAProviderAndACaptionThereIsNothingToTry() = runTest {
        imports.aiAvailable.value = false
        val vm = nothingOnThePage("https://example.com/p/abc")
        assertFalse(vm.uiState.value.canInterpretText)
    }

    @Test
    fun aPageWithoutARecipeCanBeReadAgainOrCancelled() = runTest {
        val link = "https://example.com/blockerad"
        imports.linkFailure = RecipeImportProblem.NOTHING_FOUND.toError()
        val vm = viewModel(RecipeImportKey(sharedText = link))
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.Import)
        imports.failure = RecipeImportProblem.NOTHING_FOUND.toError()
        vm.onAction(RecipeImportAction.PageLoaded("<html>bekräfta att du är en människa</html>"))
        assertEquals(PageReading(link, nothingFound = true), vm.uiState.value.pageReading)
        vm.onAction(RecipeImportAction.ReadPageAgain)
        assertEquals(1, vm.uiState.value.pageReading?.readRequest)
        vm.onAction(RecipeImportAction.CancelPage)
        assertNull(vm.uiState.value.pageReading)
        assertNull("the user's own cancel is no problem to report", vm.uiState.value.problem)
        // Another failure while the page is with the server: the usual message.
        vm.onAction(RecipeImportAction.Import)
        imports.failure = TandemError.NetworkUnavailable
        vm.onAction(RecipeImportAction.PageLoaded("<html></html>"))
        assertNull(vm.uiState.value.pageReading)
        assertEquals(TandemError.NetworkUnavailable, vm.uiState.value.failure)
    }

    @Test
    fun theAiCanSortTheLinesIntoTheStepsAndItCanBeUndone() = runTest {
        // #40: offered with an AI provider for a result with steps and lines; one more AI call.
        val vm = viewModel(RecipeImportKey(sharedText = "https://example.com/recept/pannkakor"))
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        vm.onAction(RecipeImportAction.Import)
        assertTrue(vm.uiState.value.canSortSteps)
        val before = SampleImports.pancakes.draft
        val moved = before.copy(
            steps = listOf(
                before.steps[0].copy(ingredients = before.steps[0].ingredients.take(3)),
                before.steps[1].copy(ingredients = before.steps[0].ingredients.drop(3) + before.steps[1].ingredients),
            ),
        )
        imports.sorted = moved
        vm.onAction(RecipeImportAction.SortSteps)
        assertEquals(listOf(before), imports.sortRequests)
        assertEquals(moved, vm.uiState.value.result?.draft)
        assertTrue(vm.uiState.value.stepsSorted)
        assertEquals(RecipeImportEffect.Message(R.string.recipe_import_sort_done), effects.last())
        vm.onAction(RecipeImportAction.UndoSortSteps)
        assertEquals(before, vm.uiState.value.result?.draft)
        assertFalse(vm.uiState.value.stepsSorted)

        imports.sortFailure = RecipeImportProblem.STEP_SORT_CHANGED.toError()
        vm.onAction(RecipeImportAction.SortSteps)
        assertEquals(before, vm.uiState.value.result?.draft)
        assertEquals(RecipeImportEffect.Message(R.string.recipe_import_sort_changed), effects.last())
        imports.aiAvailable.value = false
        assertFalse(vm.uiState.value.canSortSteps)
    }

    @Test
    fun everyProblemHasItsMessage() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        // A text: a link the server cannot read is read in the app instead (aLinkTheServerCannotRead…).
        vm.onAction(RecipeImportAction.SetInput("Pannkakor utan länk"))
        for ((problem, text) in listOf(
            RecipeImportProblem.NOTHING_FOUND to R.string.recipe_import_nothing_found,
            RecipeImportProblem.AI_UNAVAILABLE to R.string.recipe_import_ai_unavailable,
            RecipeImportProblem.FILE_TOO_LARGE to R.string.recipe_import_file_too_large,
            RecipeImportProblem.DEMO_MODE to R.string.recipe_import_demo_problem,
        )) {
            imports.failure = problem.toError()
            vm.onAction(RecipeImportAction.Import)
            assertEquals(problem.name, text, vm.uiState.value.problem)
            assertNull(vm.uiState.value.failure)
        }
        imports.failure = TandemError.NetworkUnavailable
        vm.onAction(RecipeImportAction.Import)
        assertEquals(R.string.recipe_import_failed, vm.uiState.value.problem)
        assertEquals(TandemError.NetworkUnavailable, vm.uiState.value.failure)
        imports.failure = null
        imports.readOnly = true
        vm.onAction(RecipeImportAction.Import)
        assertEquals(R.string.recipe_import_read_only, vm.uiState.value.problem)
        vm.onAction(RecipeImportAction.FileUnreadable)
        assertEquals(R.string.recipe_import_file_unreadable, vm.uiState.value.problem)
    }

    @Test
    fun aGuestAndDemoModeCannotImport() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        collect(vm.uiState)
        vm.onAction(RecipeImportAction.SetInput("https://example.com/x"))
        assertTrue(vm.uiState.value.readOnly)
        assertFalse(vm.uiState.value.canImport || vm.uiState.value.canImportFile)
        permissions.setGuest(false)
        permissions.state.value = permissions.state.value.copy(isDemo = true)
        assertTrue(vm.uiState.value.isDemo)
        // A text is read on the device, so the button stays; a link answers "not in demo mode".
        assertTrue(vm.uiState.value.canImport)
        assertFalse(vm.uiState.value.canImportFile)
    }

    @Test
    fun aSharedFileIsHandedToTheRouteOnceAndTheInputSurvivesProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val key = RecipeImportKey(sharedFileUri = "content://media/1", sharedMimeType = "image/jpeg")
        val vm = viewModel(key, handle)
        collect(vm.uiState)
        assertEquals(SharedFile("content://media/1", "image/jpeg"), vm.uiState.value.sharedFile)
        vm.onAction(RecipeImportAction.SharedFileTaken)
        assertNull(vm.uiState.value.sharedFile)
        vm.onAction(RecipeImportAction.SetInput("https://example.com/x"))
        val again = viewModel(key, handle)
        collect(again.uiState)
        assertNull(again.uiState.value.sharedFile)
        assertEquals("https://example.com/x", again.uiState.value.input)
    }
}
