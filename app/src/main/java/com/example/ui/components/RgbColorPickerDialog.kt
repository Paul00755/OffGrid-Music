package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.util.HapticUtils
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val QUICK_COLOR_SWATCHES = listOf(
    Color(0xFF00E5FF), // Electric Cyan
    Color(0xFFA78BFA), // Soft Violet
    Color(0xFFFF4D8D), // Neon Rose
    Color(0xFF10B981), // Emerald
    Color(0xFFFF8A00), // Sunset Amber
    Color(0xFFFACC15), // Solar Gold
    Color(0xFF3B82F6), // Cobalt Blue
    Color(0xFFF43F5E)  // Crimson Coral
)

@Composable
fun RgbColorPickerDialog(
    initialColorArgb: Int,
    onReset: () -> Unit,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val initialColor = remember(initialColorArgb) {
        if (initialColorArgb != 0) Color(initialColorArgb) else Color(0xFF00E5FF)
    }
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toArgb(), it) }
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.18f)) }

    val pickedColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }
    val redInt = (pickedColor.red * 255f).roundToInt().coerceIn(0, 255)
    val greenInt = (pickedColor.green * 255f).roundToInt().coerceIn(0, 255)
    val blueInt = (pickedColor.blue * 255f).roundToInt().coerceIn(0, 255)

    var hexInput by remember {
        mutableStateOf(String.format(Locale.US, "%02X%02X%02X", redInt, greenInt, blueInt))
    }
    var hexEditing by remember { mutableStateOf(false) }

    LaunchedEffect(redInt, greenInt, blueInt) {
        if (!hexEditing) {
            hexInput = String.format(Locale.US, "%02X%02X%02X", redInt, greenInt, blueInt)
        }
    }

    fun updateFromRgb(r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    fun updateFromColor(c: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    val isPickedLight = pickedColor.luminance() > 0.48f
    val onPickedColor = if (isPickedLight) Color(0xFF0A0C12) else Color.White

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = BentoCardSurface,
                tonalElevation = 8.dp,
                shadowElevation = 18.dp,
                border = BorderStroke(1.dp, pickedColor.copy(alpha = 0.42f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .testTag("rgb_color_picker_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(pickedColor)
                                    .border(2.dp, Color.White.copy(alpha = 0.78f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = onPickedColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Custom Accent Color",
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextPrimary
                                )
                                Text(
                                    text = "Touch wheel, enter hex, or tune RGB",
                                    fontSize = 11.5.sp,
                                    color = BentoTextSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Color Picker",
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Interactive HSV Color Wheel
                    Box(
                        modifier = Modifier
                            .size(192.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val wheelSweepColors = remember {
                            listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red
                            )
                        }
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        hexEditing = false
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val radius = minOf(cx, cy).coerceAtLeast(1f)
                                        val dx = offset.x - cx
                                        val dy = offset.y - cy
                                        val dist = hypot(dx, dy)
                                        val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                                        hue = angleDeg
                                        saturation = (dist / radius).coerceIn(0f, 1f)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, _ ->
                                        change.consume()
                                        hexEditing = false
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val radius = minOf(cx, cy).coerceAtLeast(1f)
                                        val dx = change.position.x - cx
                                        val dy = change.position.y - cy
                                        val dist = hypot(dx, dy)
                                        val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                                        hue = angleDeg
                                        saturation = (dist / radius).coerceIn(0f, 1f)
                                    }
                                }
                                .testTag("rgb_color_wheel_canvas")
                        ) {
                            val radius = minOf(size.width, size.height) / 2f
                            val center = Offset(size.width / 2f, size.height / 2f)

                            // Hue spectrum sweep
                            drawCircle(
                                brush = Brush.sweepGradient(colors = wheelSweepColors, center = center),
                                radius = radius,
                                center = center
                            )
                            // Center white saturation wash
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                                    center = center,
                                    radius = radius
                                ),
                                radius = radius,
                                center = center
                            )
                            // Brightness darkening overlay
                            val darkAlpha = (1f - value).coerceIn(0f, 0.85f)
                            if (darkAlpha > 0.01f) {
                                drawCircle(
                                    color = Color.Black.copy(alpha = darkAlpha),
                                    radius = radius,
                                    center = center
                                )
                            }
                            // Subtle outer crisp ring
                            drawCircle(
                                color = Color.White.copy(alpha = 0.28f),
                                radius = radius - 1.dp.toPx(),
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx())
                            )

                            // Selector thumb
                            val radAngle = Math.toRadians(hue.toDouble())
                            val thumbX = center.x + (cos(radAngle) * saturation * (radius - 10.dp.toPx())).toFloat()
                            val thumbY = center.y + (sin(radAngle) * saturation * (radius - 10.dp.toPx())).toFloat()
                            drawCircle(
                                color = pickedColor,
                                radius = 10.dp.toPx(),
                                center = Offset(thumbX, thumbY)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 10.dp.toPx(),
                                center = Offset(thumbX, thumbY),
                                style = Stroke(width = 2.6.dp.toPx())
                            )
                        }
                    }

                    // Brightness Slider + Hex Code Row
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Brightness",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextSecondary
                            )
                            Text(
                                text = "${(value * 100f).roundToInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary
                            )
                        }
                        Slider(
                            value = value,
                            onValueChange = {
                                hexEditing = false
                                value = it
                            },
                            valueRange = 0.15f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = pickedColor,
                                activeTrackColor = pickedColor,
                                inactiveTrackColor = BentoBorder.copy(alpha = 0.45f)
                            )
                        )
                    }

                    // Hex Input + Quick-Pick Swatches
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { raw ->
                                hexEditing = true
                                val cleaned = raw.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                                hexInput = cleaned
                                if (cleaned.length == 6) {
                                    try {
                                        val parsed = android.graphics.Color.parseColor("#$cleaned")
                                        updateFromColor(Color(parsed))
                                    } catch (_: Exception) {}
                                }
                            },
                            prefix = {
                                Text(
                                    text = "#",
                                    fontWeight = FontWeight.Bold,
                                    color = BentoTextSecondary
                                )
                            },
                            singleLine = true,
                            label = { Text("Hex Code", fontSize = 11.sp) },
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = BentoTextPrimary
                            ),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = pickedColor,
                                unfocusedBorderColor = BentoBorder.copy(alpha = 0.55f),
                                focusedLabelColor = pickedColor,
                                unfocusedLabelColor = BentoTextSecondary,
                                cursorColor = pickedColor
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("rgb_hex_input")
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = pickedColor,
                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .height(54.dp)
                                .width(78.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "LIVE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
                                    color = onPickedColor
                                )
                            }
                        }
                    }

                    // Quick-Pick Swatches
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QUICK_COLOR_SWATCHES.forEach { swatch ->
                            val isSelected = swatch.toArgb() == pickedColor.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.22f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        HapticUtils.performClickHaptic(context)
                                        hexEditing = false
                                        updateFromColor(swatch)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected Swatch",
                                        tint = if (swatch.luminance() > 0.48f) Color.Black else Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Precision RGB Sliders inside subtle grouped surface
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = BentoCardPurple.copy(alpha = 0.50f),
                        border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            RgbChannelSliderRow(
                                label = "R",
                                valueInt = redInt,
                                channelColor = Color(0xFFFF5252),
                                onValueChange = {
                                    hexEditing = false
                                    updateFromRgb(it, greenInt, blueInt)
                                }
                            )
                            RgbChannelSliderRow(
                                label = "G",
                                valueInt = greenInt,
                                channelColor = Color(0xFF00E676),
                                onValueChange = {
                                    hexEditing = false
                                    updateFromRgb(redInt, it, blueInt)
                                }
                            )
                            RgbChannelSliderRow(
                                label = "B",
                                valueInt = blueInt,
                                channelColor = Color(0xFF3B82F6),
                                onValueChange = {
                                    hexEditing = false
                                    updateFromRgb(redInt, greenInt, it)
                                }
                            )
                        }
                    }

                    // Pill Footer Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onReset()
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, BentoBorder.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("rgb_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                tint = BentoTextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Reset",
                                fontWeight = FontWeight.SemiBold,
                                color = BentoTextPrimary
                            )
                        }
                        Button(
                            onClick = {
                                HapticUtils.performClickHaptic(context)
                                onApply(pickedColor.toArgb())
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = pickedColor,
                                contentColor = onPickedColor
                            ),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(46.dp)
                                .testTag("rgb_apply_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Apply Color",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RgbChannelSliderRow(
    label: String,
    valueInt: Int,
    channelColor: Color,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = channelColor,
            modifier = Modifier.width(14.dp)
        )
        Slider(
            value = valueInt.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = channelColor,
                activeTrackColor = channelColor,
                inactiveTrackColor = channelColor.copy(alpha = 0.20f)
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valueInt.toString().padStart(3, ' '),
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = BentoTextPrimary,
            modifier = Modifier.width(30.dp)
        )
    }
}
