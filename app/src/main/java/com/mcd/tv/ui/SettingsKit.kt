package com.mcd.tv.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

/*
 * Building blocks for Settings. One spacing scale (Space), one type scale (SettingsType) and one row shape,
 * so every category looks the same. Rows are the only focusable things in the content: text values open a
 * dialog with the keyboard on OK, never on focus.
 */

/** The spacing scale. Use these, not loose numbers. */
object Space {
    val XS = 4.dp
    val S = 8.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 32.dp
    val XXL = 48.dp
}

/** The type scale: page title, section header, row label, row value, caption. */
object SettingsType {
    val Title = 26.sp
    val Section = 11.sp
    val Label = 19.sp
    val Value = 17.sp
    val Caption = 14.sp
}

/** What sits at the right end of a row. */
enum class RowEnd { Open, Edit, Change, Toggle, None }

/** Section header: small uppercase label with room above it. */
@Composable
fun SettingsSection(title: String, first: Boolean = false) {
    Text(
        title.uppercase(),
        style = hudLabelStyle(SettingsType.Section, McdColors.Muted),
        modifier = Modifier.padding(top = if (first) Space.S else Space.XL, bottom = Space.S, start = Space.L),
    )
}

/** Hairline between groups of rows. */
@Composable
fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.S).height(1.dp).background(McdColors.Line.copy(alpha = 0.45f)))
}

/** A short plain paragraph under a section (muted). */
@Composable
fun SettingsNote(text: String) {
    Text(
        text, color = McdColors.Muted, fontSize = SettingsType.Caption, lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = Space.L, vertical = Space.XS),
    )
}

/**
 * The one row shape. Focus: raised fill, bright outline, slight scale. [value] sits under the label; [end]
 * says what OK does (opens, edits, changes, toggles). [danger] colors a destructive row.
 */
@Composable
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    end: RowEnd = RowEnd.Open,
    toggled: Boolean = false,
    danger: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = HudShapeSmall
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.02f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (focused) McdColors.Raised else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) McdColors.AccentBright else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.L, vertical = Space.S + Space.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = when {
                    danger -> McdColors.Coral
                    focused -> McdColors.AccentBright
                    else -> McdColors.White
                },
                fontSize = SettingsType.Label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!value.isNullOrBlank()) {
                Text(value, color = McdColors.Muted, fontSize = SettingsType.Value, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Space.M))
        when (end) {
            RowEnd.Open -> Text("›", color = if (focused) McdColors.AccentBright else McdColors.Muted, fontSize = 28.sp)
            RowEnd.Edit -> EndHint("Edit", focused)
            RowEnd.Change -> EndHint("Change", focused)
            RowEnd.Toggle -> TogglePill(toggled, focused)
            RowEnd.None -> {}
        }
    }
}

@Composable
private fun EndHint(text: String, focused: Boolean) {
    Text(
        text.uppercase(),
        style = hudLabelStyle(11.sp, if (focused) McdColors.AccentBright else McdColors.Muted),
    )
}

/** On / Off switch drawn as a pill with a knob. */
@Composable
private fun TogglePill(on: Boolean, focused: Boolean) {
    val track = if (on) McdColors.Accent else McdColors.Line.copy(alpha = 0.6f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(onOff(on).uppercase(), style = hudLabelStyle(11.sp, if (focused) McdColors.AccentBright else McdColors.Muted))
        Spacer(Modifier.width(Space.S))
        Box(
            Modifier.width(44.dp).height(24.dp).clip(CircleShape).background(track).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (on) McdColors.Ink else McdColors.White))
        }
    }
}

/** A read-only line: label on the left, value on the right. Not focusable. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Space.L, vertical = Space.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = McdColors.Muted, fontSize = SettingsType.Value, modifier = Modifier.width(220.dp), maxLines = 1)
        Text(value, color = McdColors.White, fontSize = SettingsType.Value, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Large primary button (App updates). [highlight] uses the amber "update ready" color. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val base = if (highlight) McdColors.Amber else McdColors.Accent
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.04f else 1f; scaleX = s; scaleY = s }
            .hudGlow(focused, HudButtonShape, base)
            .clip(HudButtonShape)
            .background(if (focused) (if (highlight) McdColors.Amber else McdColors.AccentBright) else base.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .widthIn(min = 360.dp)
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.XL, vertical = Space.M),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = McdColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
    }
}

/** One category in the left rail. [selected]: the category shown on the right. */
@Composable
fun RailItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = HudShapeSmall
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.03f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(
                when {
                    focused -> McdColors.Raised
                    selected -> McdColors.Accent.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .border(if (focused) 2.dp else 0.dp, if (focused) McdColors.AccentBright else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(end = Space.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).background(if (selected) McdColors.Accent else Color.Transparent))
        Spacer(Modifier.width(Space.M))
        Text(
            label,
            color = when {
                focused -> McdColors.AccentBright
                selected -> McdColors.White
                else -> McdColors.Muted
            },
            fontSize = 18.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Text(
                badge.uppercase(), style = hudLabelStyle(9.sp, McdColors.Ink),
                modifier = Modifier.background(McdColors.Amber, HudShapeTiny).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ dialogs

/** Shared dialog frame: dark glass card in the middle of the screen. */
@Composable
private fun DialogCard(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(640.dp)
                .background(McdColors.Navy, HudShape)
                .border(1.dp, McdColors.Line, HudShape)
                .padding(Space.XL),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Text(title, style = broadcastStyle(22.sp))
            content()
        }
    }
}

/**
 * Edits one text value. The keyboard opens as the dialog opens (the user pressed OK on an Edit row).
 * Done saves. Back closes the keyboard and the dialog in one press, without saving.
 */
@Composable
fun TextEditDialog(title: String, help: String, initial: String, maxLength: Int, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    DialogCard(title, onDismiss) {
        AndroidView(
            factory = { ctx -> ImeEditText(ctx, initial, maxLength) },
            update = { v -> v.onDone = onDone; v.onBack = onDismiss },
            modifier = Modifier.fillMaxWidth().height(64.dp).background(McdColors.Card, HudShapeSmall)
                .border(2.dp, McdColors.AccentBright, HudShapeSmall),
        )
        Text(help, color = McdColors.Muted, fontSize = SettingsType.Caption)
    }
}

/**
 * A plain EditText, because it can see BACK before the keyboard does (onKeyPreIme). Compose's text field
 * cannot, so one BACK would only hide the keyboard and leave the dialog open.
 */
private class ImeEditText(ctx: Context, initial: String, maxLength: Int) : android.widget.EditText(ctx) {
    var onDone: (String) -> Unit = {}
    var onBack: () -> Unit = {}
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        setText(initial)
        setSelection(text.length)
        setSingleLine(true)
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(McdColors.White.toArgb())
        setHintTextColor(McdColors.Muted.toArgb())
        textSize = 22f
        background = null
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
        setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (action == EditorInfo.IME_ACTION_DONE || enter) { finish(); onDone(text.toString()); true } else false
        }
        // Open the keyboard once the dialog window has focus.
        postDelayed({ requestFocus(); imm.showSoftInput(this, 0) }, 150)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { finish(); onBack() }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun finish() { imm.hideSoftInputFromWindow(windowToken, 0) }
}

/** Pick one of [options]. The current one is marked and focused first. OK picks and closes. */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    DialogCard(title, onDismiss) {
        // Scrolls for long lists (every team in a league); the focused row stays in view.
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            options.forEachIndexed { i, o ->
                SettingsRow(
                    label = o, onClick = { onPick(i) },
                    modifier = if (i == selected.coerceIn(0, options.lastIndex)) Modifier.focusRequester(first) else Modifier,
                    end = RowEnd.None, value = if (i == selected) "Current" else null,
                )
            }
        }
    }
}

/** Confirms a destructive action. Cancel has focus first, so a stray OK does nothing. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
    DialogCard(title, onDismiss) {
        Text(message, color = McdColors.White, fontSize = SettingsType.Value)
        Spacer(Modifier.height(Space.S))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            ActionButton("Cancel", onDismiss, Modifier.focusRequester(cancel), primary = true)
            ActionButton(confirm, { onConfirm(); onDismiss() })
        }
    }
}
