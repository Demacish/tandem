package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.model.error.TandemError
import java.io.IOException

/**
 * Thrown by Tandem's interceptors with an already classified [error]. It is an [IOException]
 * because OkHttp only propagates those cleanly from interceptors (others crash async calls).
 */
class TandemIOException(val error: TandemError) : IOException(error.toString())
