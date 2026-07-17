package dev.rodolphe.oskeysdemo.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.rodolphe.oskeysdemo.core.crypto.KeystoreEncryptor
import dev.rodolphe.oskeysdemo.core.datastore.UserSessionSerializer
import dev.rodolphe.oskeysdemo.core.datastore.proto.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    // Alias for the AES key that encrypts the session file. Distinct from the door-key aliases used
    // later, so wiping the session never touches a door credential.
    private const val SESSION_KEY_ALIAS = "oskeys_session_key"

    @Provides
    @Singleton
    fun provideKeystoreEncryptor(): KeystoreEncryptor = KeystoreEncryptor(SESSION_KEY_ALIAS)

    @Provides
    @Singleton
    fun provideUserSessionDataStore(
        @ApplicationContext context: Context,
        serializer: UserSessionSerializer,
    ): DataStore<UserSession> = DataStoreFactory.create(
        serializer = serializer,
        // No migrations: this app has no legacy SharedPreferences to import — we start on DataStore.
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        context.dataStoreFile("user_session.pb")
    }
}
