package com.jothivel.chits.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.MaroonSurfaceLight
import com.jothivel.chits.ui.theme.TextGray

@Composable
fun PremiumInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    prefixContent: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    colors: TextFieldColors? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val elevation by animateDpAsState(if (focused) 2.dp else 0.dp, label = "premiumInputElevation")
    val iconColor by animateColorAsState(if (focused) MaroonPrimary else TextGray, label = "premiumInputIcon")
    val iconBackground by animateColorAsState(if (focused) MaroonSurfaceLight else Color(0xFFF0F1F3), label = "premiumInputIconBg")
    val shape = RoundedCornerShape(12.dp)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.shadow(elevation, shape, clip = false, ambientColor = MaroonPrimary.copy(alpha = .08f), spotColor = MaroonPrimary.copy(alpha = .10f)).defaultMinSize(minHeight = 50.dp),
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        label = { Text(label, fontSize = 10.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal) },
        placeholder = placeholder?.let { text -> { Text(text, fontSize = 11.sp, color = TextGray.copy(alpha = .75f)) } },
        leadingIcon = leadingIcon?.let { icon ->
            { Box(Modifier.size(31.dp).background(iconBackground, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconColor, modifier = Modifier.size(17.dp)) } }
        },
        trailingIcon = trailingContent,
        prefix = prefixContent,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interactionSource,
        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        shape = shape,
        colors = colors ?: OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaroonPrimary,
            unfocusedBorderColor = Color(0xFFD7D4D5),
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color(0xFFFFFEFD),
            cursorColor = MaroonPrimary,
            focusedLabelColor = MaroonPrimary,
            unfocusedLabelColor = TextGray,
            selectionColors = androidx.compose.foundation.text.selection.TextSelectionColors(MaroonPrimary, MaroonSurfaceLight)
        )
    )
}

/**
 * Same visual styling as the normal (enabled) field, just applied to enabled=false - for
 * dropdown/picker triggers that must render like a regular text field (not visibly "disabled")
 * while a wrapping clickable intercepts the tap instead of the keyboard.
 */
@Composable
fun premiumInputFieldTriggerColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    disabledBorderColor = Color(0xFFD7D4D5),
    disabledContainerColor = Color(0xFFFFFEFD),
    disabledTextColor = Color(0xFF1A1A1A),
    disabledLabelColor = TextGray,
    disabledPlaceholderColor = TextGray.copy(alpha = .75f),
    disabledTrailingIconColor = TextGray
)
