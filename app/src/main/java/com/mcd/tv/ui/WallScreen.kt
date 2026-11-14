package com.mcd.tv.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.mcd.tv.Nav
import com.mcd.tv.data.BabyCountdown
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Wall
import com.mcd.tv.data.Wall.Day
import com.mcd.tv.data.WallCycle
import com.mcd.tv.data.WallSpotlight
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Jarvis Wall: photo frame plus family calendar for a wall TV.
 * Left: the photo album (new photo every 30 s, slow zoom). Right: clock, weather, today, the next days, school due dates.
 * Now and then it shows the whole month instead: by default the last minute of every half hour (pref wall_cycle, set
 * on the Control page or in Settings, see WallCycle). Remote: OK switches views (and stops the automatic switch
 * for 10 minutes), LEFT / RIGHT change the photo, BACK leaves.
 * Heads-up: one minute in five, a card for a one-off event in the next 4 weeks takes the place of the agenda list
 * (WallSpotlight). It never covers the photo and never changes when the month calendar shows.
 * The screen stays on while the Wall is open (MainActivity, ScreenAwake). From 11 PM to 6 AM it dims.
 */

private const val PHOTO_MS = 30_000L
private const val FEED_MS = 5 * 60_000L
private const val PHOTO_LIST_MS = 30 * 60_000L
private const val MANUAL_HOLD_MS = 10 * 60_000L

private enum class WallView { Agenda, Month }

@Composable
fun WallScreen(nav: Nav) {
    // The screen stays on while the Wall is in the back stack: MainActivity holds it (ScreenAwake.WALL).

    var feed by remember { mutableStateOf(Wall.cachedFeed()) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var photoIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var photoIndex by remember { mutableIntStateOf(0) }
    var manualView by remember { mutableStateOf<WallView?>(null) }
    var manualAt by remember { mutableLongStateOf(0L) }
    // "photos": photos only, no calendar (set in Settings or from the Control page, live).
    val wallMode by Wall.mode.collectAsState()
    val wallCycle by Wall.cycle.collectAsState()

    // A minute tick: the day, the agenda and the automatic view switch follow it. The clock has its own faster tick.
    val minute by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000 + 50)
        }
    }

    if (Wall.configured) {
        LaunchedEffect(Unit) {
            while (true) {
                runCatching { Wall.loadFeed() }
                    .onSuccess { feed = it; feedError = null }
                    .onFailure { feedError = it.message ?: "Can't reach the wall feed" }
                delay(FEED_MS)
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                // The order is shuffled (same order all day) so the album doesn't feel repetitive.
                runCatching { Wall.loadPhotos() }.onSuccess { list ->
                    if (list != photoIds) {
                        photoIds = list
                        photos = list.shuffled(java.util.Random(Day.today().toLong()))
                        photoIndex = 0
                    }
                }
                delay(PHOTO_LIST_MS)
            }
        }
        LaunchedEffect(photos) {
            while (photos.size > 1) {
                delay(PHOTO_MS)
                photoIndex = (photoIndex + 1) % photos.size
            }
        }
    }

    // Load the next photo early so the crossfade never waits on the network.
    val context = LocalContext.current
    LaunchedEffect(photoIndex, photos) {
        if (photos.size > 1) {
            val next = photos[(photoIndex + 1) % photos.size]
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(Wall.photoUrl(next)).build())
        }
    }

    val cal = Calendar.getInstance().apply { timeInMillis = minute }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minuteOfDay = hour * 60 + cal.get(Calendar.MINUTE)
    val today = Day.ofMillis(minute)
    val spots = remember(feed, today) { feed?.let { WallSpotlight.pick(it, today) }.orEmpty() }
    val frame = WallSpotlight.frame(WallCycle.parse(wallCycle), spots.size, minuteOfDay)
    val autoView = if (frame.month) WallView.Month else WallView.Agenda
    val spot = frame.spot?.let { spots.getOrNull(it) }
    val shown = manualView?.takeIf { minute - manualAt < MANUAL_HOLD_MS } ?: autoView
    val night = hour >= 23 || hour < 6

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .hudBackground()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        manualView = if (shown == WallView.Agenda) WallView.Month else WallView.Agenda
                        manualAt = System.currentTimeMillis()
                        true
                    }
                    Key.DirectionRight -> { if (photos.isNotEmpty()) photoIndex = (photoIndex + 1) % photos.size; true }
                    Key.DirectionLeft -> { if (photos.isNotEmpty()) photoIndex = (photoIndex - 1 + photos.size) % photos.size; true }
                    else -> false
                }
            },
    ) {
        when {
            !Wall.configured -> NotSetUp()
            wallMode == "photos" -> PhotoPanel(photos.getOrNull(photoIndex), fade = false)
            shown == WallView.Month -> MonthView(feed, minute)
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.62f).fillMaxHeight()) {
                    PhotoPanel(photos.getOrNull(photoIndex))
                }
                Column(
                    Modifier.weight(0.38f).fillMaxHeight().background(McdColors.Navy.copy(alpha = 0.6f))
                        .padding(start = 20.dp, end = 22.dp, top = 18.dp, bottom = 14.dp),
                ) {
                    AgendaPanel(feed, minute, feedError, spot)
                }
            }
        }
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)))
    }
}

// ---------------- Photos ----------------

@Composable
private fun PhotoPanel(id: String?, fade: Boolean = true) {
    Crossfade(targetState = id, animationSpec = tween(1500), label = "wallPhoto") { pid ->
        if (pid == null) {
            EmptyPhotos()
        } else {
            val url = Wall.photoUrl(pid)
            // Slow zoom over the photo's 30 seconds.
            val zoom = remember(pid) { Animatable(1f) }
            LaunchedEffect(pid) { zoom.animateTo(1.06f, tween(PHOTO_MS.toInt() + 2000, easing = LinearEasing)) }
            Box(Modifier.fillMaxSize().clipToBounds()) {
                // Fill behind portrait photos: the same photo, cropped and dimmed (blurred on Android 12+).
                AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.35f)
                        .let { if (Build.VERSION.SDK_INT >= 31) it.blur(28.dp) else it },
                )
                AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom.value, scaleY = zoom.value),
                )
                // Soft edge into the calendar panel.
                if (fade) Box(
                    Modifier.fillMaxHeight().width(60.dp).align(Alignment.CenterEnd)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, McdColors.Navy.copy(alpha = 0.7f)))),
                )
            }
        }
    }
}

@Composable
private fun EmptyPhotos() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudRing(56.dp)
        Spacer(Modifier.height(14.dp))
        Text("No photos yet", style = broadcastStyle(22.sp))
        Spacer(Modifier.height(6.dp))
        Text("Scan to add photos from your phone.", color = McdColors.Muted, fontSize = 17.sp)
        Spacer(Modifier.height(16.dp))
        QrCode(Wall.uploadPage(), size = 150.dp)
    }
}

// ---------------- Agenda ----------------

@Composable
private fun ColumnScope.AgendaPanel(feed: Wall.Feed?, now: Long, error: String?, spot: WallSpotlight.Spot?) {
    WallClock()
    feed?.weather?.let { w ->
        val today = w.days.firstOrNull()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text("${w.now}°", style = hudDisplayStyle(20.sp, McdColors.White, 0.5.sp))
            Spacer(Modifier.width(10.dp))
            Text(Wall.weatherLabel(w.code), color = McdColors.White, fontSize = 17.sp)
            if (today != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "H ${today.hi}  L ${today.lo}" + (today.rain?.takeIf { it >= 30 }?.let { "  Rain $it%" } ?: ""),
                    color = McdColors.Muted, fontSize = 16.sp,
                )
            }
        }
    }
    BabyCard(feed, now)
    Spacer(Modifier.height(12.dp))

    if (feed == null) {
        Text(if (error != null) "Can't load the calendar: $error" else "Loading the calendar…", color = McdColors.Muted, fontSize = 17.sp)
        return
    }

    val today = Day.ofMillis(now)
    val upcoming = remember(feed, today, now / 60_000) { agendaRows(feed, today, now) }
    if (spot != null) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.CenterStart) {
            HeadsUpCard(feed, spot)
        }
    } else Column(Modifier.weight(1f).fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        upcoming.forEach { row ->
            when (row) {
                is AgendaRow.Header -> Text(
                    row.text, style = hudLabelStyle(11.sp, if (row.today) McdColors.Accent else McdColors.Muted),
                    modifier = Modifier.padding(top = if (row.first) 0.dp else 6.dp),
                )
                is AgendaRow.Item -> EventRow(feed, row.event, row.day)
                AgendaRow.Free -> Text("Nothing else today", color = McdColors.Muted, fontSize = 16.sp)
            }
        }
    }

    // School due dates in the next 10 days (school calendars, or titles starting with "SNHU"), so they never hide below the fold.
    val due = remember(feed, today) {
        feed.events.filter { e ->
            (feed.sources.getOrNull(e.source)?.kind == "school" || e.title.startsWith("SNHU", ignoreCase = true)) &&
                e.lastDay >= today && e.firstDay <= Day.plus(today, 10) &&
                (e.end ?: Long.MAX_VALUE) >= now
        }.take(3)
    }
    if (due.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, McdColors.Amber.copy(alpha = 0.6f), HudShapeSmall)
                .background(McdColors.Amber.copy(alpha = 0.08f), HudShapeSmall).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("DUE SOON", style = hudLabelStyle(10.sp, McdColors.Amber))
            due.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dueLabel(e, today), color = McdColors.Amber, fontSize = 15.sp, modifier = Modifier.width(74.dp), maxLines = 1)
                    Text(e.title, color = McdColors.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    Legend(feed, error)
}

// ---------------- Heads-up ----------------

/** A one-off event coming up: "In 3 weeks", the title, the dates and the place. */
@Composable
private fun HeadsUpCard(feed: Wall.Feed, spot: WallSpotlight.Spot) {
    val color = wallSourceColor(feed.sources.getOrNull(spot.event.source)?.color)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, McdColors.Accent.copy(alpha = 0.6f), HudShapeSmall)
            .background(McdColors.Accent.copy(alpha = 0.08f), HudShapeSmall),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("HEADS-UP", style = hudLabelStyle(11.sp, McdColors.Accent))
            Text(spot.countdown, style = hudDisplayStyle(30.sp, McdColors.White, 0.5.sp), maxLines = 1)
            Text(spot.title, color = McdColors.White, fontSize = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(spot.days, color = McdColors.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            spot.location?.let { Text(it, color = McdColors.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---------------- Baby countdown ----------------

private val BabyBlue = Color(0xFF6EC1FF)

/**
 * Countdown to the baby's due date: the date set on the Control page, else an all-day calendar event called
 * "Due date" or "Baby due". Hidden when there is neither, or 3 weeks after the date.
 */
@Composable
private fun BabyCard(feed: Wall.Feed?, now: Long) {
    val today = Day.ofMillis(now)
    val due = BabyCountdown.parse(Prefs.wallDue)
        ?: feed?.events?.firstOrNull { it.allDay && BabyCountdown.isDueEvent(it.title) }?.day
        ?: return
    val s = remember(today, due) { BabyCountdown.state(today, due) } ?: return
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).border(1.dp, BabyBlue.copy(alpha = 0.6f), HudShapeSmall)
            .background(BabyBlue.copy(alpha = 0.08f), HudShapeSmall).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val name = Prefs.wallBaby.trim()
        Text(name.ifEmpty { "Baby" }.uppercase(), style = hudLabelStyle(10.sp, BabyBlue))
        Text(s.headlineFor(name), style = hudDisplayStyle(22.sp, McdColors.White, 0.5.sp), maxLines = 1)
        Text(s.detail, color = McdColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).background(McdColors.Line.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(s.progress).height(4.dp).background(BabyBlue))
        }
    }
}

private sealed interface AgendaRow {
    data class Header(val text: String, val today: Boolean, val first: Boolean) : AgendaRow
    data class Item(val event: Wall.Event, val day: Int) : AgendaRow
    data object Free : AgendaRow
}

/** Today (events not yet over) and then each following day with events, at most 13 rows in all. */
private fun agendaRows(feed: Wall.Feed, today: Int, now: Long): List<AgendaRow> {
    val out = mutableListOf<AgendaRow>()
    var day = today
    for (i in 0 until 14) {
        val events = feed.events
            .filter { it.isOn(day) && (it.allDay || (it.end ?: 0) > now || day != today) }
            .sortedWith(compareBy<Wall.Event>({ !it.allDay }, { it.start ?: 0 }))
        if (events.isNotEmpty() || i == 0) {
            if (out.size >= 12) break
            out += AgendaRow.Header(dayHeader(day, today), today = i == 0, first = i == 0)
            if (events.isEmpty()) out += AgendaRow.Free
            events.forEach { if (out.size < 13) out += AgendaRow.Item(it, day) }
        }
        day = Day.plus(day, 1)
    }
    return out
}

@Composable
private fun EventRow(feed: Wall.Feed, e: Wall.Event, day: Int) {
    val src = feed.sources.getOrNull(e.source)
    val color = wallSourceColor(src?.color)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = McdColors.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(timeLabel(e, day), src?.tag?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                color = McdColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WallClock() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000 + 20)
        }
    }
    val d = Date(now)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(SimpleDateFormat("h:mm", Locale.US).format(d), style = hudDisplayStyle(46.sp, McdColors.White, 1.sp))
        Spacer(Modifier.width(6.dp))
        Text(
            SimpleDateFormat("a", Locale.US).format(d), style = hudLabelStyle(14.sp, McdColors.Accent),
            modifier = Modifier.padding(bottom = 9.dp),
        )
    }
    Text(SimpleDateFormat("EEEE  ·  MMMM d", Locale.US).format(d).uppercase(), style = hudLabelStyle(12.sp, McdColors.Muted))
}

@Composable
private fun Legend(feed: Wall.Feed, error: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        feed.sources.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(Color(s.color), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(s.tag, color = if (s.error != null) McdColors.Coral else McdColors.Muted, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (error != null) {
            Spacer(Modifier.weight(1f))
            Text("Offline", color = McdColors.Coral, fontSize = 13.sp)
        }
    }
}

// ---------------- Month ----------------

@Composable
private fun MonthView(feed: Wall.Feed?, now: Long) {
    val today = Day.ofMillis(now)
    val first = Day.of(Day.year(today), Day.month(today), 1)
    // The grid starts on the Sunday on or before the 1st.
    val gridStart = Day.plus(first, -(Day.weekday(first) - Calendar.SUNDAY))
    val daysInMonth = Day.calendar(first).getActualMaximum(Calendar.DAY_OF_MONTH)
    val weeks = (Day.weekday(first) - Calendar.SUNDAY + daysInMonth + 6) / 7

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("MMMM yyyy", Locale.US).format(Day.calendar(today).time).uppercase(),
                style = broadcastStyle(26.sp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) { WallClock() }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                Text(it, style = hudLabelStyle(11.sp, McdColors.Muted), modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).fillMaxWidth()) {
            for (w in 0 until weeks) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val day = Day.plus(gridStart, w * 7 + c)
                        MonthCell(feed, day, today, inMonth = Day.month(day) == Day.month(today), modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(feed: Wall.Feed?, day: Int, today: Int, inMonth: Boolean, modifier: Modifier) {
    val isToday = day == today
    val events = feed?.events?.filter { it.isOn(day) }?.sortedWith(compareBy<Wall.Event>({ !it.allDay }, { it.start ?: 0 })).orEmpty()
    Column(
        modifier
            .border(if (isToday) 2.dp else 0.5.dp, if (isToday) McdColors.Accent else McdColors.Line.copy(alpha = 0.5f))
            .background(if (isToday) McdColors.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            Day.dom(day).toString(),
            style = hudDisplayStyle(12.sp, if (isToday) McdColors.Accent else if (inMonth) McdColors.White else McdColors.Muted.copy(alpha = 0.4f), 0.5.sp),
        )
        val max = 3
        events.take(max).forEach { e ->
            val color = wallSourceColor(feed?.sources?.getOrNull(e.source)?.color)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Spacer(Modifier.width(3.dp))
                Text(
                    (if (e.allDay) "" else shortTime(e.start ?: 0) + " ") + e.title,
                    color = if (inMonth) McdColors.White else McdColors.Muted.copy(alpha = 0.5f),
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (events.size > max) Text("+${events.size - max} more", color = McdColors.Muted, fontSize = 10.sp)
    }
}

// ---------------- Not set up ----------------

@Composable
private fun NotSetUp() {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudRing(64.dp)
        Spacer(Modifier.height(16.dp))
        Text("JARVIS WALL", style = broadcastStyle(26.sp))
        Spacer(Modifier.height(10.dp))
        Text(
            "Not set up yet. On your phone, open the Control page and paste the wall key in the Wall section.",
            color = McdColors.Muted, fontSize = 18.sp,
        )
    }
}

// ---------------- Labels ----------------

private fun dayHeader(day: Int, today: Int): String = when (day) {
    today -> "TODAY"
    Day.plus(today, 1) -> "TOMORROW"
    else -> SimpleDateFormat("EEEE  ·  MMM d", Locale.US).format(Day.calendar(day).time).uppercase()
}

private fun timeLabel(e: Wall.Event, day: Int): String {
    if (e.allDay) return if (e.lastDay > day) "All day, through " + SimpleDateFormat("EEE", Locale.US).format(Day.calendar(e.lastDay).time) else "All day"
    val s = e.start ?: return ""
    val en = e.end ?: s
    val fmt = SimpleDateFormat("h:mm a", Locale.US)
    return when {
        e.firstDay < day -> "Until " + fmt.format(Date(en))
        en - s <= 60_000 -> fmt.format(Date(s)) // a deadline, not a meeting
        else -> fmt.format(Date(s)) + " to " + fmt.format(Date(en))
    }
}

/** "9a", "1:30p". */
private fun shortTime(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val m = c.get(Calendar.MINUTE)
    val ap = if (c.get(Calendar.AM_PM) == Calendar.AM) "a" else "p"
    return if (m == 0) "$h$ap" else "$h:${m.toString().padStart(2, '0')}$ap"
}

private fun dueLabel(e: Wall.Event, today: Int): String {
    val d = e.firstDay
    return when (d) {
        today -> "Today"
        Day.plus(today, 1) -> "Tomorrow"
        else -> SimpleDateFormat("EEE M/d", Locale.US).format(Day.calendar(d).time)
    }
}

/** A Wall source's color. No color, or the feed's default cyan, follows the active look's accent. */
private fun wallSourceColor(c: Long?): Color = if (c == null || c == 0xFF00E5FFL) McdColors.Accent else Color(c)
