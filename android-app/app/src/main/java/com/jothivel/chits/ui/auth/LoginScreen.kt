package com.jothivel.chits.ui.auth

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    agentViewModel: AgentLoginViewModel? = null,
    isSetupMode: Boolean,
    onLanguageToggle: () -> Unit
) {
    var loginMode by rememberSaveable { mutableStateOf("ADMIN") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = LoginGradient)
    ) {
        if (isSetupMode) {
            SetupAdminScreen(viewModel, onLanguageToggle)
        } else if (loginMode == "ADMIN" || agentViewModel == null) {
            PinLoginScreen(viewModel, onLanguageToggle, showLabourToggle = agentViewModel != null, onSwitchToLabour = { loginMode = "LABOUR" })
        } else {
            AgentPinLoginScreen(agentViewModel, onLanguageToggle, onSwitchToAdmin = { loginMode = "ADMIN" })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ADMIN / LABOUR MODE TOGGLE — shared pill switch shown above both login forms
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun RoleModeToggle(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .background(GlassWhite, RoundedCornerShape(24.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf("ADMIN" to "Admin", "LABOUR" to "Labour").forEach { (value, label) ->
            val isSelected = selected == value
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HANGING MARIGOLD GARLAND — decorative toranam banner at the top of the login screen
// (res/drawable-nodpi/marigold_garland.png, background removed from the user-supplied photo)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MarigoldGarlandHeader(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 100.dp) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Image(
            painter = painterResource(id = R.drawable.marigold_garland_left),
            contentDescription = null,
            modifier = Modifier.height(height),
            contentScale = ContentScale.FillHeight
        )
        Image(
            painter = painterResource(id = R.drawable.marigold_garland_right),
            contentDescription = null,
            modifier = Modifier.height(height),
            contentScale = ContentScale.FillHeight
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PREMIUM PIN LOGIN SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun PinLoginScreen(
    viewModel: LoginViewModel,
    onLanguageToggle: () -> Unit,
    showLabourToggle: Boolean = false,
    onSwitchToLabour: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.observeAsState(false)
    val haptic = LocalHapticFeedback.current

    // ── Shake animation on error ─────────────────────────────────────────
    var triggerShake by remember { mutableStateOf(false) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (triggerShake) 1f else 0f,
        animationSpec = if (triggerShake) {
            keyframes {
                durationMillis = 400
                0f at 0
                -18f at 50
                18f at 100
                -14f at 150
                14f at 200
                -8f at 250
                8f at 300
                -4f at 350
                0f at 400
            }
        } else {
            tween(0)
        },
        label = "shake",
        finishedListener = { triggerShake = false }
    )

    // ── Error state for PIN dots ─────────────────────────────────────────
    var isError by remember { mutableStateOf(false) }

    // ── Logo pulse animation ─────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "logoPulse")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoScale"
    )
    val logoGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoGlow"
    )

    // ── Staggered entry animations ───────────────────────────────────────
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    // Observe login errors to trigger the shake. Keyed on errorSeq (changes on every error), not on the
    // message: the same wrong-PIN text twice in a row must still clear the dots and unlock the keypad.
    val loginError by viewModel.loginError.observeAsState()
    val errorSeq by viewModel.errorSeq.observeAsState(0)
    var shownError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorSeq) {
        if (errorSeq > 0) {
            shownError = loginError ?: "Wrong PIN. Try again."
            isError = true
            triggerShake = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(600)
            pin = ""
            delay(300)
            isError = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Hanging marigold garland ──────────────────────────────────────
        MarigoldGarlandHeader()

        Spacer(modifier = Modifier.weight(0.8f))

        // ── Admin/Labour switch, centered ────────────────────────────────
        if (showLabourToggle) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RoleModeToggle(selected = "ADMIN") { if (it == "LABOUR") onSwitchToLabour() }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── App Name ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(600, delayMillis = 200)) + slideInVertically(
                initialOffsetY = { 30 },
                animationSpec = tween(600, delayMillis = 200, easing = FastOutSlowInEasing)
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(id = R.string.app_name),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "உழைப்பாளர்களுக்கு மட்டும்..",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── "Enter PIN" label ────────────────────────────────────────────
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 400))
        ) {
            // The error stays until the next digit is typed (it used to vanish after a second).
            Text(
                shownError ?: "Enter your PIN to continue",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = if (shownError != null) PinDotError.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f),
                fontWeight = if (shownError != null) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── PIN DOTS ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 500)) + scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(500, delayMillis = 500)
            )
        ) {
            Row(
                modifier = Modifier.offset(x = shakeOffset.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    PinDot(
                        isFilled = index < pin.length,
                        isError = isError,
                        index = index,
                        filledCount = pin.length
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // ── NUMBER PAD ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 600)) + slideInVertically(
                initialOffsetY = { 60 },
                animationSpec = tween(500, delayMillis = 600, easing = FastOutSlowInEasing)
            )
        ) {
            NumberPad(
                onNumberClick = { digit ->
                    if (pin.length < 4) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        shownError = null
                        pin += digit.toString()
                        if (pin.length == 4) {
                            // Auto-submit after 4th digit
                            viewModel.verifyPin(pin)
                        }
                    }
                },
                onDeleteClick = {
                    if (pin.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        pin = pin.dropLast(1)
                    }
                },
                enabled = !isLoading && pin.length < 4
            )
        }

        // ── Loading indicator ────────────────────────────────────────────
        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                color = AccentGold,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LABOUR (FIELD AGENT) LOGIN — phone number + PIN, verified against Firestore
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPinLoginScreen(
    viewModel: AgentLoginViewModel,
    onLanguageToggle: () -> Unit,
    onSwitchToAdmin: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.observeAsState(false)
    val loginError by viewModel.loginError.observeAsState()
    val errorSeq by viewModel.errorSeq.observeAsState(0)
    var shownError by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    var triggerShake by remember { mutableStateOf(false) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (triggerShake) 1f else 0f,
        animationSpec = if (triggerShake) {
            keyframes {
                durationMillis = 400
                0f at 0; -18f at 50; 18f at 100; -14f at 150; 14f at 200; -8f at 250; 8f at 300; -4f at 350; 0f at 400
            }
        } else tween(0),
        label = "agentShake",
        finishedListener = { triggerShake = false }
    )
    var isError by remember { mutableStateOf(false) }

    // Keyed on errorSeq, not the message: the same error twice in a row must still reset the PIN dots.
    LaunchedEffect(errorSeq) {
        if (errorSeq > 0) {
            shownError = loginError ?: "Login failed. Try again."
            isError = true
            triggerShake = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(600)
            pin = ""
            delay(300)
            isError = false
        }
    }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); isVisible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MarigoldGarlandHeader(height = 80.dp)

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RoleModeToggle(selected = "LABOUR") { if (it == "ADMIN") onSwitchToAdmin() }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(id = R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(3.dp))
            Text("Field Collection Login", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), letterSpacing = 0.5.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // The error stays until the next digit is typed (it used to vanish after a second).
        Text(
            shownError ?: "Enter your mobile number & PIN",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = if (shownError != null) PinDotError.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.7f),
            fontWeight = if (shownError != null) FontWeight.SemiBold else FontWeight.Normal
        )

        Spacer(modifier = Modifier.height(10.dp))

        // The field is read-only (the custom NumberPad drives it, not the system keyboard), so
        // it never gains real focus and never shows a real blinking text cursor - without this,
        // there was no visual cue at all for "this is where you're typing", unlike the PIN dots
        // below (or the Admin PIN screen) which animate as each digit lands.
        val phoneActive = phone.isNotEmpty() && phone.length < 10
        val phoneComplete = phone.length == 10
        val phoneBorderColor by animateColorAsState(
            targetValue = when {
                phoneComplete -> PinDotFilled
                phoneActive -> AccentGold.copy(alpha = 0.85f)
                else -> Color.White.copy(alpha = 0.25f)
            },
            animationSpec = tween(250),
            label = "phoneBorderColor"
        )
        val cursorTransition = rememberInfiniteTransition(label = "phoneCursor")
        val cursorAlpha by cursorTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
            label = "phoneCursorAlpha"
        )

        OutlinedTextField(
            value = phone,
            onValueChange = {},
            readOnly = true,
            label = { Text("Mobile Number", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading,
            textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                when {
                    phoneComplete -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PinDotFilled, modifier = Modifier.size(18.dp))
                    phoneActive -> Box(Modifier.width(2.dp).height(20.dp).background(AccentGold.copy(alpha = cursorAlpha), RoundedCornerShape(1.dp)))
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = phoneBorderColor,
                unfocusedBorderColor = phoneBorderColor,
                disabledBorderColor = phoneBorderColor,
                cursorColor = AccentGold,
                focusedContainerColor = GlassWhite.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.Transparent,
                focusedLabelColor = AccentGold,
                unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.offset(x = shakeOffset.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index -> PinDot(isFilled = index < pin.length, isError = isError, index = index, filledCount = pin.length) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Same keypad drives both fields, one after the other - the phone number never opens
        // the system keyboard (the field above is read-only), so there's nothing to scroll to.
        NumberPad(
            onNumberClick = { digit ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                shownError = null
                if (phone.length < 10) {
                    phone += digit.toString()
                    isError = false
                } else if (pin.length < 4) {
                    pin += digit.toString()
                    if (pin.length == 4) viewModel.login(phone, pin)
                }
            },
            onDeleteClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (pin.isNotEmpty()) {
                    pin = pin.dropLast(1)
                } else if (phone.isNotEmpty()) {
                    phone = phone.dropLast(1)
                }
            },
            enabled = !isLoading
        )

        if (phone.length in 0..9) {
            Spacer(modifier = Modifier.height(10.dp))
            Text("Enter your full 10-digit mobile number", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        } else if (pin.isEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text("Now enter your 4-digit PIN", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(10.dp))
            CircularProgressIndicator(color = AccentGold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PIN DOT — Animated filled/empty indicator
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun PinDot(isFilled: Boolean, isError: Boolean, index: Int, filledCount: Int) {
    val dotScale by animateFloatAsState(
        targetValue = if (isFilled) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dotScale"
    )
    
    val dotColor by animateColorAsState(
        targetValue = when {
            isError -> PinDotError
            isFilled -> PinDotFilled
            else -> PinDotEmpty
        },
        animationSpec = tween(200),
        label = "dotColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> PinDotError.copy(alpha = 0.8f)
            isFilled -> AccentGold.copy(alpha = 0.6f)
            index == filledCount -> Color.White.copy(alpha = 0.5f) // next dot to fill
            else -> Color.White.copy(alpha = 0.2f)
        },
        animationSpec = tween(200),
        label = "borderColor"
    )

    Box(
        modifier = Modifier
            .size(20.dp)
            .scale(dotScale)
            .clip(CircleShape)
            .background(dotColor, CircleShape)
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = CircleShape
            )
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// NUMBER PAD — Custom 3x4 grid
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun NumberPad(
    onNumberClick: (Int) -> Unit,
    onDeleteClick: () -> Unit,
    enabled: Boolean
) {
    val numbers = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
        listOf(-1, 0, -2)  // -1 = empty, -2 = delete
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        numbers.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                row.forEach { num ->
                    when (num) {
                        -1 -> {
                            // Empty spacer
                            Spacer(modifier = Modifier.size(60.dp))
                        }
                        -2 -> {
                            // Delete button
                            NumberPadButton(
                                onClick = onDeleteClick,
                                enabled = true,
                                content = {
                                    Icon(
                                        Icons.Default.Backspace,
                                        contentDescription = "Delete",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                        else -> {
                            // Number button
                            NumberPadButton(
                                onClick = { if (enabled) onNumberClick(num) },
                                enabled = enabled,
                                content = {
                                    Text(
                                        num.toString(),
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NumberPadButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "btnScale"
    )
    
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.25f else 0.1f,
        animationSpec = tween(100),
        label = "bgAlpha"
    )

    Surface(
        onClick = {
            if (enabled) {
                isPressed = true
                onClick()
            }
        },
        modifier = Modifier
            .size(60.dp)
            .scale(scale),
        shape = CircleShape,
        color = Color.White.copy(alpha = bgAlpha),
        border = null
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
    
    // Reset press state
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ADMIN SETUP SCREEN — Polished with staggered animations
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupAdminScreen(viewModel: LoginViewModel, onLanguageToggle: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    
    val isLoading by viewModel.isLoading.observeAsState(false)

    // Staggered entry
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); isVisible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Language toggle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = onLanguageToggle,
                modifier = Modifier.size(40.dp).background(GlassWhite, CircleShape)
            ) {
                Icon(Icons.Default.Language, contentDescription = "Switch Language", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logo removed per user request
        
        AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(400, delayMillis = 200))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(id = R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 1.sp)
                Text("உழைப்பாளர்களுக்கு மட்டும்..", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(400, delayMillis = 300))) {
            Text("Setup Admin Profile", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Form fields with staggered slide-up ──────────────────────────
        val fields = listOf(
            Triple("Admin Name", name) { v: String -> name = v },
            Triple(stringResource(id = R.string.mobile_number), phone) { v: String -> phone = v },
            Triple("Username", username) { v: String -> username = v }
        )

        fields.forEachIndexed { index, (label, value, onChange) ->
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 400 + index * 80)) +
                        slideInVertically(
                            initialOffsetY = { 40 },
                            animationSpec = tween(400, delayMillis = 400 + index * 80, easing = FastOutSlowInEasing)
                        )
            ) {
                SetupTextField(
                    value = value,
                    onValueChange = onChange,
                    label = label,
                    isPhone = index == 1
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // PIN field
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 640)) +
                    slideInVertically(
                        initialOffsetY = { 40 },
                        animationSpec = tween(400, delayMillis = 640, easing = FastOutSlowInEasing)
                    )
        ) {
            SetupTextField(
                value = pin,
                onValueChange = { if (it.length <= 4) pin = it },
                label = "Create 4-Digit PIN",
                isPassword = true
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        // ── Submit button with gradient ──────────────────────────────────
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 750)) +
                    slideInVertically(
                        initialOffsetY = { 30 },
                        animationSpec = tween(400, delayMillis = 750, easing = FastOutSlowInEasing)
                    )
        ) {
            Button(
                onClick = { viewModel.setupAdminProfile(name, phone, username, pin) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                enabled = !isLoading && name.isNotBlank() && phone.isNotBlank() && pin.length == 4
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaroonPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "Complete Setup",
                        color = MaroonPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Setup form text field with glass effect ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPhone: Boolean = false,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        ),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                isPhone -> KeyboardType.Phone
                isPassword -> KeyboardType.NumberPassword
                else -> KeyboardType.Text
            }
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentGold.copy(alpha = 0.8f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            cursorColor = AccentGold,
            focusedContainerColor = GlassWhite.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.Transparent,
            focusedLabelColor = AccentGold,
            unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
        )
    )
}
