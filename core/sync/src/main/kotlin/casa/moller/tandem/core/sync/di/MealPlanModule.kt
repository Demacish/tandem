package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.mealplan.MealPlanOutboxHandler
import casa.moller.tandem.core.sync.mealplan.MealTypeOutboxHandler
import casa.moller.tandem.core.sync.mealplan.OfflineFirstMealPlanRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Phase 2 (matsedel lane): the meal plan's data layer (replaces the app's
 * `TemporaryFas2MealPlanModule`): the repository and the outbox senders of `meal_plan` and
 * `meal_type` (docs/BYGGE.md "Matsedeln – data och synk").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MealPlanModule {
    @Binds
    abstract fun bindMealPlanRepository(impl: OfflineFirstMealPlanRepository): MealPlanRepository

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.MEAL_PLAN)
    abstract fun bindMealPlanHandler(impl: MealPlanOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.MEAL_TYPE)
    abstract fun bindMealTypeHandler(impl: MealTypeOutboxHandler): OutboxHandler
}
