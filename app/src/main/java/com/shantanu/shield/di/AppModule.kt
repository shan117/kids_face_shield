package com.shantanu.shield.di

import android.content.Context
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.face.FaceRecognitionManager
import com.shantanu.shield.ui.stats.StatsRepository
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
    fun provideDataStoreManager(@ApplicationContext context: Context): DataStoreManager {
        return DataStoreManager(context)
    }

    @Provides
    @Singleton
    fun provideFaceRecognitionManager(@ApplicationContext context: Context): FaceRecognitionManager {
        return FaceRecognitionManager(context)
    }

    @Provides
    @Singleton
    fun provideStatsRepository(
        @ApplicationContext context: Context,
        dataStoreManager: DataStoreManager
    ): StatsRepository = StatsRepository(context, dataStoreManager)
}
