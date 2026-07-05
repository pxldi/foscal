package app.calendarium.core.data

import android.content.ContentResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideContentResolver(context: android.content.Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideCalendarRepository(impl: CalendarContractRepository): CalendarRepository = impl
}
