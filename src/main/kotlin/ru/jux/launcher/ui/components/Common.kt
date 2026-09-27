package ru.jux.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.softShadow

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = JuxColors.TextMuted,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(JuxDimens.CornerLarge)
    Box(
        modifier = modifier
            .softShadow(shape)
            .background(JuxColors.Surface, shape)
            .padding(22.dp),
    ) { content() }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun LabeledRow(
    label: String,
    hint: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
        }
        control()
    }
}
