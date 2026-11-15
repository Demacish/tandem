package casa.moller.tandem.assistant

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionName
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.testing.AppFunctionTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.assistant.AssistantAddition
import casa.moller.tandem.core.model.assistant.AssistantMeal
import casa.moller.tandem.core.model.assistant.AssistantMealDay
import casa.moller.tandem.core.model.assistant.AssistantRecipe
import casa.moller.tandem.core.model.assistant.AssistantRefusal
import casa.moller.tandem.core.model.assistant.AssistantResult
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.assistant.AssistantShoppingItem
import casa.moller.tandem.core.testing.data.FakeAssistantSettingsRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Fas 5 (funktioner lane, plan §8.7): Tandem's App Functions as an assistant sees them, run through
 * `AppFunctionManager` with the library's [AppFunctionTestRule] (Robolectric, Android 16). Checks
 * the GENERATED metadata (names, parameters, descriptions, off by default), that each function
 * reaches [casa.moller.tandem.core.data.assistant.AssistantActions] with its arguments ("lägg till
 * mjölk på listan" → `add(mjölk, …)`; the rules behind it are core:sync's
 * `DefaultAssistantActionsTest`), that every refusal is the matching AppFunctionException, the
 * service in the merged manifest, and that "Låt assistenten använda Tandem" switches the functions
 * on and off in the system.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = AssistantTestApplication::class, sdk = [36])
class TandemAppFunctionsTest {
    private val app: AssistantTestApplication = ApplicationProvider.getApplicationContext()

    @get:Rule
    val rule = AppFunctionTestRule(app)

    private val manager: AppFunctionManager by lazy { rule.getAppFunctionManager() }

    private val fake get() = app.fake

    private suspend fun functions(): List<AppFunctionMetadata> =
        manager.searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(app.packageName)))

    private suspend fun function(name: String): AppFunctionMetadata = functions().single { it.id == "$SERVICE#$name" }

    private suspend fun execute(name: String, parameters: AppFunctionData.Builder.() -> Unit = {}): ExecuteAppFunctionResponse {
        val metadata = function(name)
        val data = AppFunctionData.Builder(metadata.parameters, metadata.components).apply(parameters).build()
        return manager.executeAppFunction(ExecuteAppFunctionRequest(app.packageName, metadata.id, data))
    }

    private fun ExecuteAppFunctionResponse.value(): AppFunctionData =
        (this as? ExecuteAppFunctionResponse.Success)?.returnValue ?: throw AssertionError("not a success: $this")

    private fun ExecuteAppFunctionResponse.error(): AppFunctionException =
        (this as? ExecuteAppFunctionResponse.Error)?.error ?: throw AssertionError("not an error: $this")

    // ------------------------------------------------------------------ the generated metadata

    @Test
    fun theGeneratedMetadataNamesEveryFunctionAndParameter() = runBlocking {
        val byName = functions().associateBy { it.id.substringAfter('#') }
        assertEquals(setOf("addToShoppingList", "getShoppingList", "getMealPlan", "searchRecipes", "openRecipe"), byName.keys)
        assertTrue(functions().all { it.id.startsWith("$SERVICE#") })

        fun parameters(name: String) = byName.getValue(name).parameters.map { it.name to it.isRequired }
        assertEquals(listOf("text" to true, "amount" to false, "unit" to false, "listName" to false), parameters("addToShoppingList"))
        assertEquals(listOf("listName" to false), parameters("getShoppingList"))
        assertEquals(listOf("date" to false), parameters("getMealPlan"))
        assertEquals(listOf("query" to true, "maxResults" to false), parameters("searchRecipes"))
        assertEquals(listOf("recipeId" to true, "cookMode" to false), parameters("openRecipe"))

        // The KDoc is what the assistant reads: every function and parameter is described.
        byName.values.forEach { metadata ->
            assertTrue("${metadata.id} has a description", metadata.description.isNotBlank())
            metadata.parameters.forEach { assertTrue("${metadata.id}.${it.name} has a description", it.description.isNotBlank()) }
        }
        assertTrue(byName.getValue("addToShoppingList").description.contains("shopping list"))
    }

    @Test
    fun everyFunctionIsOffInTheSystemUntilTheUserTurnsTheAssistantOn() = runBlocking {
        val names = AssistantFunctionStates.functionIds().map { AppFunctionName(app.packageName, it) }
        assertEquals(5, manager.getAppFunctionStates(names).size)
        assertTrue("off by default", manager.getAppFunctionStates(names).none { it.isEnabled })

        val settings = FakeAssistantSettingsRepository()
        val scope = TestScope(StandardTestDispatcher())
        val states = AssistantFunctionStates(app.packageName, settings, scope) { manager }
        states.start()
        scope.advanceUntilIdle()
        assertTrue(manager.getAppFunctionStates(names).none { it.isEnabled })

        settings.state.value = AssistantSettings(enabled = true)
        scope.advanceUntilIdle()
        assertTrue("on with the setting", manager.getAppFunctionStates(names).all { it.isEnabled })

        settings.state.value = AssistantSettings(enabled = false)
        scope.advanceUntilIdle()
        assertTrue("off again", manager.getAppFunctionStates(names).none { it.isEnabled })
        scope.cancel()
    }

    @Test
    fun theSchemaTheManifestNamesIsPackagedWithEveryFunction() {
        // KSP writes it as the Java resource assets/<name>.xml, which lands in the APK's assets.
        val path = "assets/${BaseTandemAppFunctionService.XML_FILE}.xml"
        val xml = javaClass.classLoader!!.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
        assertNotNull("$path is generated", xml)
        listOf("addToShoppingList", "getShoppingList", "getMealPlan", "searchRecipes", "openRecipe").forEach { name ->
            assertTrue("$name in $path", xml!!.contains("$SERVICE#$name"))
        }
    }

    @Test
    fun theServiceIsInTheMergedManifestForTheSystemOnly() {
        val intent = Intent("android.app.appfunctions.AppFunctionService").setPackage(app.packageName)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        val service = app.packageManager.queryIntentServices(intent, flags).single().serviceInfo
        assertEquals(BaseTandemAppFunctionService.SERVICE_NAME, service.name)
        assertEquals("android.permission.BIND_APP_FUNCTION_SERVICE", service.permission)
        assertTrue(service.exported)
    }

    // ------------------------------------------------------------------ the functions

    @Test
    fun addMilkToTheListReachesTheAssistantActions() = runBlocking {
        fake.addition = AssistantResult.Done(AssistantAddition(listOf(AssistantShoppingItem("mjölk", waitingToSync = true))))

        val added = execute("addToShoppingList") { setString("text", "mjölk") }
            .value().getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .deserialize(AddedToShoppingList::class.java)

        assertEquals(listOf("add(mjölk, null, null, null)"), fake.calls)
        assertEquals(listOf("mjölk"), added.items.map { it.food })
        assertTrue(added.items.single().waitingToSync)
        assertEquals(null, added.listName)

        execute("addToShoppingList") {
            setString("text", "mjölk")
            setDouble("amount", 2.0)
            setString("unit", "l")
            setString("listName", "Veckohandling")
        }.value()
        assertEquals("add(mjölk, 2.0, l, Veckohandling)", fake.calls.last())
    }

    @Test
    fun theListMealsAndRecipesComeBackAsTheirTypes() = runBlocking {
        fake.items = AssistantResult.Done(listOf(AssistantShoppingItem("mjölk", 2.0, "l", lists = listOf("Veckohandling"))))
        val items = execute("getShoppingList").value()
            .getAppFunctionDataList(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .map { it.deserialize(ShoppingListItem::class.java) }
        val milk = ShoppingListItem("mjölk", 2.0, "l", "", listOf("Veckohandling"), buyLater = false, waitingToSync = false)
        assertEquals(listOf(milk), items)
        assertEquals("list(null)", fake.calls.last())

        val pancakes = AssistantMeal("Pannkakor", "Middag", LocalTime(18, 0), recipeId = 7)
        fake.meals = { date -> AssistantResult.Done(AssistantMealDay(date ?: LocalDate(2026, 11, 15), listOf(pancakes))) }
        val day = execute("getMealPlan").value()
            .getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .deserialize(MealPlanForDay::class.java)
        assertEquals("meals(null)", fake.calls.last())
        assertEquals(java.time.LocalDate.of(2026, 11, 15), day.date)
        assertEquals(listOf("Pannkakor"), day.meals.map { it.title })
        assertEquals(java.time.LocalTime.of(18, 0), day.meals.single().time)
        assertEquals(7L, day.meals.single().recipeId)

        fake.recipes = AssistantResult.Done(listOf(AssistantRecipe(7, "Pannkakor", 30, 4.5)))
        val found = execute("searchRecipes") { setString("query", "pannkakor") }.value()
            .getAppFunctionDataList(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .map { it.deserialize(RecipeSummary::class.java) }
        assertEquals("search(pannkakor, 0)", fake.calls.last())
        assertEquals(listOf(RecipeSummary(7, "Pannkakor", 30, 4.5)), found)
    }

    @Test
    fun aDateReachesTheMealPlanAndOpeningARecipeGivesAnIntent() = runBlocking {
        val service = Robolectric.buildService(TandemAppFunctionService::class.java).create().get()
        fake.meals = { date -> AssistantResult.Done(AssistantMealDay(date!!, emptyList())) }
        val day = service.getMealPlan(java.time.LocalDate.of(2026, 11, 20))
        assertEquals("meals(2026-11-20)", fake.calls.last())
        assertEquals(java.time.LocalDate.of(2026, 11, 20), day.date)

        fake.recipe = AssistantResult.Done(AssistantRecipe(7, "Pannkakor"))
        val intent: PendingIntent = service.openRecipe(7, cookMode = true)
        assertNotNull(intent)
        assertEquals("recipe(7)", fake.calls.last())
        assertTrue(execute("openRecipe") { setLong("recipeId", 7) } is ExecuteAppFunctionResponse.Success)
    }

    @Test
    fun everyRefusalIsTheMatchingAppFunctionError() = runBlocking {
        suspend fun addFails(refusal: AssistantRefusal, detail: String? = null): AppFunctionException {
            fake.addition = AssistantResult.Refused(refusal, detail)
            return execute("addToShoppingList") { setString("text", "mjölk") }.error()
        }
        assertTrue(addFails(AssistantRefusal.TURNED_OFF) is AppFunctionDisabledException)
        assertTrue(addFails(AssistantRefusal.LOCKED) is AppFunctionPermissionRequiredException)
        assertTrue(addFails(AssistantRefusal.NOT_LOGGED_IN) is AppFunctionPermissionRequiredException)
        assertTrue(addFails(AssistantRefusal.READ_ONLY) is AppFunctionNotSupportedException)
        assertTrue(addFails(AssistantRefusal.SAFE_MODE) is AppFunctionNotSupportedException)
        assertTrue(addFails(AssistantRefusal.NOTHING_TO_ADD) is AppFunctionInvalidArgumentException)
        assertTrue(addFails(AssistantRefusal.AMOUNT_FOR_SEVERAL_ITEMS) is AppFunctionInvalidArgumentException)
        val list = addFails(AssistantRefusal.LIST_NOT_FOUND, "Veckohandling, Helgen")
        assertTrue(list is AppFunctionElementNotFoundException)
        assertTrue("${list.errorMessage}", list.errorMessage.orEmpty().contains("Veckohandling, Helgen"))

        // Every refusal has a message for the assistant.
        AssistantRefusal.entries.forEach { refusal ->
            assertFalse("$refusal", AssistantErrors.exceptionFor(AssistantResult.Refused(refusal)).errorMessage.isNullOrBlank())
        }

        fake.recipe = AssistantResult.Refused(AssistantRefusal.RECIPE_NOT_FOUND)
        assertTrue(execute("openRecipe") { setLong("recipeId", 9) }.error() is AppFunctionElementNotFoundException)
    }

    private companion object {
        /** A function's id is `<the @AppFunctionServiceEntryPoint class>#<function>` (the compiler's rule). */
        const val SERVICE = "casa.moller.tandem.assistant.BaseTandemAppFunctionService"
    }
}
