package dev.rodolphe.syeksodemo.core.network.di

import dev.rodolphe.syeksodemo.core.network.BuildConfig
import dev.rodolphe.syeksodemo.core.network.IntercomApiService
import dev.rodolphe.syeksodemo.core.network.SyeksoApiService
import dev.rodolphe.syeksodemo.core.network.signaling.OkHttpSignalingTransport
import dev.rodolphe.syeksodemo.core.network.signaling.Signaling
import dev.rodolphe.syeksodemo.core.network.signaling.SignalingClient
import dev.rodolphe.syeksodemo.core.network.signaling.SignalingTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true // tolerate new server fields without breaking older app builds
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            },
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(json: Json, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideSyeksoApiService(retrofit: Retrofit): SyeksoApiService =
        retrofit.create(SyeksoApiService::class.java)

    @Provides
    @Singleton
    fun provideIntercomApiService(retrofit: Retrofit): IntercomApiService =
        retrofit.create(IntercomApiService::class.java)

    @Provides
    @Singleton
    fun provideSignalingTransport(client: OkHttpClient): SignalingTransport =
        OkHttpSignalingTransport(client)

    @Provides
    @Singleton
    fun provideSignaling(transport: SignalingTransport): Signaling =
        SignalingClient(transport)
}
