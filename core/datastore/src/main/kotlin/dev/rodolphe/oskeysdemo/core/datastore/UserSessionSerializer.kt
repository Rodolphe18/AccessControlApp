package dev.rodolphe.oskeysdemo.core.datastore

import androidx.datastore.core.Serializer
import dev.rodolphe.oskeysdemo.core.crypto.KeystoreEncryptor
import dev.rodolphe.oskeysdemo.core.datastore.proto.UserSession
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Reads/writes the [UserSession] proto, encrypting the bytes with a Keystore-backed key. The file on
 * disk is therefore ciphertext — the JWT never lands in cleartext.
 *
 * On unreadable data (empty file on first run, or a key that no longer decrypts) we fall back to the
 * logged-out default rather than crash: the worst case is the resident logs in again.
 */
class UserSessionSerializer @Inject constructor(
    private val encryptor: KeystoreEncryptor,
) : Serializer<UserSession> {

    override val defaultValue: UserSession = UserSession.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): UserSession {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            UserSession.parseFrom(encryptor.decrypt(bytes))
        } catch (_: Exception) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: UserSession, output: OutputStream) {
        output.write(encryptor.encrypt(t.toByteArray()))
    }
}
