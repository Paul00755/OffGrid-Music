package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthUiState
import com.example.auth.PasswordResetUiState
import com.example.auth.UserAccount
import com.example.ui.viewmodel.MusicViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFlowUnitTest {

    @Test
    fun testUserAccountProperties() {
        val googleUser = UserAccount(
            uid = "test-uid-123",
            email = "user@gmail.com",
            displayName = "Test User",
            providerId = "google.com"
        )
        assertEquals("Google Account", googleUser.friendlyProviderName)
        assertEquals("T", googleUser.displayInitial)

        val emailUser = UserAccount(
            uid = "test-uid-456",
            email = "music@example.com",
            providerId = "password"
        )
        assertEquals("Email & Password", emailUser.friendlyProviderName)
        assertEquals("M", emailUser.displayInitial)
    }

    @Test
    fun testPasswordResetEmptyEmailValidation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MusicViewModel(app)

        assertEquals(PasswordResetUiState.Idle, vm.passwordResetState.value)

        vm.sendPasswordReset("   ")
        val state = vm.passwordResetState.value
        assertTrue("Expected Error state on blank email", state is PasswordResetUiState.Error)
        assertEquals("Please enter a valid email address.", (state as PasswordResetUiState.Error).message)

        vm.resetPasswordResetState()
        assertEquals(PasswordResetUiState.Idle, vm.passwordResetState.value)
    }

    @Test
    fun testAuthUiStateReset() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MusicViewModel(app)

        vm.openAuthScreen()
        assertTrue(vm.showAuthScreen.value)
        assertEquals(AuthUiState.Idle, vm.authUiState.value)

        vm.closeAuthScreen()
        assertFalse(vm.showAuthScreen.value)
    }
}
