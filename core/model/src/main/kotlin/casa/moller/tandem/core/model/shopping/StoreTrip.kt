package casa.moller.tandem.core.model.shopping

/**
 * One shopping trip in store mode (Fas 4 "Inlärd butiksordning", plan §8.2): the categories in
 * the order their first entry was checked. Kept on this device only (a few per store), so the app
 * can propose an aisle order once three trips agree (core:domain `StoreOrderLearning`).
 *
 * @property supermarketId the store the trip was in.
 * @property startedAt when store mode opened (epoch millis); identifies the trip.
 * @property categoryIds the supermarket categories, first check first, each once.
 */
data class StoreTrip(val supermarketId: Long, val startedAt: Long, val categoryIds: List<Long>)
