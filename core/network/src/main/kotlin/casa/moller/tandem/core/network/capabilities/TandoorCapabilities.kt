package casa.moller.tandem.core.network.capabilities

import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.probe.TandoorVersion

/** A feature whose presence only a request can tell; see [TandoorCapabilities.plan]. */
enum class CapabilityProbe(val feature: ServerFeature) {
    VIEW_LOG(ServerFeature.VIEW_LOG),
    HOUSEHOLDS(ServerFeature.HOUSEHOLDS),
    INVENTORY(ServerFeature.INVENTORY),
}

/**
 * What the version and `space/current` already decide, and what is left to probe.
 *
 * @property features the features known to be available.
 * @property probes features to probe (at most [TandoorCapabilities.MAX_PROBES]); empty for every
 *   version the app knows.
 */
data class CapabilityPlan(val features: Set<ServerFeature>, val probes: List<CapabilityProbe>)

/**
 * The capability map (plan 3.6, feature #2): built FIRST from the version and `space/current`
 * (`ai_enabled`, `household_setup_completed`); probes only where those cannot decide, at most
 * [MAX_PROBES] per start, and only list endpoints, which answer an unknown path with a 302 to the
 * login page rather than a 404 (plan 4.1, FACTS g).
 *
 * | Feature | Rule |
 * |---|---|
 * | RECIPE_INDEX, COOK_LOG, VIEW_LOG, SHARE_LINK, AUTO_PLAN, ICAL | every Tandoor 2.x |
 * | INVENTORY | 2.6.0 and later |
 * | HOUSEHOLDS | 2.6.0 and later, and the space finished its household setup |
 * | AI_IMPORT | the space has `ai_enabled` |
 *
 * A version that cannot be parsed (a development build) gets the 2.x base set minus VIEW_LOG,
 * and VIEW_LOG, INVENTORY and HOUSEHOLDS (the latter only when the space says it is set up) are
 * probed. A newer version than the tested ones keeps every feature of 2.6: Tandoor has never
 * removed these endpoints, and safe mode already pauses the writes.
 */
object TandoorCapabilities {
    /** Plan 4.1: at most 3 capability probes per start. */
    const val MAX_PROBES: Int = 3

    private val SINCE_2_6: TandoorVersion = TandoorVersion(2, 6, 0)

    private val BASE_2X: Set<ServerFeature> = setOf(
        ServerFeature.RECIPE_INDEX,
        ServerFeature.COOK_LOG,
        ServerFeature.VIEW_LOG,
        ServerFeature.SHARE_LINK,
        ServerFeature.AUTO_PLAN,
        ServerFeature.ICAL,
    )

    /** What [assessment] and [space] (null when not read) decide, and which probes are left. */
    fun plan(assessment: VersionAssessment, space: SpaceDto?): CapabilityPlan {
        val features = mutableSetOf<ServerFeature>()
        val probes = mutableListOf<CapabilityProbe>()
        val householdsSetUp = space?.householdSetupCompleted == true
        if (space?.aiEnabled == true) features += ServerFeature.AI_IMPORT

        val version = assessment.version
        when {
            assessment.compatibility == ServerCompatibility.TOO_OLD -> Unit

            version == null -> {
                features += BASE_2X - ServerFeature.VIEW_LOG
                probes += CapabilityProbe.VIEW_LOG
                probes += CapabilityProbe.INVENTORY
                if (householdsSetUp) probes += CapabilityProbe.HOUSEHOLDS
            }

            else -> {
                features += BASE_2X
                if (version >= SINCE_2_6) {
                    features += ServerFeature.INVENTORY
                    if (householdsSetUp) features += ServerFeature.HOUSEHOLDS
                }
            }
        }
        return CapabilityPlan(features, probes.take(MAX_PROBES))
    }

    /** The capabilities when no probe is run (or all of them failed). */
    fun fromVersionAndSpace(rawVersion: String?, space: SpaceDto?): Capabilities =
        Capabilities(version = rawVersion, features = plan(TandoorVersionTable.assess(rawVersion), space).features)
}
