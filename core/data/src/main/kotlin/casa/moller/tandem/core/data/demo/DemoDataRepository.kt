package casa.moller.tandem.core.data.demo

import casa.moller.tandem.core.model.error.Outcome

/**
 * The demo mode's own data (plan 6.2 "Demoläge"): the sample recipes, meal plan and carousels live
 * in a separate database, filled from [DemoContent]; nothing in it ever reaches a server.
 *
 * - **Threading:** main-safe.
 */
interface DemoDataRepository {
    /**
     * "Reset demo data" ("Återställ demodata"): throws away every change made in demo mode
     * (pinned recipes, chosen servings, viewed recipes) and fills the demo database again with
     * today's sample data. Works whether demo mode is active or not. Fails only when the demo
     * database cannot be written.
     */
    suspend fun resetDemoData(): Outcome<Unit>
}
