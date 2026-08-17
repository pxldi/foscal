package app.foscal.di

import app.foscal.notifications.AlarmReminderScheduler
import app.foscal.notifications.ReminderScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // The application-wide CoroutineScope that used to live here existed solely for ReminderSync's
    // provider-observing flow. WorkManager owns that lifecycle now, and an unused injectable scope
    // is an invitation to start background work that nothing supervises.

    @Provides
    @Singleton
    fun provideReminderScheduler(impl: AlarmReminderScheduler): ReminderScheduler = impl
}
