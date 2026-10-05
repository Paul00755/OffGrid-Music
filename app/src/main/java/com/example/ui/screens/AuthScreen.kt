package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auth.AuthUiState
import com.example.auth.PasswordResetUiState
import com.example.ui.theme.*

private enum class AuthTab {
    SIGN_IN,
    CREATE_ACCOUNT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    authUiState: AuthUiState,
    passwordResetState: PasswordResetUiState = PasswordResetUiState.Idle,
    onSignInWithGoogle: (Activity) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignUpWithEmail: (String, String, String?) -> Unit,
    onForgotPassword: (String) -> Unit,
    onDismissPasswordReset: () -> Unit = {},
    onContinueWithoutSignUp: () -> Unit,
    onDismissError: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focusManager = LocalFocusManager.current

    var selectedTab by remember { mutableStateOf(AuthTab.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf("") }

    val isLoading = authUiState is AuthUiState.Loading

    BackHandler {
        onBackClick()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BentoBackground,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BentoTextPrimary
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onContinueWithoutSignUp) {
                        Text(
                            text = "Skip for now",
                            color = BentoPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // App Brand Header
            Surface(
                shape = CircleShape,
                color = BentoPrimary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, BentoPrimary.copy(alpha = 0.35f)),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "OffGrid Logo",
                        tint = BentoPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "OffGrid Music",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = BentoTextPrimary
            )

            Text(
                text = "Sign in to personalize your profile and prepare for cloud backup",
                fontSize = 13.sp,
                color = BentoTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error or Status Banner
            AnimatedVisibility(
                visible = authUiState is AuthUiState.Error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (authUiState is AuthUiState.Error) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFF5252).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = authUiState.message,
                                color = BentoTextPrimary,
                                fontSize = 12.5.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = BentoTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Google Sign-In Primary Card
            Surface(
                onClick = {
                    if (!isLoading && activity != null) {
                        focusManager.clearFocus()
                        onSignInWithGoogle(activity)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = BentoPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Connecting...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                    } else {
                        // Google "G" representation
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "G",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp,
                                    color = Color(0xFF4285F4)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = BentoBorder.copy(alpha = 0.6f)
                )
                Text(
                    text = "OR EMAIL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BentoTextSubtle,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = BentoBorder.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Tab Selector: Sign In / Create Account
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BentoCardSurface,
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    Surface(
                        onClick = {
                            selectedTab = AuthTab.SIGN_IN
                            onDismissError()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTab == AuthTab.SIGN_IN) BentoPrimary else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Sign In",
                            fontSize = 13.5.sp,
                            fontWeight = if (selectedTab == AuthTab.SIGN_IN) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == AuthTab.SIGN_IN) BentoOnPrimary else BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }

                    Surface(
                        onClick = {
                            selectedTab = AuthTab.CREATE_ACCOUNT
                            onDismissError()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTab == AuthTab.CREATE_ACCOUNT) BentoPrimary else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Create Account",
                            fontSize = 13.5.sp,
                            fontWeight = if (selectedTab == AuthTab.CREATE_ACCOUNT) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == AuthTab.CREATE_ACCOUNT) BentoOnPrimary else BentoTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Form Fields
            if (selectedTab == AuthTab.CREATE_ACCOUNT) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name (optional)") },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = BentoTextSecondary)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoBorder,
                        focusedLabelColor = BentoPrimary,
                        unfocusedContainerColor = BentoCardSurface.copy(alpha = 0.6f),
                        focusedContainerColor = BentoCardSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                leadingIcon = {
                    Icon(Icons.Default.Email, contentDescription = null, tint = BentoTextSecondary)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BentoPrimary,
                    unfocusedBorderColor = BentoBorder,
                    focusedLabelColor = BentoPrimary,
                    unfocusedContainerColor = BentoCardSurface.copy(alpha = 0.6f),
                    focusedContainerColor = BentoCardSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = BentoTextSecondary)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = BentoTextSecondary
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (selectedTab == AuthTab.CREATE_ACCOUNT) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (selectedTab == AuthTab.SIGN_IN && !isLoading) {
                            onSignInWithEmail(email, password)
                        }
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BentoPrimary,
                    unfocusedBorderColor = BentoBorder,
                    focusedLabelColor = BentoPrimary,
                    unfocusedContainerColor = BentoCardSurface.copy(alpha = 0.6f),
                    focusedContainerColor = BentoCardSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedTab == AuthTab.CREATE_ACCOUNT) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    leadingIcon = {
                        Icon(Icons.Default.LockReset, contentDescription = null, tint = BentoTextSecondary)
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (password == confirmPassword && !isLoading) {
                                onSignUpWithEmail(email, password, displayName)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BentoPrimary,
                        unfocusedBorderColor = BentoBorder,
                        focusedLabelColor = BentoPrimary,
                        unfocusedContainerColor = BentoCardSurface.copy(alpha = 0.6f),
                        focusedContainerColor = BentoCardSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selectedTab == AuthTab.SIGN_IN) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(
                        onClick = {
                            forgotPasswordEmail = email
                            showForgotPasswordDialog = true
                        }
                    ) {
                        Text(
                            text = "Forgot password?",
                            fontSize = 12.5.sp,
                            color = BentoPrimary
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Submit Button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (selectedTab == AuthTab.SIGN_IN) {
                        onSignInWithEmail(email, password)
                    } else {
                        if (password != confirmPassword) {
                            // password mismatch handled locally or via UI error
                            return@Button
                        }
                        onSignUpWithEmail(email, password, displayName)
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank() &&
                        (selectedTab == AuthTab.SIGN_IN || password == confirmPassword),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BentoPrimary,
                    contentColor = BentoOnPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = BentoOnPrimary
                    )
                } else {
                    Text(
                        text = if (selectedTab == AuthTab.SIGN_IN) "Sign In" else "Create Account",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // "Continue without sign-up" Card / Button
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BentoCardSurface.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContinueWithoutSignUp() }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF00E676).copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.OfflinePin,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Continue without sign-up",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BentoTextPrimary
                        )
                        Text(
                            text = "Listen offline immediately • 0 internet needed",
                            fontSize = 11.5.sp,
                            color = BentoTextSecondary
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = BentoTextSubtle
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Password Reset Dialog
    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showForgotPasswordDialog = false
                onDismissPasswordReset()
            },
            title = {
                Text(
                    text = "Reset Password",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val state = passwordResetState) {
                        is PasswordResetUiState.SentCooldown, is PasswordResetUiState.ReadyToResend -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF00E676).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.MarkEmailRead,
                                            contentDescription = null,
                                            tint = Color(0xFF00E676),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Password reset email sent.",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = BentoTextPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Check your inbox and spam folder.\nYou can request another email in 60 seconds.",
                                        fontSize = 12.5.sp,
                                        color = BentoTextSecondary,
                                        lineHeight = 18.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Didn't receive it?",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                        }
                        else -> {
                            Text(
                                text = "Enter your account email to receive a password reset link.",
                                fontSize = 13.sp,
                                color = BentoTextSecondary
                            )

                            OutlinedTextField(
                                value = forgotPasswordEmail,
                                onValueChange = { forgotPasswordEmail = it },
                                label = { Text("Email Address") },
                                singleLine = true,
                                enabled = state !is PasswordResetUiState.Sending,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BentoPrimary,
                                    unfocusedBorderColor = BentoBorder,
                                    focusedContainerColor = BentoCardSurface,
                                    unfocusedContainerColor = BentoCardSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (state is PasswordResetUiState.Error) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFF5252).copy(alpha = 0.12f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = Color(0xFFFF5252),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = state.message,
                                            fontSize = 12.sp,
                                            color = BentoTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val isSending = passwordResetState is PasswordResetUiState.Sending
                val isCooldown = passwordResetState is PasswordResetUiState.SentCooldown
                val isReadyToResend = passwordResetState is PasswordResetUiState.ReadyToResend

                val buttonText = when (passwordResetState) {
                    is PasswordResetUiState.SentCooldown -> "Resend in ${passwordResetState.secondsRemaining}s"
                    is PasswordResetUiState.ReadyToResend -> "Resend"
                    else -> "Send Link"
                }

                val isButtonEnabled = !isSending && !isCooldown && forgotPasswordEmail.isNotBlank()

                Button(
                    onClick = {
                        if (isButtonEnabled) {
                            onForgotPassword(forgotPasswordEmail)
                        }
                    },
                    enabled = isButtonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BentoPrimary,
                        disabledContainerColor = BentoPrimary.copy(alpha = 0.35f),
                        disabledContentColor = BentoOnPrimary.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BentoOnPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = buttonText,
                            color = if (isButtonEnabled) BentoOnPrimary else BentoOnPrimary.copy(alpha = 0.6f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showForgotPasswordDialog = false
                        onDismissPasswordReset()
                    }
                ) {
                    Text("Close", color = BentoTextSecondary)
                }
            },
            containerColor = BentoCardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
