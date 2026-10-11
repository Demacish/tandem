package casa.moller.tandem.feature.recipes.list

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * How the recipe list looks on this device (grid or list). A per-device UI choice, not account
 * data, so it lives with the screen instead of in core:data; nothing is synced or backed up.
 */
interface RecipeListPreferences {
    /** The remembered layout; [RecipeListLayout.GRID] until the user changes it. */
    val layout: Flow<RecipeListLayout>

    /** Remembers [layout]. Never throws for storage problems (the choice is then not kept). */
    suspend fun setLayout(layout: RecipeListLayout)
}

private val Context.recipeListStore: DataStore<Preferences> by preferencesDataStore(name = "recipe_list_ui")

/** [RecipeListPreferences] in a small Preferences DataStore file (`recipe_list_ui`). */
@Singleton
class DataStoreRecipeListPreferences @Inject constructor(@ApplicationContext context: Context) : RecipeListPreferences {
    private val store = context.recipeListStore

    override val layout: Flow<RecipeListLayout> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> RecipeListLayout.entries.firstOrNull { it.name == preferences[LAYOUT] } ?: RecipeListLayout.GRID }

    override suspend fun setLayout(layout: RecipeListLayout) {
        try {
            store.edit { it[LAYOUT] = layout.name }
        } catch (e: IOException) {
            // A full or broken disk only means the choice is not remembered.
        }
    }

    private companion object {
        val LAYOUT = stringPreferencesKey("layout")
    }
}

/** Binds the recipe list's own device preferences. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecipeListPreferencesModule {
    @Binds
    abstract fun bindRecipeListPreferences(implementation: DataStoreRecipeListPreferences): RecipeListPreferences
}
