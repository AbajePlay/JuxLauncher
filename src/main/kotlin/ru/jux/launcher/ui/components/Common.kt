package ru.jux.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

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
    Box(
        modifier = modifier
            .background(JuxColors.Surface, RoundedCornerShape(JuxDimens.CornerLarge))
            .border(1.dp, JuxColors.Outline, RoundedCornerShape(JuxDimens.CornerLarge))
            .padding(JuxDimens.Gutter),
    ) { content() }
}

@Composable
fun Banner(
    message: String?,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = message != null) {
        val accent = if (isError) JuxColors.Danger else JuxColors.Accent
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(JuxDimens.CornerMedium))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(JuxDimens.CornerMedium))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.Text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(JuxDimens.CornerSmall))
            .padding(horizontal = 7.dp, vertical = 3.dp),
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
