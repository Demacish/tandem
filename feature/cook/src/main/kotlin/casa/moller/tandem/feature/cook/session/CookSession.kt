package casa.moller.tandem.feature.cook.session

import androidx.compose.runtime.Immutable
import casa.moller.tandem.feature.cook.navigation.CookKey

/**
 * One recipe of a cook session (Fas 4, two recipes at once, plan 8.1).
 *
 * @property servings and [startStep] are where its cook mode OPENS; afterwards its own
 *   `CookViewModel` keeps the page, servings and "Klart!" draft in its SavedStateHandle.
 * @property instance tells apart two openings of the same recipe in one session: a recipe that was
 *   closed (or saved with "Klart!") and added again gets a fresh cook mode ([viewModelKey]).
 */
@Immutable
data class CookSessionRecipe(val recipeId: Long, val servings: Double? = null, val startStep: Int = 0, val instance: Int = 0) {
    init {
        require(startStep >= 0) { "startStep is 0-based" }
        require(servings == null || servings > 0.0) { "servings are positive" }
        require(instance >= 0) { "instance is not negative" }
    }

    /** The key of its `CookViewModel` in the cook entry; the first opening keeps cook mode's key of 0.5.0. */
    val viewModelKey: String
        get() = if (instance == 0) "cook-$recipeId" else "cook-$recipeId-$instance"

    /** The per-recipe cook mode's key (what it opens at). */
    fun toCookKey(): CookKey = CookKey(recipeId = recipeId, servings = servings, startStep = startStep)
}

/**
 * The recipes open in one cook mode (Fas 4, plan 8.1, Kitshn #433): one, or two side by side as
 * tabs, each with its own step, servings and "Klart!", and the device's timers shared. Pure; kept
 * in the cook entry's SavedStateHandle as one string ([encode]) so both recipes and the selected
 * tab survive rotation and process death.
 *
 * @property recipes in tab order, at most [MAX_RECIPES], each recipe once.
 * @property activeRecipeId the recipe shown now, one of [recipes].
 * @property nextInstance the [CookSessionRecipe.instance] the next added recipe gets.
 */
@Immutable
data class CookSession(val recipes: List<CookSessionRecipe>, val activeRecipeId: Long, val nextInstance: Int = 1) {
    init {
        require(recipes.isNotEmpty() && recipes.size <= MAX_RECIPES) { "one or two recipes" }
        require(recipes.map { it.recipeId }.distinct().size == recipes.size) { "each recipe once" }
        require(recipes.any { it.recipeId == activeRecipeId }) { "the active recipe is open" }
        require(nextInstance > 0) { "instances after the first are positive" }
    }

    val recipeIds: List<Long>
        get() = recipes.map { it.recipeId }

    val active: CookSessionRecipe
        get() = recipes.first { it.recipeId == activeRecipeId }

    /** Two recipes are open: cook mode shows tabs. */
    val isTwo: Boolean
        get() = recipes.size > 1

    /** "Laga ett recept till" is offered. */
    val canAdd: Boolean
        get() = recipes.size < MAX_RECIPES

    operator fun contains(recipeId: Long): Boolean = recipes.any { it.recipeId == recipeId }

    /**
     * Opens [recipeId] beside the open recipe and selects it. An open recipe is only selected; a
     * full session is unchanged.
     */
    fun add(recipeId: Long, servings: Double? = null, startStep: Int = 0): CookSession = when {
        recipeId in this -> select(recipeId)

        !canAdd -> this

        else -> copy(
            recipes = recipes + CookSessionRecipe(recipeId, servings, startStep, instance = nextInstance),
            activeRecipeId = recipeId,
            nextInstance = nextInstance + 1,
        )
    }

    /** Shows [recipeId]'s tab; a recipe that is not open changes nothing. */
    fun select(recipeId: Long): CookSession = if (recipeId in this) copy(activeRecipeId = recipeId) else this

    /**
     * Closes [recipeId] (✕ in two-recipe mode, or its "Klart!" was saved): the other recipe stays
     * and is selected. Null when it was the only recipe: cook mode closes.
     */
    fun remove(recipeId: Long): CookSession? {
        if (recipeId !in this) return this
        val left = recipes.filterNot { it.recipeId == recipeId }
        if (left.isEmpty()) return null
        val active = if (activeRecipeId == recipeId) left.first().recipeId else activeRecipeId
        return copy(recipes = left, activeRecipeId = active)
    }

    /**
     * Puts [recipe] back at tab [index] and selects it ("Ångra" after ✕). It keeps its [instance][CookSessionRecipe.instance],
     * so its cook mode, still in the entry's ViewModelStore, comes back as it was. Open or full: nothing changes.
     */
    fun restore(recipe: CookSessionRecipe, index: Int): CookSession = when {
        recipe.recipeId in this -> select(recipe.recipeId)

        !canAdd -> this

        else -> copy(
            recipes = recipes.toMutableList().apply { add(index.coerceIn(0, size), recipe) },
            activeRecipeId = recipe.recipeId,
        )
    }

    /** "active|next|id:step:servings:instance,…" (servings "-" when not chosen). */
    fun encode(): String = buildString {
        append(activeRecipeId).append('|').append(nextInstance).append('|')
        recipes.joinTo(this, ",") { "${it.recipeId}:${it.startStep}:${it.servings ?: "-"}:${it.instance}" }
    }

    companion object {
        /** Kitshn #433 asks for two; more would not fit a phone's tabs with kitchen-sized targets. */
        const val MAX_RECIPES: Int = 2

        /** What [key] opens: its recipe, and the one already being cooked first when it names one. */
        fun of(key: CookKey): CookSession {
            val main = CookSessionRecipe(key.recipeId, key.servings, key.startStep)
            val alongside = key.alongsideRecipeId?.let { CookSessionRecipe(it, startStep = key.alongsideStep) }
            return CookSession(recipes = listOfNotNull(alongside, main), activeRecipeId = key.recipeId)
        }

        /** The inverse of [encode]; null for anything else. */
        fun decode(text: String?): CookSession? {
            val parts = text?.split('|') ?: return null
            if (parts.size != ENCODED_PARTS) return null
            val active = parts[0].toLongOrNull() ?: return null
            val next = parts[1].toIntOrNull() ?: return null
            val recipes = parts[2].split(',').map { decodeRecipe(it) ?: return null }
            return runCatching { CookSession(recipes, active, next) }.getOrNull()
        }

        private fun decodeRecipe(text: String): CookSessionRecipe? {
            val fields = text.split(':')
            if (fields.size != RECIPE_FIELDS) return null
            val id = fields[0].toLongOrNull() ?: return null
            val step = fields[1].toIntOrNull() ?: return null
            val servings = if (fields[2] == "-") null else fields[2].toDoubleOrNull() ?: return null
            val instance = fields[3].toIntOrNull() ?: return null
            return runCatching { CookSessionRecipe(id, servings, step, instance) }.getOrNull()
        }

        private const val ENCODED_PARTS = 3
        private const val RECIPE_FIELDS = 4
    }
}
