package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Fas S (drycker lane): the answers of DrinkService that are no recipe or keyword (FACTS "Fas S" c, f).

/** `PUT /api/keyword/{id}/move/{parent}/` → `{"msg": "… was moved successfully to parent …"}`. */
@Serializable
data class KeywordMoveDto(val msg: String = "")

/** `PUT /api/recipe/{id}/image/` → the stored image as a path relative to the server (`/media/recipes/…`). */
@Serializable
data class RecipeImageDto(val image: String? = null, @SerialName("image_url") val imageUrl: String? = null)
