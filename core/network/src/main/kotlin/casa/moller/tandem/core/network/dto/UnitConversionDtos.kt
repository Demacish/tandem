package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Fas 5, lane handla (plan §8.2 "Sammanslagning i vyn"; FACTS "Fas 5"): the space's own unit
// conversions, read with the small tables and cached per account for the merged shopping rows.

/**
 * One result of `GET /api/unit-conversion/`: [baseAmount] [baseUnit] = [convertedAmount]
 * [convertedUnit], for [food] only, or for every food when it is null. A member may read them, a
 * guest gets 403 (Tandoor's `CustomIsUser`), so the app never asks as a guest.
 */
@Serializable
data class UnitConversionDto(
    val id: Long? = null,
    /** Tandoor's own text, e.g. `"1 dl  = 100 ml"` (two spaces, FACTS "Fas 1 a"). */
    val name: String = "",
    @SerialName("base_amount") val baseAmount: Double = 0.0,
    @SerialName("base_unit") val baseUnit: UnitDto? = null,
    @SerialName("converted_amount") val convertedAmount: Double = 0.0,
    @SerialName("converted_unit") val convertedUnit: UnitDto? = null,
    /** A whole `Food` on the server; only its id and name are read. */
    val food: UnitConversionFoodDto? = null,
)

/** The food of a food-specific [UnitConversionDto] (the server sends the whole food). */
@Serializable
data class UnitConversionFoodDto(val id: Long? = null, val name: String = "")
