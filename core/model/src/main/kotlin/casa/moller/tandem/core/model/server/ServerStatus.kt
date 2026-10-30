package casa.moller.tandem.core.model.server

import kotlin.time.Instant

/**
 * Optional server features the UI shows or hides (plan 3.6 "Kapabilitetskarta"). Derived first
 * from the version and `space/current`; probes only where that is not enough, at most 3 per start
 * and never against paths that may answer 404 (plan 4.1).
 */
enum class ServerFeature {
    /** `GET /api/recipe/flat/`: the cheap recipe index used by the sync. */
    RECIPE_INDEX,

    /** `POST /api/view-log/`: "recently viewed" in sync with the web app. */
    VIEW_LOG,

    /** `POST /api/cook-log/`: "Done!" with rating and comment. */
    COOK_LOG,

    /** `GET /api/share-link/{id}`: share a recipe link. */
    SHARE_LINK,

    /** Households (2.6+): shopping and meal plans shared per household. */
    HOUSEHOLDS,

    /** The space has an AI provider (`space/current` `ai_enabled`): AI import. */
    AI_IMPORT,

    /** `POST /api/auto-plan/`. */
    AUTO_PLAN,

    /** `GET /api/meal-plan/ical/`. */
    ICAL,

    /** Inventory / pantry (2.6+). */
    INVENTORY,
}

/**
 * What the app may use on the current server.
 *
 * @property version the server version the map was built for, e.g. `2.6.13`; null when unknown
 *   (demo mode or not checked yet).
 * @property features the available optional features.
 */
data class Capabilities(val version: String?, val features: Set<ServerFeature>) {
    operator fun contains(feature: ServerFeature): Boolean = feature in features

    companion object {
        /** Nothing optional: what the app assumes before the server was checked. */
        val None: Capabilities = Capabilities(version = null, features = emptySet())

        /** Everything a Tandoor 2.6 server offers except AI (which depends on the space). */
        val Tandoor26: Capabilities = Capabilities(
            version = "2.6",
            features = ServerFeature.entries.toSet() - ServerFeature.AI_IMPORT,
        )
    }
}

/**
 * The server as the app currently sees it (plan 3.6 point 3: checked at start, on resume at most
 * every 15 minutes, and by the background sync).
 *
 * @property version the version the server reported, null when not known yet or in demo mode.
 * @property compatibility how the app treats [version].
 * @property capabilities what the UI may offer.
 * @property checkedAt when the version was last confirmed by the server, null when never.
 * @property isDemo the app runs on local demo data (no server); everything local is allowed.
 * @property capabilitiesResolved (Fas S, rester lane) [capabilities] were resolved with the server
 *   (`space/current`, once per version, the sync's server check) and not only derived from the
 *   version: only then does a missing [ServerFeature.HOUSEHOLDS] mean "the household setup is not
 *   finished".
 */
data class ServerStatus(
    val version: String?,
    val compatibility: ServerCompatibility,
    val capabilities: Capabilities,
    val checkedAt: Instant? = null,
    val isDemo: Boolean = false,
    val capabilitiesResolved: Boolean = false,
) {
    /** Safe mode: read normally, queue writes without sending them (plan 3.6). */
    val safeMode: Boolean
        get() = !isDemo && compatibility.isSafeMode

    /** What happens to queued changes. Demo mode has no outbox: its changes stay in the demo data. */
    val writePolicy: WritePolicy
        get() = compatibility.writePolicy

    companion object {
        /** Before the first check: tested line assumed, no optional features. */
        val Unknown: ServerStatus = ServerStatus(
            version = null,
            compatibility = ServerCompatibility.SUPPORTED,
            capabilities = Capabilities.None,
        )

        /** Demo mode: no server, every feature of the demo data available. */
        val Demo: ServerStatus = ServerStatus(
            version = null,
            compatibility = ServerCompatibility.SUPPORTED,
            capabilities = Capabilities.Tandoor26,
            isDemo = true,
        )
    }
}

/**
 * The server's version changed since the last check (plan B18: a notice, and safe mode when the
 * new version is untested).
 *
 * @property previousVersion the version seen before.
 * @property newVersion the version the server reports now.
 * @property compatibility how the app treats [newVersion].
 */
data class ServerVersionChange(val previousVersion: String, val newVersion: String, val compatibility: ServerCompatibility)
