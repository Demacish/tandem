package casa.moller.tandem.core.data.demo

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the one [DemoContent] (its parsed files are cached) for the demo data source. */
@Module
@InstallIn(SingletonComponent::class)
internal object DemoContentModule {
    @Provides
    @Singleton
    fun provideDemoContent(@ApplicationContext context: Context): DemoContent = DemoContent(AndroidDemoAssets(context.assets))
}
