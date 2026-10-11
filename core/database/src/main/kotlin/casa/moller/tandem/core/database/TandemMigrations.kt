package casa.moller.tandem.core.database

import androidx.room3.migration.Migration

/**
 * Every migration of [TandemDatabase], in version order. Empty while the schema is at version 1
 * (regenerated until 0.1.0 ships). From then on each schema change adds `MIGRATION_<n>_<n+1>`
 * here, bumps [TandemDatabase.VERSION], commits the new `schemas/.../<n+1>.json` and adds a case
 * to `TandemMigrationTest` (create at n with `MigrationTestHelper`, insert rows, migrate and
 * validate, check the rows). Destructive fallback is never enabled: offline changes in the
 * outbox must survive an app update.
 */
object TandemMigrations {
    val ALL: List<Migration> = emptyList()
}
