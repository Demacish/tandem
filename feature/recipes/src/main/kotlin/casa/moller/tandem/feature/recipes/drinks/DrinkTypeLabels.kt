package casa.moller.tandem.feature.recipes.drinks

import android.content.Context
import casa.moller.tandem.core.model.drink.Drink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * Fas S komplettering (finding 29): drink types as the UI shows them, and the UI's language for
 * sorting. The server keeps the Swedish type names ("Vin", "Öl"), so sorting by them put the chips
 * of an English UI in Swedish order ("Alcohol-free, Cider, Cocktail, Wine, Beer"); the screens sort
 * by the shown label ([drinkTypeLabel]) with a [Collator] for the UI language instead.
 */
interface DrinkTypeLabels {
    /** [name] in the UI's language ("Vin" -> "Wine"); any other type as it is. */
    fun label(name: String): String

    /** The UI's language (the per-app language, or the device's). */
    val locale: Locale
}

/** The type keywords [types] in the order of their shown labels. */
fun DrinkTypeLabels.sortedTypes(types: Collection<String>): List<String> {
    val collator = collator()
    return types.map { it to label(it) }.sortedWith { a, b -> collator.compare(a.second, b.second) }.map { it.first }
}

/** [drinks] by name, each with its types in the order of their shown labels. */
fun DrinkTypeLabels.sortedDrinks(drinks: List<Drink>): List<Drink> {
    val collator = collator()
    return drinks.map { it.copy(types = sortedTypes(it.types)) }.sortedWith { a, b -> collator.compare(a.name, b.name) }
}

private fun DrinkTypeLabels.collator(): Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

/** [DrinkTypeLabels] from the app's resources, read for every call (a language change applies at once). */
class ResourceDrinkTypeLabels @Inject constructor(@ApplicationContext private val context: Context) : DrinkTypeLabels {
    override fun label(name: String): String = drinkTypeRes(name)?.let(context::getString) ?: name

    override val locale: Locale
        get() = context.resources.configuration.locales[0] ?: Locale.getDefault()
}

/** Binds the drink screens' type labels. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DrinkTypeLabelsModule {
    @Binds
    abstract fun bindDrinkTypeLabels(implementation: ResourceDrinkTypeLabels): DrinkTypeLabels
}
