package ru.jux.launcher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.softShadow

@Composable
fun JuxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    clearable: Boolean = false,
    isError: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(
        when {
            isError -> JuxColors.Danger.copy(alpha = 0.7f)
            focused -> JuxColors.Accent.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "fieldOutline",
    )
    val shape = PillShape

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = JuxColors.Text),
        cursorBrush = SolidColor(JuxColors.Accent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .height(46.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocusChange(it.isFocused) },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(JuxColors.SurfaceHigh, shape)
                    .border(1.dp, outline, shape)
                    .padding(start = 18.dp, end = if (clearable) 8.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JuxColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (clearable && value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Очистить", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) = JuxTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = placeholder,
    modifier = modifier,
    leadingIcon = Icons.Default.Search,
    clearable = true,
    focusRequester = focusRequester,
    onFocusChange = onFocusChange,
)

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> JuxColors.AccentSoft
            hovered && enabled -> JuxColors.Outline
            else -> JuxColors.SurfaceHigh
        },
        animationSpec = tween(150),
        label = "chipBackground",
    )
    val content = when {
        !enabled -> JuxColors.TextMuted.copy(alpha = 0.5f)
        selected -> JuxColors.Accent
        else -> JuxColors.Text
    }

    Row(
        modifier
            .height(38.dp)
            .clip(PillShape)
            .background(background)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

@Composable
fun JuxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = when (style) {
        ButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = JuxColors.Accent,
            contentColor = JuxColors.OnAccent,
            disabledContainerColor = JuxColors.SurfaceHigh,
            disabledContentColor = JuxColors.TextMuted,
        )
        ButtonStyle.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = JuxColors.SurfaceHigh,
            contentColor = JuxColors.Text,
            disabledContainerColor = JuxColors.SurfaceHigh,
            disabledContentColor = JuxColors.TextMuted.copy(alpha = 0.5f),
        )
        ButtonStyle.DANGER -> ButtonDefaults.buttonColors(
            containerColor = JuxColors.Danger.copy(alpha = 0.14f),
            contentColor = JuxColors.Danger,
            disabledContainerColor = JuxColors.SurfaceHigh,
            disabledContentColor = JuxColors.TextMuted.copy(alpha = 0.5f),
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, animationSpec = tween(120), label = "buttonPress")
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = colors,
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.height(44.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun JuxSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = JuxColors.OnAccent,
            checkedTrackColor = JuxColors.Accent,
            uncheckedThumbColor = JuxColors.TextMuted,
            uncheckedTrackColor = JuxColors.SurfaceHigh,
            uncheckedBorderColor = JuxColors.Outline,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTooltip(text: String?, content: @Composable () -> Unit) {
    if (text == null) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.Text,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .softShadow(RoundedCornerShape(JuxDimens.CornerSmall), 12.dp)
                    .background(JuxColors.SurfaceHigh, RoundedCornerShape(JuxDimens.CornerSmall))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    ) { content() }
}

@Composable
fun JuxDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp),
        shape = RoundedCornerShape(JuxDimens.CornerCard),
        containerColor = JuxColors.SurfaceHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, JuxColors.Outline.copy(alpha = 0.6f)),
        shadowElevation = 12.dp,
        content = content,
    )
}

@Composable
fun JuxMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val color = if (danger) JuxColors.Danger else JuxColors.Text
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { vector -> { Icon(vector, null, modifier = Modifier.size(18.dp)) } },
        colors = MenuDefaults.itemColors(
            textColor = color,
            leadingIconColor = if (danger) JuxColors.Danger else JuxColors.TextMuted,
            disabledTextColor = JuxColors.TextMuted.copy(alpha = 0.5f),
            disabledLeadingIconColor = JuxColors.TextMuted.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(42.dp),
    )
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(PillShape)
            .background(JuxColors.Background)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(PillShape)
                .background(JuxColors.Accent)
        )
    }
}

@Composable
fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(JuxColors.Outline)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContextMenuBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onOpenChange: (Boolean) -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    val close = { open = false }
    ReportOpen(open, onOpenChange)

    Box(
        modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (enabled && event.buttons.isSecondaryPressed) {
                val at = event.changes.first().position
                anchor = IntOffset(at.x.toInt(), at.y.toInt())
                open = true
            }
        }
    ) {
        content()
        Box(Modifier.offset { anchor }.size(1.dp)) {
            JuxDropdownMenu(expanded = open, onDismissRequest = close) { menu(close) }
        }
    }
}

@Composable
fun ReportOpen(open: Boolean, onOpenChange: (Boolean) -> Unit) {
    if (!open) return
    DisposableEffect(Unit) {
        onOpenChange(true)
        onDispose { onOpenChange(false) }
    }
}

@Composable
fun JuxDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(JuxDimens.CornerLarge)
        Column(
            Modifier
                .padding(vertical = JuxDimens.Gutter)
                .width(width)
                .softShadow(shape, 32.dp)
                .clip(shape)
                .background(JuxColors.Surface)
                .padding(26.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = JuxColors.Text)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = JuxColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Закрыть", tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
            if (actions != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
