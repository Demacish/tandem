package casa.moller.tandem.assistant

import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionNotSupportedException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import casa.moller.tandem.core.model.assistant.AssistantRefusal
import casa.moller.tandem.core.model.assistant.AssistantResult

/**
 * Fas 5 (funktioner lane): every [AssistantRefusal] as the [AppFunctionException] that names its
 * cause, with a plain-language message for the assistant (which tells the user in its own words
 * and language; the messages are not shown by Tandem, so they are English like the functions'
 * descriptions). docs/BYGGE.md "App Functions" lists them.
 */
internal object AssistantErrors {
    fun exceptionFor(refusal: AssistantResult.Refused): AppFunctionException = when (refusal.refusal) {
        AssistantRefusal.TURNED_OFF -> AppFunctionDisabledException(
            "The user has not let assistants use Tandem. They can turn on \"Let the assistant use Tandem\" " +
                "in Tandem under More > Assistant.",
        )

        AssistantRefusal.LOCKED -> AppFunctionPermissionRequiredException(
            "Tandem is locked. The user can open Tandem and unlock it, " +
                "or allow the assistant while the app is locked in Tandem's lock settings.",
        )

        AssistantRefusal.NOT_LOGGED_IN -> AppFunctionPermissionRequiredException(
            "Nobody is signed in to Tandem. The user can open Tandem and sign in to their Tandoor server, or try the demo.",
        )

        AssistantRefusal.READ_ONLY -> AppFunctionNotSupportedException(
            "The user is a guest in this Tandoor space and can only read; Tandem does not change anything for a guest.",
        )

        AssistantRefusal.SAFE_MODE -> AppFunctionNotSupportedException(
            "Tandem is in safe mode because the Tandoor server runs a version Tandem has not been tested with, " +
                "so it takes no changes from an assistant. The user can add the item in Tandem.",
        )

        AssistantRefusal.NOTHING_TO_ADD -> AppFunctionInvalidArgumentException("The text to add is empty.")

        AssistantRefusal.AMOUNT_FOR_SEVERAL_ITEMS -> AppFunctionInvalidArgumentException(
            "An amount or unit can only be given for a single item. Put the amounts in the text instead, for example \"2 l milk, 6 eggs\".",
        )

        AssistantRefusal.INVALID_AMOUNT -> AppFunctionInvalidArgumentException("The amount must be a number of zero or more.")

        AssistantRefusal.LIST_NOT_FOUND -> AppFunctionElementNotFoundException(
            refusal.detail?.let { "There is no shopping list with that name. The lists are: $it." }
                ?: "There is no shopping list with that name, and the household has no named lists. Leave the list name out.",
        )

        AssistantRefusal.EMPTY_QUERY -> AppFunctionInvalidArgumentException("The search text is empty.")

        AssistantRefusal.RECIPE_NOT_FOUND -> AppFunctionElementNotFoundException(
            "No recipe with that id is stored in Tandem. searchRecipes gives the ids of the stored recipes.",
        )

        AssistantRefusal.NOT_SAVED -> AppFunctionAppUnknownException("Tandem could not save on the phone. The user can try again later.")
    }

    /** The value of [result], or throws the matching exception. */
    fun <T> valueOf(result: AssistantResult<T>): T = when (result) {
        is AssistantResult.Done -> result.value
        is AssistantResult.Refused -> throw exceptionFor(result)
    }
}
