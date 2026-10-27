package com.mcd.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Jarvis
import com.mcd.tv.data.JarvisAnswer
import com.mcd.tv.data.JarvisMatch

/** Example prompts shown before the first question. Selecting one fills the field and asks. */
internal val ASK_EXAMPLES = listOf(
    "Robert Downey Jr plays a genius inventor who builds a suit",
    "Space movie where they travel through a wormhole to save humanity",
    "Show about a chemistry teacher who starts making drugs",
)

/**
 * Ask Jarvis: describe a movie or show in plain words, get back best guesses as poster cards
 * with a confidence gauge and a one-line reason. [initial] (from Search or the QA intent) asks right away.
 */
@Composable
fun AskJarvisScreen(nav: Nav, initial: String = "") {
    var query by rememberSaveable { mutableStateOf(initial) }
    var asked by rememberSaveable { mutableStateOf(initial.trim()) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val hasKey = remember { Jarvis.configured }
    val field = remember { FocusRequester() }
    val setupButton = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        query = q
        if (q == asked) attempt++ else asked = q
        // A chip disappears once asked: keep focus on the field so the remote still has a home.
        runCatching { field.requestFocus() }
    }

    // Focus the field (no keyboard yet: OK opens it), or the setup button when there is no key.
    LaunchedEffect(hasKey) {
        withFrameNanos { }
        runCatching { if (hasKey) field.requestFocus() else setupButton.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().hudBackground().verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 24.dp),
    ) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudRing(44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("ASK JARVIS", style = broadcastStyle(26.sp), maxLines = 1)
                Text(
                    "Describe a movie or show. Plot, actors, a scene, anything you remember.",
                    color = McdColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!hasKey) {
            HudPanel(Modifier.fillMaxWidth()) {
                Text("JARVIS OFFLINE", style = hudLabelStyle(11.sp, McdColors.Amber))
                Text(Jarvis.NO_KEY, color = McdColors.White, fontSize = 18.sp)
                Text(
                    "Open Phone & Computer Setup, scan the code with your phone and paste the key under Keys.",
                    color = McdColors.Muted, fontSize = 14.sp,
                )
                Row(Modifier.padding(top = 4.dp)) {
                    ActionButton(
                        "Phone & Computer Setup", { nav.push(Screen.PhoneSetup) },
                        modifier = Modifier.focusRequester(setupButton), primary = true,
                    )
                }
            }
        } else {
            // ---- Question field + ASK ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(600) },
                    singleLine = true,
                    textStyle = TextStyle(color = McdColors.White, fontSize = 20.sp, fontFamily = HudText),
                    cursorBrush = SolidColor(McdColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("That movie where…", color = McdColors.Muted.copy(alpha = 0.7f), fontSize = 20.sp, maxLines = 1)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(field)
                        .background(McdColors.Card, HudShape).border(1.5.dp, McdColors.Accent, HudShape)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Spacer(Modifier.width(14.dp))
                ActionButton("Ask", { submit() }, primary = true)
            }
            Text(
                "Press OK, then the mic on the keyboard to speak. Press Search or ASK when you're done.",
                color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )

            // ---- Examples, thinking, or results ----
            if (asked.isBlank()) {
                RailHeader("Try asking")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ASK_EXAMPLES.forEach { ex -> AskChip(ex) { submit(ex) } }
                }
            } else {
                val res by rememberLoad(asked, attempt) { Jarvis.ask(asked) }
                when (val r = res) {
                    is Load.Loading -> JarvisThinking()
                    is Load.Err -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JarvisLine(r.message)
                        Row { ActionButton("Try again", { attempt++ }) }
                    }
                    is Load.Ok -> JarvisResults(r.value) { t -> nav.push(Screen.Detail(t.title.type, t.title.id)) }
                }
            }
        }
    }
}

/** "JARVIS: ..." line: the label in accent Orbitron, the text in HUD white. */
@Composable
internal fun JarvisLine(text: String, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = McdColors.Accent, fontFamily = HudDisplay, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp)) {
                append("JARVIS: ")
            }
            append(text)
        },
        color = McdColors.White, fontSize = 17.sp, modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Spinner, "ANALYZING…" and a thin scanning bar sweeping left to right. */
@Composable
internal fun JarvisThinking() {
    val sweep = rememberInfiniteTransition(label = "jarvis-scan")
    val x by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "jarvis-scan-x",
    )
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudSpinner(diameter = 28.dp)
            Spacer(Modifier.width(12.dp))
            Text("ANALYZING…", style = hudLabelStyle(14.sp, McdColors.Accent))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.width(360.dp).height(3.dp)) {
            drawRect(McdColors.Line.copy(alpha = 0.5f))
            val seg = size.width * 0.22f
            val left = (size.width + seg) * x - seg
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to McdColors.AccentBright,
                    1f to Color.Transparent,
                    startX = left,
                    endX = left + seg,
                ),
                topLeft = Offset(left.coerceAtLeast(0f), 0f),
                size = androidx.compose.ui.geometry.Size((left + seg).coerceAtMost(size.width) - left.coerceAtLeast(0f), size.height),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Cross-referencing the archives, sir…", color = McdColors.Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun JarvisResults(a: JarvisAnswer, onOpen: (JarvisMatch) -> Unit) {
    if (a.clarify.isNotBlank()) JarvisLine(a.clarify)
    if (a.matches.isEmpty()) {
        if (a.clarify.isBlank()) JarvisLine("I'm afraid that one eludes me, sir. An actor, a year or a scene would help.")
        return
    }
    RailHeader(if (a.plain) "Direct matches" else if (a.matches.size == 1) "Best match" else "Best guesses", Modifier.padding(top = 6.dp))
    LazyRow(
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(a.matches, key = { "${it.title.type}-${it.title.id}" }) { m -> JarvisMatchCard(m) { onOpen(m) } }
    }
}

/** Poster, title and year, then a confidence gauge beside Jarvis's one-line reason. */
@Composable
private fun JarvisMatchCard(m: JarvisMatch, onClick: () -> Unit) {
    val t = m.title
    Column(Modifier.width(150.dp)) {
        PosterCard(t, onClick = onClick, width = 150.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            (t.name + if (t.year.isNotBlank()) " (${t.year})" else "").uppercase(),
            style = broadcastStyle(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            val c = m.confidence.coerceIn(0, 100)
            HudGauge(
                fraction = c / 100f, valueText = "$c", label = "MATCH", diameter = 34.dp,
                color = if (c >= 50) McdColors.Accent else McdColors.Amber,
            )
            Spacer(Modifier.width(8.dp))
            Text(m.why, color = McdColors.Muted, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small chamfered example chip: hairline at rest, cyan fill with dark text when focused. */
@Composable
internal fun AskChip(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.03f else 1f; scaleX = s; scaleY = s }
            .hudGlow(focused, HudShapeSmall, McdColors.Accent)
            .clip(HudShapeSmall)
            .background(if (focused) McdColors.Accent else McdColors.Card)
            .border(1.dp, if (focused) Color.Transparent else McdColors.Line, HudShapeSmall)
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "“$text”",
            color = if (focused) McdColors.Ink else McdColors.White,
            fontFamily = HudText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}
