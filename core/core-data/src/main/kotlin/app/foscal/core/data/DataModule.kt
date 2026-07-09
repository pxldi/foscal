package app.foscal.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @dagger.Provides
    @Singleton
    fun provideCalendarRepository(impl: CalendarContractRepository): CalendarRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun bindPreferences(impl: UserPreferencesRepository): Preferences
}
