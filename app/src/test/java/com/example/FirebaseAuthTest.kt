package com.example

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthManager
import com.example.auth.AuthUiState
import com.example.auth.GoogleSignInCancelledException
import com.example.auth.PasswordResetUiState
import com.example.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseAuthTest {

    private lateinit var context: Context
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        authManager = AuthManager.getInstance(context)
    }

    @Test
    fun `web client id is valid and matches google-services`() {
        val serverClientId = authManager.getServerClientId()
        assertNotNull(serverClientId)
        assertTrue("Web Client ID must not be blank", serverClientId.isNotBlank())
        assertEquals(
            "844076337559-mvpmas9lvh40glt9vqd0qn5oe846999b.apps.googleusercontent.com",
            serverClientId
        )
    }

    @Test
    fun `isNoCredentialException detects NoCredentialException and variants`() {
        val noCred = NoCredentialException("No credentials available")
        assertTrue(authManager.isNoCredentialException(noCred))

        val customNoCred = Exception("androidx.credentials.TYPE_NO_CREDENTIAL")
        assertTrue(authManager.isNoCredentialException(customNoCred))

        val textNoCred = Exception("No credentials available for this user")
        assertTrue(authManager.isNoCredentialException(textNoCred))

        val networkErr = Exception("Network error")
        assertFalse(authManager.isNoCredentialException(networkErr))
    }

    @Test
    fun `parseAuthError converts raw errors to friendly messages`() {
        val emailErr = authManager.parseAuthError(Exception("ERROR_INVALID_EMAIL"))
        assertEquals("The email address is badly formatted.", emailErr.message)

        val passErr = authManager.parseAuthError(Exception("ERROR_WRONG_PASSWORD"))
        assertEquals("Invalid email or password. Please verify your credentials.", passErr.message)

        val weakPass = authManager.parseAuthError(Exception("WEAK_PASSWORD"))
        assertEquals("The password is too weak. Please use at least 6 characters.", weakPass.message)

        val netErr = authManager.parseAuthError(Exception("NETWORK_ERROR"))
        assertEquals("Network error. Please check your internet connection.", netErr.message)
    }

    @Test
    fun `password reset invalid email rejects immediately without sending`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MusicViewModel(app)

        viewModel.sendPasswordReset("")
        assertTrue(viewModel.passwordResetState.value is PasswordResetUiState.Error)
        assertEquals(
            "Please enter a valid email address.",
            (viewModel.passwordResetState.value as PasswordResetUiState.Error).message
        )

        viewModel.sendPasswordReset("not-an-email")
        assertTrue(viewModel.passwordResetState.value is PasswordResetUiState.Error)
    }

    @Test
    fun `password reset double tap is blocked when sending or cooling down`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MusicViewModel(app)

        // Artificially put in Sending state
        viewModel.passwordResetState.value = PasswordResetUiState.Sending
        var callbackCalled = false
        viewModel.sendPasswordReset("test@example.com") { success, _ ->
            assertFalse(success)
            callbackCalled = true
        }
        assertTrue("Callback should report cooldown/blocked", callbackCalled)
        assertEquals(PasswordResetUiState.Sending, viewModel.passwordResetState.value)

        // Artificially put in SentCooldown state
        viewModel.passwordResetState.value = PasswordResetUiState.SentCooldown(45)
        var blockedCalled = false
        viewModel.sendPasswordReset("test@example.com") { success, _ ->
            assertFalse(success)
            blockedCalled = true
        }
        assertTrue(blockedCalled)
        assertEquals(45, (viewModel.passwordResetState.value as PasswordResetUiState.SentCooldown).secondsRemaining)
    }

    @Test
    fun `password reset state can be reset to idle`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MusicViewModel(app)

        viewModel.passwordResetState.value = PasswordResetUiState.SentCooldown(30)
        viewModel.resetPasswordResetState()
        assertEquals(PasswordResetUiState.Idle, viewModel.passwordResetState.value)
    }

    @Test
    fun `email sign in validates empty inputs`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MusicViewModel(app)

        viewModel.signInWithEmail("", "")
        assertTrue(viewModel.authUiState.value is AuthUiState.Error)
        assertEquals(
            "Please enter both email and password",
            (viewModel.authUiState.value as AuthUiState.Error).message
        )
    }

    @Test
    fun `email sign up validates short password`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MusicViewModel(app)

        viewModel.signUpWithEmail("test@example.com", "123", "User")
        assertTrue(viewModel.authUiState.value is AuthUiState.Error)
        assertEquals(
            "Password must be at least 6 characters",
            (viewModel.authUiState.value as AuthUiState.Error).message
        )
    }
}
