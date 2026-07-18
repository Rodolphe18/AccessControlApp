package dev.rodolphe.syeksodemo.core.model

/**
 * The resident's session, as the rest of the app sees it — a plain domain type, deliberately not the
 * generated proto. Storage details (proto, encryption) stay behind :core:datastore.
 */
data class Session(
    val jwt: String,
    val isLoggedIn: Boolean,
    val userId: String,
    val displayName: String,
    val email: String,
) {
    companion object {
        val LOGGED_OUT = Session(
            jwt = "",
            isLoggedIn = false,
            userId = "",
            displayName = "",
            email = "",
        )
    }
}
