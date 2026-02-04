package com.callrecorder.app.di

import android.content.Context
import com.callrecorder.app.data.local.AppDatabase
import com.callrecorder.app.data.local.RecordingDao
import com.callrecorder.app.data.local.SettingsDataStore
import com.callrecorder.app.data.repository.ContactsRepository
import com.callrecorder.app.data.repository.RecordingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideRecordingDao(database: AppDatabase): RecordingDao {
        return database.recordingDao()
    }

    @Provides
    @Singleton
    fun provideRecordingRepository(recordingDao: RecordingDao): RecordingRepository {
        return RecordingRepository(recordingDao)
    }

    @Provides
    @Singleton
    fun provideContactsRepository(
        @ApplicationContext context: Context
    ): ContactsRepository {
        return ContactsRepository(context)
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ): SettingsDataStore {
        return SettingsDataStore(context)
    }
}
