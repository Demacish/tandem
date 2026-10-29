package casa.moller.tandem.feature.pantry.navigation

/**
 * Fas S (hemma-ui lane): the scanners "Hemma" offers (feature:scan, B21/B22). feature:pantry never
 * knows feature:scan's keys (checkModuleGraph rule 2), so the route asks for one of these and the
 * shell opens `BarcodeScanKey`, `ReceiptScanKey` or `FridgeScanKey` with the location.
 */
enum class PantryScanner {
    /** A barcode (#27). */
    BARCODE,

    /** A photographed receipt (#44). */
    RECEIPT,

    /** A photo of the fridge (#45, experimental, own AI key). */
    FRIDGE,
}
