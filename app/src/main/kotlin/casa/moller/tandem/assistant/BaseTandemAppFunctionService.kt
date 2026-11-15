package casa.moller.tandem.assistant

import android.app.PendingIntent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.model.assistant.AssistantMeal
import casa.moller.tandem.core.model.assistant.AssistantRecipe
import casa.moller.tandem.core.model.assistant.AssistantShoppingItem
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalDate

/**
 * Fas 5 (funktioner lane, plan §8.7 "App Functions"): Tandem's functions for an assistant on the
 * phone (an app the system lets execute App Functions, such as Gemini), so it can "lägga till mjölk
 * på listan". KSP generates the service [SERVICE_NAME] and the schema `assets/[XML_FILE].xml`
 * from this class; the manifest declares both. Android 16 (API 36) and newer only: below it the
 * system never binds the service and the functions do not exist.
 *
 * Every function is off until the user turns on "Låt assistenten använda Tandem" (Mer →
 * Assistenten): `isEnabled = false` here, [AssistantFunctionStates] enables them in the system, and
 * [AssistantActions] refuses again while the setting is off, the app is locked, nobody is logged in,
 * or (for the write) the user is a guest or the server is in safe mode. Nothing deletes anything.
 * The KDoc is the description the assistant reads (docs/BYGGE.md "App Functions").
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(serviceName = "TandemAppFunctionService", appFunctionXmlFileName = "tandem_app_functions")
abstract class BaseTandemAppFunctionService : AppFunctionService() {

    private val actions: AssistantActions
        get() = (application as AssistantHost).assistantActions

    /**
     * Adds one or more items to the household's shopping list in Tandem, the same way as typing
     * them into the list's add field. Several items can be given in one text, separated by commas
     * or new lines, each with its own amount and unit, for example "2 l mjölk, bröd, 6 ägg". The
     * items are saved on the phone at once and sent to the household's recipe server when there
     * is a connection.
     *
     * @param text The items to add, in the household's language, for example "mjölk" or "2 l mjölk, bröd".
     * @param amount The amount to buy, only when the text names a single item without an amount.
     * @param unit The unit of the amount (for example "l", "dl", "g", "st"), only when the text names a single item.
     * @param listName The name of one of the household's named shopping lists; leave out to add to no named list.
     * @return The items added and the list they went to.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun addToShoppingList(
        text: String,
        amount: Double? = null,
        unit: String? = null,
        listName: String? = null,
    ): AddedToShoppingList = withContext(Dispatchers.IO) {
        val added = AssistantErrors.valueOf(actions.addToShoppingList(text, amount, unit, listName))
        AddedToShoppingList(added.items.map { it.toFunctionItem() }, added.list)
    }

    /**
     * Gets the items still to buy on the household's shopping list in Tandem (items already
     * checked off are left out), at most 200.
     *
     * @param listName The name of one of the household's named shopping lists; leave out for every item.
     * @return The items to buy; empty when there is nothing to buy.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun getShoppingList(listName: String? = null): List<ShoppingListItem> = withContext(Dispatchers.IO) {
        AssistantErrors.valueOf(actions.shoppingList(listName)).map { it.toFunctionItem() }
    }

    /**
     * Gets the meals the household planned in Tandem's meal plan for one day, such as tonight's
     * dinner.
     *
     * @param date The day; leave out for today.
     * @return The day and its planned meals.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun getMealPlan(date: LocalDate? = null): MealPlanForDay = withContext(Dispatchers.IO) {
        val day = AssistantErrors.valueOf(actions.mealPlan(date?.toKotlinLocalDate()))
        MealPlanForDay(day.date.toJavaLocalDate(), day.meals.map { it.toFunctionMeal() })
    }

    /**
     * Searches the recipes stored in Tandem by name, ingredient or text.
     *
     * @param query What to search for, for example "pannkakor" or "kyckling".
     * @param maxResults The most recipes to return, 1 to 25; leave out for 10.
     * @return The matching recipes with their ids; empty when nothing matches.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun searchRecipes(query: String, maxResults: Int = 0): List<RecipeSummary> = withContext(Dispatchers.IO) {
        AssistantErrors.valueOf(actions.searchRecipes(query, maxResults)).map { it.toSummary() }
    }

    /**
     * Opens a recipe stored in Tandem on the screen: the recipe itself, or cook mode with one step
     * at a time.
     *
     * @param recipeId The recipe's id, from searchRecipes or getMealPlan.
     * @param cookMode True to open cook mode instead of the recipe.
     * @return The intent that opens the recipe in Tandem.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun openRecipe(recipeId: Long, cookMode: Boolean = false): PendingIntent = withContext(Dispatchers.IO) {
        val recipe = AssistantErrors.valueOf(actions.recipe(recipeId))
        val destination = if (cookMode) AppOpenIntents.cook(recipe.id) else AppOpenIntents.recipe(recipe.id)
        AppOpenIntents.openPendingIntent(this@BaseTandemAppFunctionService, destination)
            ?: throw AppFunctionAppUnknownException("Tandem could not be opened on this phone.")
    }

    private fun AssistantShoppingItem.toFunctionItem() = ShoppingListItem(
        food = food,
        amount = amount,
        unit = unit,
        note = note,
        lists = lists,
        buyLater = later,
        waitingToSync = waitingToSync,
    )

    private fun AssistantMeal.toFunctionMeal() = PlannedMeal(
        title = title,
        mealType = mealType,
        time = time?.toJavaLocalTime(),
        recipeId = recipeId,
        servings = servings,
        note = note,
    )

    private fun AssistantRecipe.toSummary() = RecipeSummary(id = id, name = name, totalMinutes = totalMinutes, rating = rating)

    companion object {
        /** The class KSP generates (the manifest's service). */
        const val SERVICE_NAME: String = "casa.moller.tandem.assistant.TandemAppFunctionService"

        /** The schema KSP writes to `assets/` (the manifest's `android.app.appfunctions.v2`). */
        const val XML_FILE: String = "tandem_app_functions"
    }
}
