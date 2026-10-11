package casa.moller.tandem.core.network.capabilities

import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.error.apiCall
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * The result of [CapabilityResolver.resolve].
 *
 * @property assessment the version's classification (compatibility, tested, write policy).
 * @property capabilities what the UI may offer.
 * @property space `space/current` as read (or passed in); null when it could not be read.
 * @property probesSent extra probe requests sent (0 to [TandoorCapabilities.MAX_PROBES]).
 */
data class CapabilityReport(val assessment: VersionAssessment, val capabilities: Capabilities, val space: SpaceDto?, val probesSent: Int)

/**
 * Builds the capability map for one server (plan 3.6, feature #2): version table, then
 * `space/current`, then at most [TandoorCapabilities.MAX_PROBES] probes when the version cannot
 * decide ([TandoorCapabilities.plan]).
 *
 * Probe answers: 2xx = available; a redirect (Tandoor's 302 to the login page for an unknown
 * path) = not available, next probe. Anything else (403, 404, 5xx, network errors) = not
 * available and NO further probes, so that a blocked or failing server never sees a burst of
 * errors (plan 4.1: CrowdSec counts 403/404 per path).
 */
class CapabilityResolver @Inject constructor() {

    /**
     * @param rawVersion the version from `server-settings/current` (the probe at login, or the
     *   periodic version check).
     * @param space `space/current` if the caller already has it; otherwise it is read here (one
     *   request, not counted as a probe; skipped for [ServerCompatibility.TOO_OLD]).
     */
    suspend fun resolve(api: TandoorApi, rawVersion: String?, space: SpaceDto? = null): CapabilityReport {
        val assessment = TandoorVersionTable.assess(rawVersion)
        if (assessment.compatibility == ServerCompatibility.TOO_OLD) {
            return CapabilityReport(assessment, Capabilities(rawVersion, emptySet()), space, probesSent = 0)
        }
        val currentSpace = space ?: apiCall { api.space.currentSpace() }.getOrNull()
        val plan = TandoorCapabilities.plan(assessment, currentSpace)
        val features = plan.features.toMutableSet()
        var sent = 0
        for (probe in plan.probes) {
            if (sent >= TandoorCapabilities.MAX_PROBES) break
            sent++
            when (runProbe(api, probe)) {
                ProbeAnswer.AVAILABLE -> features += probe.feature
                ProbeAnswer.MISSING -> Unit
                ProbeAnswer.STOP -> break
            }
        }
        return CapabilityReport(assessment, Capabilities(rawVersion, features), currentSpace, sent)
    }

    private enum class ProbeAnswer { AVAILABLE, MISSING, STOP }

    private suspend fun runProbe(api: TandoorApi, probe: CapabilityProbe): ProbeAnswer {
        val response: Response<ResponseBody> = try {
            when (probe) {
                CapabilityProbe.VIEW_LOG -> api.probes.viewLogs()
                CapabilityProbe.HOUSEHOLDS -> api.probes.households()
                CapabilityProbe.INVENTORY -> api.probes.inventoryLocations()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network errors and the HTML guard (a 2xx login page): stop probing.
            return ProbeAnswer.STOP
        }
        response.body()?.close()
        response.errorBody()?.close()
        return when (response.code()) {
            in SUCCESS -> ProbeAnswer.AVAILABLE
            in REDIRECT -> ProbeAnswer.MISSING
            else -> ProbeAnswer.STOP
        }
    }

    private companion object {
        val SUCCESS = 200..299
        val REDIRECT = 300..399
    }
}
