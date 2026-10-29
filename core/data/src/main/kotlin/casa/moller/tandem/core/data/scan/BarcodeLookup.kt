package casa.moller.tandem.core.data.scan

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.scan.BarcodeLookupResult

/**
 * An unknown barcode looked up in Open Food Facts (`GET /api/v2/product/{ean}`, plan §8.8): ONLY
 * the code is sent, with the User-Agent `Tandem/<versionName> (+https://github.com/Demacish/tandem)`,
 * through a SEPARATE OkHttp client without any Tandoor interceptor, header, token or cookie. The
 * answer (also "not found") is cached in Room (`barcode_product`). Never called in demo mode
 * (answers [BarcodeLookupResult.NotFound]). Implemented by the skanning lane.
 */
interface BarcodeLookup {
    /** [ean] must be digits only (EAN-8/13, UPC-A); anything else fails without a request. */
    suspend fun lookup(ean: String): Outcome<BarcodeLookupResult>
}
