package com.tgmon.app.di

import android.content.Context
import com.tgmon.app.data.database.AppDatabase
import com.tgmon.app.data.preferences.PreferencesManager
import com.tgmon.app.data.preferences.ServiceUrlManager
import com.tgmon.app.domain.AlertManager
import com.tgmon.app.domain.MetricCache
import com.tgmon.app.domain.PreferencesAlertManager
import com.tgmon.app.domain.ContainerAlertManager
import com.tgmon.app.domain.RoomMetricCache
import com.tgmon.app.domain.repository.TgMonRepository
import com.tgmon.app.presentation.notifications.TgMonNotificationManager
import com.tgmon.app.presentation.viewmodel.WsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePreferencesManager(
        @ApplicationContext context: Context
    ): PreferencesManager {
        return PreferencesManager(context).also { pm ->
            com.tgmon.app.data.api.RetrofitClient.setTokenProvider { pm.getToken() }
            com.tgmon.app.data.api.RetrofitClient.setServerUrlProvider { pm.getServerUrl() }

            // When token is invalidated by the API, clear it and restart MainActivity directing to setup
            val appContext = context.applicationContext
            com.tgmon.app.data.api.RetrofitClient.setTokenInvalidationCallback {
                try {
                    pm.clearToken()
                } catch (e: Exception) {
                    // ignore
                }

                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    try {
                        android.util.Log.w("TgMon", "Token inválido o expirado, forzando cierre de sesión")
                        android.widget.Toast.makeText(appContext, "Sesión expirada. Por favor, reconfigura el token.", android.widget.Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        // ignore toast/log failures
                    }

                    try {
                        val intent = android.content.Intent(appContext, com.tgmon.app.MainActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        intent.putExtra("force_setup", true)
                        appContext.startActivity(intent)
                    } catch (e: Exception) {
                        // ignore errors launching activity from non-UI context
                    }
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideTgMonRepository(
        preferencesManager: PreferencesManager,
        database: AppDatabase
    ): TgMonRepository {
        return TgMonRepository(preferencesManager, database = database)
    }

    @Provides
    @Singleton
    fun provideServiceUrlManager(
        @ApplicationContext context: Context
    ): ServiceUrlManager {
        return ServiceUrlManager(context)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideContainerLogDao(
        database: AppDatabase
    ) = database.containerLogDao()

    @Provides
    @Singleton
    fun provideMetricHistoryDao(
        database: AppDatabase
    ) = database.metricHistoryDao()

    @Provides
    @Singleton
    fun provideRoomMetricCache(
        @ApplicationContext context: Context
    ): RoomMetricCache {
        return RoomMetricCache(context)
    }

    @Provides
    @Singleton
    fun provideNotificationManager(
        @ApplicationContext context: Context
    ): TgMonNotificationManager {
        return TgMonNotificationManager(context)
    }

    @Provides
    @Singleton
    fun providePreferencesAlertManager(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager
    ): PreferencesAlertManager {
        return PreferencesAlertManager(context, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideContainerAlertManager(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        notificationManager: TgMonNotificationManager
    ): ContainerAlertManager {
        return ContainerAlertManager(context, preferencesManager, notificationManager)
    }

    @Provides
    @Singleton
    fun provideAlertManager(
        preferencesAlertManager: PreferencesAlertManager
    ): AlertManager {
        return preferencesAlertManager
    }

    @Provides
    @Singleton
    fun provideMetricCache(
        roomMetricCache: RoomMetricCache
    ): MetricCache {
        return roomMetricCache
    }

    @Provides
    @Singleton
    @WsClient
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
