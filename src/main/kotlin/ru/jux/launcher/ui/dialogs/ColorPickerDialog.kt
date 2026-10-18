package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ru.jux.launcher.core.tr
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxTextField
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.colorOf
import java.awt.Color as AwtColor

private class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    val rgb: Int get() = AwtColor.HSBtoRGB(hue, saturation, value) and 0xFFFFFF

    companion object {
        fun of(rgb: Int): Hsv = AwtColor.RGBtoHSB(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, null)
            .let { Hsv(it[0], it[1], it[2]) }
    }
}

private val HEX = Regex("#?([0-9a-fA-F]{6})")
private val HUES = (0..6).map { Color(AwtColor.HSBtoRGB(it / 6f, 1f, 1f)) }
private val THUMB = 18.dp

internal fun hexOf(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

@Composable
fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(Hsv.of(initial)) }
    var hex by remember { mutableStateOf(hexOf(initial)) }
    val pick = { next: Hsv ->
        hsv = next
        hex = hexOf(next.rgb)
    }

    JuxDialog(
        title = title,
        onDismiss = onDismiss,
        width = 420.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = onDismiss)
            JuxButton(tr("Готово", "Done"), style = ButtonStyle.PRIMARY, onClick = { onPick(hsv.rgb) })
        },
    ) {
        SaturationValue(hsv) { saturation, value -> pick(Hsv(hsv.hue, saturation, value)) }
        Spacer(Modifier.height(14.dp))
        HueBar(hsv.hue) { hue -> pick(Hsv(hue, hsv.saturation, hsv.value)) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(colorOf(hsv.rgb)).border(2.dp, JuxColors.Outline, CircleShape))
            JuxTextField(
                value = hex,
                onValueChange = { input ->
                    hex = input.take(7)
                    HEX.matchEntire(input.trim())?.let { hsv = Hsv.of(it.groupValues[1].toInt(16)) }
                },
                placeholder = "#RRGGBB",
                isError = HEX.matchEntire(hex.trim()) == null,
                modifier = Modifier.width(150.dp),
            )
            Text("HEX", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
        }
    }
}

@Composable
private fun SaturationValue(hsv: Hsv, onChange: (Float, Float) -> Unit) {
    val hueColor = Color(AwtColor.HSBtoRGB(hsv.hue, 1f, 1f))
    val shape = RoundedCornerShape(JuxDimens.CornerMedium)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .dragToPick { position, size ->
                onChange((position.x / size.width).coerceIn(0f, 1f), 1f - (position.y / size.height).coerceIn(0f, 1f))
            },
    ) {
        Thumb(maxWidth * hsv.saturation, maxHeight * (1f - hsv.value), colorOf(hsv.rgb))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(THUMB)
            .clip(PillShape)
            .background(Brush.horizontalGradient(HUES))
            .dragToPick { position, size -> onChange((position.x / size.width).coerceIn(0f, 0.999f)) },
    ) {
        Thumb(maxWidth * hue, THUMB / 2, Color(AwtColor.HSBtoRGB(hue, 1f, 1f)))
    }
}

@Composable
private fun Thumb(x: Dp, y: Dp, fill: Color) {
    Box(
        Modifier
            .offset(x - THUMB / 2, y - THUMB / 2)
            .size(THUMB)
            .clip(CircleShape)
            .background(Color.White)
            .padding(3.dp)
            .clip(CircleShape)
            .background(fill),
    )
}

// Жест живёт дольше одной перерисовки, поэтому зовём всегда свежий обработчик, а не тот, что был при первом показе
@Composable
private fun Modifier.dragToPick(onPick: (Offset, IntSize) -> Unit): Modifier {
    val latest by rememberUpdatedState(onPick)
    return pointerInput(Unit) { detectTapGestures { latest(it, size) } }
        .pointerInput(Unit) {
            detectDragGestures(onDragStart = { latest(it, size) }) { change, _ -> latest(change.position, size) }
        }
}
