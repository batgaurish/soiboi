package app.soiboi.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi

/** A labelled text field. The label stays visible above the field (never a placeholder doing a label's job); the hint says what goes in. */
@Composable
fun SField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, hint: String = "", secret: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, error: String? = null, leading: Glyph? = null, onSubmit: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text, requestFocus: Boolean = false,
) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(10)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val fr = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { fr.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, style = Soiboi.type.label, color = c.soft)
        Row(
            Modifier.fillMaxWidth().background(c.raised, shape).border(if (focused) 2.5.dp else 1.5.dp, if (error != null) c.danger else if (focused) c.accent else c.rule, shape).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) GlyphIcon(leading, size = 20.dp, tint = c.muted)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && hint.isNotEmpty()) Txt(hint, style = Soiboi.type.body, color = c.muted, maxLines = 1)
                BasicTextField(
                    value, onValueChange, Modifier.fillMaxWidth().focusRequester(fr).semantics { contentDescription = label },
                    textStyle = Soiboi.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), singleLine = singleLine, minLines = minLines,
                    interactionSource = src, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = if (onSubmit != null) ImeAction.Go else ImeAction.Default),
                    keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }),
                )
            }
        }
        if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Warning, size = 14.dp, tint = c.danger); Txt(error, style = Soiboi.type.caption, color = c.danger)
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val c = Soiboi.colors
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(if (Soiboi.reduceMotion) 0 else 140), label = "knob")
    Row(modifier.fillMaxWidth().press({ onChange(!checked) }, label, Role.Switch, shape = Soiboi.look.shape(10)).padding(vertical = 8.dp, horizontal = 4.dp).semantics { stateDescription = if (checked) "On" else "Off" }, horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(label, style = Soiboi.type.bodyStrong); if (detail != null) Txt(detail, style = Soiboi.type.caption, color = c.soft) }
        Box(Modifier.width(46.dp).height(26.dp).background(if (checked) c.accent else c.sunk, CircleShape).border(1.5.dp, if (checked) c.accent else c.muted, CircleShape).padding(3.dp)) {
            Box(Modifier.offset(x).size(18.dp).background(if (checked) c.onAccent else c.soft, CircleShape))
        }
    }
}

/** One of a few exclusive choices, laid out as a row of chips. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s)) { options.forEach { (v, l) -> Chip(l, v == selected, { onSelect(v) }) } }
}

/**
 * The selection control that replaces a native select: a button that announces the current value, opens a list
 * in a dialog, and works from the keyboard (arrows move, Enter picks, Escape closes).
 */
@Composable
fun <T> Listbox(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Choose") {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(10)
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: placeholder
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, style = Soiboi.type.label, color = c.soft)
        Row(
            Modifier.fillMaxWidth().press({ open = true }, "$label, $current", role = Role.DropdownList, shape = shape).background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(current, Modifier.weight(1f), style = Soiboi.type.body, color = if (selected == null) c.muted else c.ink, maxLines = 1)
            GlyphIcon(Glyph.ChevronDown, size = 18.dp, tint = c.soft)
        }
    }
    if (open) SDialog(label, onDismiss = { open = false }) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(options) { (v, l) ->
                Row(Modifier.fillMaxWidth().press({ onSelect(v); open = false }, l, Role.RadioButton, shape = Soiboi.look.shape(8)).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(l, Modifier.weight(1f), style = if (v == selected) Soiboi.type.bodyStrong else Soiboi.type.body)
                    if (v == selected) GlyphIcon(Glyph.Check, size = 18.dp, tint = c.accent)
                }
            }
        }
    }
}

/** A modal that closes on Escape and on a tap outside, with a title and a close button. */
@Composable
fun SDialog(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, width: Dp = 460.dp, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(20)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier.widthIn(max = width).fillMaxWidth(0.94f).background(c.paper, shape).border(1.5.dp, c.ink, shape).padding(Space.l)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, Modifier.weight(1f), style = Soiboi.type.headline, maxLines = 2)
                SIconButton(Glyph.Close, "Close", onDismiss, size = 40.dp, iconSize = 18.dp)
            }
            content()
            if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/** Confirms a destructive action. The confirm button restates the action and object, never "OK". */
@Composable
fun ConfirmDialog(title: String, detail: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SDialog(title, onDismiss, actions = {
        SButton("Cancel", onDismiss, tone = Tone.Ghost)
        SButton(confirmLabel, { onConfirm(); onDismiss() }, tone = Tone.Danger)
    }) { Txt(detail, style = Soiboi.type.body, color = Soiboi.colors.soft) }
}

/** A draggable value bar for seeking and volume: arrow keys step it, and it announces its value. */
@Composable
fun SSlider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onChangeFinished: (() -> Unit)? = null, label: String, step: Float = 0.05f,
    height: Dp = 6.dp, valueText: String? = null,
) {
    val c = Soiboi.colors
    val v = value.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.height(28.dp).semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f); if (valueText != null) stateDescription = valueText; setProgress { onChange(it.coerceIn(0f, 1f)); true } }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionRight, Key.DirectionUp -> { onChange((v + step).coerceAtMost(1f)); onChangeFinished?.invoke(); true }
                    Key.DirectionLeft, Key.DirectionDown -> { onChange((v - step).coerceAtLeast(0f)); onChangeFinished?.invoke(); true }
                    else -> false
                }
            }.focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = maxWidth
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / wPx).coerceIn(0f, 1f)); onChangeFinished?.invoke() } }
                .pointerInput(Unit) { detectDragGestures(onDragEnd = { onChangeFinished?.invoke() }) { change, _ -> change.consume(); onChange((change.position.x / wPx).coerceIn(0f, 1f)) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.rule))
            Box(Modifier.fillMaxWidth(v).height(height).clip(CircleShape).background(c.ink))
            Box(Modifier.offset(x = (trackWidth * v) - 8.dp).size(16.dp).background(c.accent, CircleShape).border(2.dp, c.paper, CircleShape))
        }
    }
}
