package com.example.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthManager(
    private val context: Context,
    private val injectedFirebaseAuth: FirebaseAuth? = null,
    private val injectedCredentialManager: CredentialManager? = null
) {

    companion object {
        private const val TAG = "AuthManager"
        const val GOOGLE_SERVER_CLIENT_ID = "844076337559-mvpmas9lvh40glt9vqd0qn5oe846999b.apps.googleusercontent.com"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    @Volatile
    private var cachedFirebaseAuth: FirebaseAuth? = null

    fun getFirebaseAuth(): FirebaseAuth? {
        if (injectedFirebaseAuth != null) return injectedFirebaseAuth
        cachedFirebaseAuth?.let { return it }
        return synchronized(this) {
            cachedFirebaseAuth?.let { return it }
            val auth = com.example.util.FirebaseInitializer.getAuth(context)
            cachedFirebaseAuth = auth
            auth
        }
    }

    private val credentialManager: CredentialManager by lazy {
        injectedCredentialManager ?: CredentialManager.create(context)
    }

    val currentUser: FirebaseUser?
        get() = getFirebaseAuth()?.currentUser

    val userAccountFlow: Flow<UserAccount?> = callbackFlow {
        val auth = getFirebaseAuth()
        if (auth == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val listener = FirebaseAuth.AuthStateListener { currentAuth ->
            val user = currentAuth.currentUser
            trySend(user?.toUserAccount())
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.toUserAccount())
        awaitClose {
            auth.removeAuthStateListener(listener)
        }
    }

    fun getServerClientId(): String {
        return try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                val id = context.getString(resId)
                if (id.isNotBlank()) id else GOOGLE_SERVER_CLIENT_ID
            } else {
                GOOGLE_SERVER_CLIENT_ID
            }
        } catch (_: Exception) {
            GOOGLE_SERVER_CLIENT_ID
        }
    }

    private fun FirebaseUser.toUserAccount(): UserAccount {
        val provider = providerData.firstOrNull { it.providerId != "firebase" }?.providerId ?: "password"
        return UserAccount(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl?.toString(),
            isAnonymous = isAnonymous,
            providerId = provider,
            isEmailVerified = isEmailVerified
        )
    }

    suspend fun signInWithGoogle(activity: Activity): Result<UserAccount> {
        val auth = getFirebaseAuth() ?: return Result.failure(
            Exception("Authentication service is temporarily unavailable. Please verify your internet connection or try again shortly.")
        )
        val serverClientId = getServerClientId()
        if (serverClientId.isBlank()) {
            return Result.failure(Exception("Google Sign-In configuration error: Missing Web Client ID"))
        }

        // Stage 1: Attempt account discovery with previously authorized accounts (fast auto-select)
        val stage1Result = tryGetGoogleCredential(activity, serverClientId, filterByAuthorizedAccounts = true)

        val credential = if (stage1Result.isSuccess) {
            stage1Result.getOrThrow()
        } else {
            val stage1Exception = stage1Result.exceptionOrNull()
            if (stage1Exception is GetCredentialCancellationException ||
                stage1Exception is GoogleSignInCancelledException ||
                stage1Exception?.message?.contains("cancel", ignoreCase = true) == true) {
                Log.d(TAG, "Google Sign-In was cancelled by user during Stage 1")
                return Result.failure(GoogleSignInCancelledException())
            }

            // Stage 1 is an optimization for previously signed-in accounts. If it fails for ANY reason
            // other than cancellation (such as first-time sign-in, NoCredential, or unhandled provider type),
            // seamlessly proceed to Stage 2 with filterByAuthorizedAccounts = false to display the full account picker.
            Log.d(TAG, "Stage 1 returned no credentials; retrying Stage 2 with filterByAuthorizedAccounts = false")
            val stage2Result = tryGetGoogleCredential(activity, serverClientId, filterByAuthorizedAccounts = false)
            if (stage2Result.isSuccess) {
                stage2Result.getOrThrow()
            } else {
                val stage2Exception = stage2Result.exceptionOrNull()
                if (stage2Exception is GetCredentialCancellationException ||
                    stage2Exception is GoogleSignInCancelledException ||
                    stage2Exception?.message?.contains("cancel", ignoreCase = true) == true) {
                    Log.d(TAG, "Google Sign-In was cancelled by user during Stage 2")
                    return Result.failure(GoogleSignInCancelledException())
                }

                // Secondary fallback using GetSignInWithGoogleOption for older Play Services implementations
                val fallbackResult = tryGetCredentialWithSignInWithGoogle(activity, serverClientId)
                if (fallbackResult.isSuccess) {
                    fallbackResult.getOrThrow()
                } else {
                    val finalException = fallbackResult.exceptionOrNull() ?: stage2Exception ?: stage1Exception ?: Exception("Google Sign-In failed")
                    Log.e(TAG, "Google Sign-In stages failed", finalException)
                    return Result.failure(handleGoogleSignInException(finalException))
                }
            }
        }

        return try {
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    return Result.failure(Exception("Google Sign-In failed: Empty ID token received"))
                }
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = authResult.user
                if (user != null) {
                    Result.success(user.toUserAccount())
                } else {
                    Result.failure(Exception("Failed to get user after Google sign-in"))
                }
            } else {
                Result.failure(Exception("Unsupported credential type returned from Credential Manager: ${credential.type}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase credential sign-in failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    private suspend fun tryGetGoogleCredential(
        activity: Activity,
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean
    ): Result<androidx.credentials.Credential> {
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(filterByAuthorizedAccounts)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activity, request)
            Result.success(result.credential)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun tryGetCredentialWithSignInWithGoogle(
        activity: Activity,
        serverClientId: String
    ): Result<androidx.credentials.Credential> {
        return try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(activity, request)
            Result.success(result.credential)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isNoCredentialException(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        if (throwable is NoCredentialException) return true
        val className = throwable.javaClass.simpleName
        if (className.contains("NoCredential", ignoreCase = true)) return true
        val msg = (throwable.message ?: "") + " " + (throwable.localizedMessage ?: "")
        if (msg.contains("no credential", ignoreCase = true)) return true
        if (msg.contains("no_credential", ignoreCase = true)) return true
        if (throwable is GetCredentialCustomException) {
            if (throwable.type.contains("no_credential", ignoreCase = true)) return true
        }
        return false
    }

    private fun handleGoogleSignInException(e: Throwable): Exception {
        if (e is GetCredentialCancellationException || e is GoogleSignInCancelledException) {
            return GoogleSignInCancelledException()
        }
        val msg = e.message ?: ""
        return when {
            msg.contains("cancelled", ignoreCase = true) || msg.contains("canceled", ignoreCase = true) ->
                GoogleSignInCancelledException()
            isNoCredentialException(e) ->
                Exception("No Google accounts found on this device. Please add a Google account in device Settings and try again.")
            e is GetCredentialProviderConfigurationException ||
            (msg.contains("configuration", ignoreCase = true) && msg.contains("provider", ignoreCase = true)) ->
                Exception("Google Play Services configuration issue. Please ensure Google Play Services is up to date.")
            e is GetCredentialUnsupportedException ||
            msg.contains("unsupported", ignoreCase = true) ->
                Exception("Google Credential Manager is not supported on this device. Please sign in with email.")
            msg.contains("network", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ||
            msg.contains("offline", ignoreCase = true) || msg.contains("connection", ignoreCase = true) ->
                Exception("Network error during Google Sign-In. Please check your internet connection.")
            msg.contains("service unavailable", ignoreCase = true) || msg.contains("SERVICE_UNAVAILABLE", ignoreCase = true) ||
            msg.contains("API_NOT_CONNECTED", ignoreCase = true) ->
                Exception("Authentication service is temporarily unavailable. Please verify Google Play Services and internet connection.")
            msg.contains("16", ignoreCase = true) && (msg.contains("DEVELOPER_ERROR", ignoreCase = true) || msg.contains("API: 16")) ->
                Exception("Google Sign-In configuration mismatch (developer configuration). Please sign in with email or continue offline.")
            msg.contains("reauth", ignoreCase = true) || msg.contains("re-auth", ignoreCase = true) ->
                Exception("Your Google account requires re-authentication. Please sign into Google in device Settings.")
            else ->
                parseAuthError(if (e is Exception) e else Exception(e.message))
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): Result<UserAccount> {
        val auth = getFirebaseAuth() ?: return Result.failure(
            Exception("Authentication service is temporarily unavailable. Please verify your internet connection or try again shortly.")
        )
        return try {
            val result = auth.signInWithEmailAndPassword(email.trim(), pass).await()
            val user = result.user
            if (user != null) {
                Result.success(user.toUserAccount())
            } else {
                Result.failure(Exception("Failed to sign in"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Email sign-in failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    suspend fun signUpWithEmail(email: String, pass: String, displayName: String?): Result<UserAccount> {
        val auth = getFirebaseAuth() ?: return Result.failure(
            Exception("Authentication service is temporarily unavailable. Please verify your internet connection or try again shortly.")
        )
        return try {
            val result = auth.createUserWithEmailAndPassword(email.trim(), pass).await()
            val user = result.user
            if (user != null) {
                if (!displayName.isNullOrBlank()) {
                    val updateRequest = UserProfileChangeRequest.Builder()
                        .setDisplayName(displayName.trim())
                        .build()
                    user.updateProfile(updateRequest).await()
                }
                Result.success(user.toUserAccount())
            } else {
                Result.failure(Exception("Failed to create account"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val auth = getFirebaseAuth() ?: return Result.failure(
            Exception("Authentication service is temporarily unavailable. Please verify your internet connection or try again shortly.")
        )
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    suspend fun updateDisplayName(newName: String): Result<Unit> {
        val user = getFirebaseAuth()?.currentUser ?: return Result.failure(Exception("No user signed in"))
        return try {
            val req = UserProfileChangeRequest.Builder()
                .setDisplayName(newName.trim())
                .build()
            user.updateProfile(req).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Update display name failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    suspend fun updatePhotoUrl(photoUri: Uri?): Result<Unit> {
        val user = getFirebaseAuth()?.currentUser ?: return Result.failure(Exception("No user signed in"))
        return try {
            val req = UserProfileChangeRequest.Builder()
                .setPhotoUri(photoUri)
                .build()
            user.updateProfile(req).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Update photo failed", e)
            Result.failure(parseAuthError(e))
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            getFirebaseAuth()?.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Clear credential state failed", e)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
            Result.failure(e)
        }
    }

    suspend fun reloadUser(): Result<UserAccount?> {
        val user = getFirebaseAuth()?.currentUser ?: return Result.success(null)
        return try {
            user.reload().await()
            Result.success(getFirebaseAuth()?.currentUser?.toUserAccount())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseAuthError(e: Exception): Exception {
        val msg = e.message ?: "Authentication error"
        val friendlyMsg = when {
            msg.contains("ERROR_INVALID_EMAIL", ignoreCase = true) || msg.contains("badly formatted", ignoreCase = true) ->
                "The email address is badly formatted."
            msg.contains("ERROR_WRONG_PASSWORD", ignoreCase = true) || msg.contains("invalid credential", ignoreCase = true) ->
                "Invalid email or password. Please verify your credentials."
            msg.contains("ERROR_USER_NOT_FOUND", ignoreCase = true) ->
                "No account exists with this email address."
            msg.contains("ERROR_EMAIL_ALREADY_IN_USE", ignoreCase = true) ->
                "An account with this email address already exists."
            msg.contains("WEAK_PASSWORD", ignoreCase = true) ->
                "The password is too weak. Please use at least 6 characters."
            msg.contains("NETWORK_ERROR", ignoreCase = true) || msg.contains("network error", ignoreCase = true) ->
                "Network error. Please check your internet connection."
            msg.contains("TOO_MANY_REQUESTS", ignoreCase = true) ->
                "Too many attempts. Please try again in a few moments."
            msg.contains("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", ignoreCase = true) ||
            msg.contains("account-exists-with-different-credential", ignoreCase = true) ->
                "An account already exists with the same email using a different sign-in method."
            msg.contains("ERROR_USER_DISABLED", ignoreCase = true) || msg.contains("user-disabled", ignoreCase = true) ->
                "This account has been disabled."
            else -> msg
        }
        return Exception(friendlyMsg)
    }
}
