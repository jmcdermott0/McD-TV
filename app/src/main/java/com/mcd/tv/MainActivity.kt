package com.mcd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.mcd.tv.data.LocalWeb
import com.mcd.tv.data.Relay
import com.mcd.tv.data.RemoteNav
import com.mcd.tv.data.PlayMeta
import kotlinx.coroutines.launch
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.ScreenAwake
import com.mcd.tv.data.Service
import com.mcd.tv.player.PlayerScreen
import com.mcd.tv.ui.AskJarvisScreen
import com.mcd.tv.ui.BrowseGridScreen
import com.mcd.tv.ui.BrowseScreen
import com.mcd.tv.ui.GenresScreen
import com.mcd.tv.ui.DetailScreen
import com.mcd.tv.ui.HomeScreen
import com.mcd.tv.ui.IntroScreen
import com.mcd.tv.ui.LibraryScreen
import com.mcd.tv.ui.LiveTvScreen
import com.mcd.tv.ui.McdTheme
import com.mcd.tv.ui.NoiseRunScreen
import com.mcd.tv.ui.NoiseScreen
import com.mcd.tv.ui.NavTab
import com.mcd.tv.ui.PhoneSetupScreen
import com.mcd.tv.ui.PersonScreen
import com.mcd.tv.ui.RdCloudScreen
import com.mcd.tv.ui.RdConnectScreen
import com.mcd.tv.ui.SearchScreen
import com.mcd.tv.ui.ServiceGridScreen
import com.mcd.tv.ui.ServicesScreen
import com.mcd.tv.ui.SettingsScreen
import com.mcd.tv.ui.SourcesScreen
import com.mcd.tv.ui.SportsScreen
import com.mcd.tv.ui.WebScreen

/** Every screen in the app. Navigation is a simple back stack of these. */
sealed interface Screen {
    data object Intro : Screen
    /** "Who's watching?" profile picker (after the intro, and from the profile chip in the top bar). */
    data object Profiles : Screen
    data object Home : Screen
    data object Search : Screen
    /** Ask Jarvis: describe a title in plain words. [initial] is asked right away when not blank. */
    data class AskJarvis(val initial: String = "") : Screen
    data object Library : Screen
    /** Catalog menu: Trending, Top Rated, New Releases, By Year, By Language, Genres, Collections, services, Sports, Noise. */
    data object Browse : Screen
    /**
     * A full poster grid with "See more" paging. kind: trending, popular, top, new, year, lang, collections, collection, history.
     * param: kind-specific (type "movie"/"tv" for popular/top, a language code, a collection id, a History shelf id…). title: page heading.
     */
    data class BrowseGrid(val kind: String, val param: String = "", val title: String = "") : Screen
    /** Browse > History: Ken Burns, Historical Dramas, the eras and More Like <a history title this profile watched>. */
    data object History : Screen
    data object Sports : Screen
    data object Live : Screen
    /** Live TV multiview: 1, 2 or 4 playlist channels at once. */
    data object Multiview : Screen
    /** Every game this week for a league, with the playlist channels showing each one. */
    data class Games(val league: com.mcd.tv.data.League = com.mcd.tv.data.League.NFL) : Screen
    data object Services : Screen
    data class ServiceGrid(val service: Service) : Screen
    data object Noise : Screen
    data object NoiseRun : Screen
    data object Genres : Screen
    data object Settings : Screen
    data object Update : Screen
    data class Taste(val profile: String) : Screen
    data object JoinHouse : Screen
    data object ConnectPhone : Screen
    /** Jarvis Wall: photo frame and family calendar for a wall TV. */
    data object Wall : Screen
    data object PhoneSetup : Screen
    data object RdConnect : Screen
    data object RdCloud : Screen
    data class Person(val id: Int) : Screen
    /** openSources = go straight to the source list once the title loads (Services catalog). */
    data class Detail(val type: String, val id: Int, val openSources: Boolean = false) : Screen
    /** autoPlay = the Play button: pick the best source and start immediately. */
    data class Sources(val meta: PlayMeta, val imdbId: String, val autoPlay: Boolean) : Screen
    data class Web(val url: String, val name: String) : Screen
    data class LiveChannel(val item: com.mcd.tv.data.LiveItem) : Screen
    /** A playlist channel: [index] into com.mcd.tv.ui.LiveSession.channels (channel up/down moves through that list). */
    data class LivePlay(val index: Int) : Screen
    /** imdbId lets a TV episode roll into the next one when it ends. */
    data class Player(
        val url: String,
        val title: String,
        val meta: PlayMeta? = null,
        val imdbId: String? = null,
        /** Extra HTTP headers some streams need (Referer, Origin, User-Agent), e.g. video found by the browser. */
        val headers: Map<String, String> = emptyMap(),
        /** com.mcd.tv.data.SourceQueue id of the source list this came from (0 = none), for "Try next source". */
        val queue: Int = 0,
        /** Index of the playing source in that list. */
        val queuePos: Int = -1,
        /** Start here (ms) instead of the saved resume position; -1 = resume as usual. */
        val startMs: Long = -1L,
        /** Opened by the quality fallback (4K to 1080p): show the notice, and do not step down again. */
        val stepped: Boolean = false,
    ) : Screen
}

/** Navigation actions handed to every screen. */
class Nav(
    val push: (Screen) -> Unit,
    val back: () -> Unit,
    val replace: (Screen) -> Unit,
    val tab: (NavTab) -> Unit,
)

class MainActivity : ComponentActivity() {
    /** Any remote key means someone is watching: resets the "Still watching?" count. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            com.mcd.tv.data.StillWatching.counter.keyPressed()
            com.mcd.tv.data.WallRecorder.lastKeyAt = System.currentTimeMillis()
        }
        return super.dispatchKeyEvent(event)
    }

    /** This activity's window, as the keep-screen-on guard sees it. */
    private val keepOnWindow = object : com.mcd.tv.data.KeepOnWindow {
        override var keepOn: Boolean
            get() = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            set(on) {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
    }

    // The keep-screen-on self-check: about once a minute while Jarvis is in front (see KeepAwakeGuard).
    private val awakeCheck = android.os.Handler(android.os.Looper.getMainLooper())
    private val awakeTick = object : Runnable {
        override fun run() {
            ScreenAwake.guard.check()
            com.mcd.tv.data.WallRecorder.tick()
            awakeCheck.postDelayed(this, ScreenAwake.CHECK_EVERY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenAwake.guard.resumed()
        com.mcd.tv.data.WallRecorder.resumed()
        awakeCheck.removeCallbacks(awakeTick)
        awakeCheck.postDelayed(awakeTick, ScreenAwake.CHECK_EVERY_MS)
    }

    override fun onPause() {
        awakeCheck.removeCallbacks(awakeTick)
        super.onPause()
    }

    override fun onStop() {
        ScreenAwake.guard.stopped(isChangingConfigurations)
        com.mcd.tv.data.WallRecorder.stopped(this, isChangingConfigurations)
        super.onStop()
    }

    // The window can be rebuilt (a display change, or Android restoring the app). The back stack starts over
    // then, so remember that the Wall was up and open it again.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(WALL_UP, ScreenAwake.holds.has(ScreenAwake.WALL))
    }

    // Screen off with the Wall up is Fire OS sleep (or the TV turning the stick off over HDMI-CEC).
    private val screenEvents = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> com.mcd.tv.data.WallRecorder.screen(false)
                android.content.Intent.ACTION_SCREEN_ON -> com.mcd.tv.data.WallRecorder.screen(true)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenEvents) }
        ScreenAwake.guard.detach(keepOnWindow)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lapses go to logcat (adb logcat -s JarvisAwake) and to Settings > About and help.
        ScreenAwake.log = { msg ->
            android.util.Log.i("JarvisAwake", msg)
            if (msg.contains("lapsed")) com.mcd.tv.data.RecentErrors.note(msg)
        }
        ScreenAwake.guard.attach(keepOnWindow)
        Prefs.init(this)
        // Why the Wall stopped last time (sleep, the screensaver, a crash, Android ending Jarvis), and whether to
        // open it again because Jarvis ended while the Wall was in front. See WallWatch.
        com.mcd.tv.data.WallRecorder.installCrashHook()
        val wallLost = com.mcd.tv.data.WallRecorder.diagnoseOnStart(this)
        // Not exported: only the system sends these.
        androidx.core.content.ContextCompat.registerReceiver(this, screenEvents, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        com.mcd.tv.data.IptvVod.init(this)
        com.mcd.tv.ui.AppImages.install(this)
        if (intent?.getBooleanExtra("qa_mode", false) == true) { LocalWeb.qaMode = true; Prefs.qaMode = true }
        // Automated QA launch: no profile picker, the last used profile is used.
        com.mcd.tv.ui.ProfileStart.skip = intent?.hasExtra("qa") == true
        // The home-network setup page (port 8642) normally runs only while Phone & Computer Setup is open
        // (PhoneSetupScreen starts and stops it). The automated QA run talks to it directly, so a launch
        // with a QA extra ("--ez qa true", or "--es screen ...") starts it here too.
        if (intent?.hasExtra("qa") == true || !intent?.getStringExtra("screen").isNullOrEmpty()) LocalWeb.start()
        Relay.start() // internet setup link (works from any network)
        // QA hook: "--es screen settings" opens a screen directly (used by the automated emulator test).
        val screenExtra = intent?.getStringExtra("screen") ?: ""
        // "--es screen ask --es ask 'that movie where…'" opens Ask Jarvis and asks right away.
        val askExtra = intent?.getStringExtra("ask") ?: ""
        val startScreen: Screen? = if (screenExtra.startsWith("detail:")) {
            // "detail:movie:603"
            screenExtra.split(":").let { p -> p.getOrNull(2)?.toIntOrNull()?.let { Screen.Detail(p[1], it) } }
        } else if (screenExtra.startsWith("grid:")) {
            // "grid:top:movie", "grid:lang:ko", "grid:collection:2344"
            screenExtra.split(":").let { p -> Screen.BrowseGrid(p.getOrElse(1) { "trending" }, p.getOrElse(2) { "" }) }
        } else if (screenExtra.startsWith("settings:")) {
            // "settings:updates": Settings open on one category (QA screenshots of each).
            com.mcd.tv.ui.SettingsLaunch.requested = com.mcd.tv.ui.SettingsCategory.fromId(screenExtra.substringAfter(":"))
            Screen.Settings
        } else if (screenExtra.startsWith("history:")) {
            // "history:kenburns", "history:era:civilwar": one History shelf's See all grid.
            val shelf = screenExtra.substringAfter(":")
            Screen.BrowseGrid("history", shelf, com.mcd.tv.ui.historyShelfLabel(shelf))
        } else if (screenExtra.startsWith("person:")) {
            screenExtra.substringAfter(":").toIntOrNull()?.let { Screen.Person(it) }
        } else when (screenExtra) {
            "settings" -> Screen.Settings
            "phone" -> Screen.PhoneSetup
            "genres" -> Screen.Genres
            "sports" -> Screen.Sports
            "profiles" -> Screen.Profiles
            "noise" -> Screen.Noise
            "library" -> Screen.Library
            "browse" -> Screen.Browse
            "history" -> Screen.History
            "live" -> Screen.Live
            "multiview" -> Screen.Multiview
            "games" -> Screen.Games(com.mcd.tv.data.League.NFL)
            "services" -> Screen.Services
            "search" -> Screen.Search
            "ask" -> Screen.AskJarvis(askExtra)
            "wall" -> Screen.Wall
            else -> if (askExtra.isNotBlank()) Screen.AskJarvis(askExtra) else null
        }
        // Automated QA only: "--ez qa_mode true --es qa_watched 'movie|The Irishman|2019'" marks a title watched,
        // so the emulator run can prove the History hub looks the same whatever a profile watched.
        val qaWatched = intent?.getStringExtra("qa_watched") ?: ""
        if (qaWatched.isNotBlank() && intent?.getBooleanExtra("qa_mode", false) == true) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { com.mcd.tv.data.HistoryHub.qaWatch(qaWatched) }
            }
        }
        // Automated QA only: "--ei qa_day 20261028" makes Picked for <name> use that day, so the run can compare two days;
        // "--es qa_list 'movie|603|The Matrix'" puts a title on the active profile's My List (Home's My List row).
        if (intent?.getBooleanExtra("qa_mode", false) == true) {
            intent?.getIntExtra("qa_day", 0)?.takeIf { it > 0 }?.let { com.mcd.tv.data.Recommender.qaDay = it }
            intent?.getStringExtra("qa_list")?.split("|")?.takeIf { it.size >= 3 }?.let { p ->
                p[1].toIntOrNull()?.let { id ->
                    com.mcd.tv.data.Library.setWatchlist(com.mcd.tv.data.Title(id, p[0], p[2], "", null, null, 0.0, ""), true)
                }
            }
        }
        // Rebuilt while the Wall was up: open the Wall again. This wins over a deep link, because a rebuilt
        // activity gets its first launch intent again, which is old news by then.
        // A rebuild gets its old launch intent again, so the Wall wins there. After Jarvis ended in front, a fresh
        // launch with its own deep link wins; a plain launch (the Fire TV home screen) opens the Wall again.
        val rebuilt = savedInstanceState?.getBoolean(WALL_UP) == true
        val reopen = rebuilt || (wallLost && startScreen == null)
        if (rebuilt) ScreenAwake.log("Wall reopened after the app was rebuilt")
        else if (reopen) ScreenAwake.log("Wall reopened after Jarvis ended while it was in front")
        setContent { McdTheme { App(if (reopen) Screen.Wall else startScreen) } }
    }

    private companion object {
        const val WALL_UP = "wall_up"
    }
}

@Composable
private fun App(startScreen: Screen? = null) {
    val stack = remember {
        when {
            startScreen != null -> mutableStateListOf<Screen>(Screen.Home, startScreen)
            // A wall TV opens straight into the Wall. BACK goes to Home.
            Prefs.wallStart -> mutableStateListOf<Screen>(Screen.Home, Screen.Wall)
            Prefs.playIntro -> mutableStateListOf<Screen>(Screen.Intro)
            !com.mcd.tv.ui.ProfileStart.skip -> mutableStateListOf<Screen>(Screen.Profiles)
            else -> mutableStateListOf<Screen>(Screen.Home)
        }
    }
    // removeAt instead of removeLast: removeLast crashes on older Android versions.
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val nav = remember {
        Nav(
            push = { stack.add(it) },
            back = { pop() },
            replace = { stack[stack.lastIndex] = it },
            tab = { t ->
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Home
                val s = when (t) {
                    NavTab.Home -> null
                    NavTab.Search -> Screen.Search
                    NavTab.Browse -> Screen.Browse
                    NavTab.Library -> Screen.Library
                    NavTab.Sports -> Screen.Sports
                    NavTab.Live -> Screen.Live
                    NavTab.Services -> Screen.Services
                    NavTab.Noise -> Screen.Noise
                    NavTab.Genres -> Screen.Genres
                    NavTab.Settings -> Screen.Settings
                }
                if (s != null) stack.add(s)
            },
        )
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Wall mode: the screen stays on for as long as the Wall is anywhere in the back stack, also while a title
    // opened from the phone plays on top of it. Held here, not in WallScreen, so no other screen can clear it.
    val wallUp = stack.contains(Screen.Wall)
    val wallCtx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(wallUp) {
        val hold = if (wallUp) ScreenAwake.holds.acquire(ScreenAwake.WALL) else null
        // A beat on each change, so a Wall closed with BACK is never taken for a lost one.
        com.mcd.tv.data.WallRecorder.beat()
        if (wallUp) com.mcd.tv.data.WallRecorder.checkSleepSettings(wallCtx)
        onDispose {
            hold?.release()
            com.mcd.tv.data.WallRecorder.beat()
        }
    }

    // A new version is out: open "Update Jarvis" once per launch, on the Home screen (never mid-movie).
    // Skipped in the automated QA run, which drives screens itself.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Checks right after start, then every 6 hours for as long as the app runs (Fire TVs keep it in memory).
    LaunchedEffect(Unit) {
        if (com.mcd.tv.data.Prefs.qaMode || com.mcd.tv.ui.ProfileStart.skip) return@LaunchedEffect
        kotlinx.coroutines.delay(8_000) // after the intro and profile picker
        while (true) {
            runCatching { com.mcd.tv.data.Updater.check(ctx) }
            kotlinx.coroutines.delay(6 * 3600_000L)
        }
    }
    val update = com.mcd.tv.data.Updater.available
    LaunchedEffect(update, stack.last()) {
        // Each new version is offered once (the next Home visit after it is found).
        if (update != null && com.mcd.tv.data.Updater.promptedFor != update.code && stack.last() == Screen.Home && !com.mcd.tv.data.Prefs.qaMode) {
            com.mcd.tv.data.Updater.promptedFor = update.code
            com.mcd.tv.data.Updater.promptShown = true
            stack.add(Screen.Update)
        }
    }
    // A new TV: offer "Join my other TVs" once, so it gets the house's settings and profiles.
    LaunchedEffect(stack.last()) {
        if (stack.last() == Screen.Home && !com.mcd.tv.ui.ProfileStart.skip && com.mcd.tv.data.HouseJoin.shouldOffer(ctx)) {
            com.mcd.tv.data.HouseJoin.markSeen()
            stack.add(Screen.JoinHouse)
        }
    }

    // "Open on TV" / "Play on TV" from the Jarvis web app. In the background this still runs,
    // so the new screen is simply there when the app comes back to the front.
    LaunchedEffect(Unit) {
        Relay.navRequests.collect { r ->
            if (stack.last() == Screen.Intro) stack[stack.lastIndex] = Screen.Home
            when (r) {
                is RemoteNav.Open -> {
                    val target = Screen.Detail(r.type, r.id)
                    if (stack.last() != target) stack.add(target)
                }
                is RemoteNav.Play -> {
                    val detail = Screen.Detail(r.meta.type, r.meta.tmdbId)
                    if (stack.last() != detail) stack.add(detail)
                    stack.add(Screen.Sources(r.meta, r.imdbId, autoPlay = true))
                }
                // Start Wall / Stop Wall from the Control page. Stop only closes the Wall; it never leaves another screen.
                is RemoteNav.Wall -> {
                    if (r.start) {
                        if (stack.last() != Screen.Wall) stack.add(Screen.Wall)
                    } else {
                        while (stack.size > 1 && stack.last() == Screen.Wall) stack.removeAt(stack.lastIndex)
                    }
                }
            }
        }
    }

    // Each back-stack entry gets its own saved state (scroll positions, typed search, picked genre…),
    // keyed by its position and screen: Detail -> Detail starts fresh, and Back restores the page as it was.
    val holder = rememberSaveableStateHolder()
    val entryKey = "${stack.lastIndex}:${stack.last()}"
    val shownKeys = remember { mutableSetOf<String>() }
    SideEffect {
        // Drop saved state for entries that left the stack (popped, replaced, or cleared by a tab switch).
        shownKeys.add(entryKey)
        val live = stack.mapIndexed { i, sc -> "$i:$sc" }.toSet()
        val gone = shownKeys.filter { it !in live }
        gone.forEach { holder.removeState(it) }
        shownKeys.removeAll(gone.toSet())
    }
    key(entryKey) {
        holder.SaveableStateProvider(entryKey) {
            ScreenContent(stack.last(), nav, isOnlyEntry = stack.size == 1)
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, nav: Nav, isOnlyEntry: Boolean) {
    when (val s = screen) {
        // "Replay intro" pushes Intro on top of Settings: go back there instead of stacking a second Home.
        Screen.Intro -> IntroScreen(onDone = {
            if (isOnlyEntry) nav.replace(if (com.mcd.tv.ui.ProfileStart.skip) Screen.Home else Screen.Profiles) else nav.back()
        })
        // Picked at start: on to Home. Picked from the top bar or Settings: back there (Home reloads for the new profile).
        Screen.Profiles -> com.mcd.tv.ui.ProfilePickerScreen(onPicked = { if (isOnlyEntry) nav.replace(Screen.Home) else nav.back() })
        Screen.Home -> HomeScreen(nav)
        Screen.Search -> SearchScreen(nav)
        is Screen.AskJarvis -> AskJarvisScreen(nav, s.initial)
        Screen.Library -> LibraryScreen(nav)
        Screen.Browse -> BrowseScreen(nav)
        is Screen.BrowseGrid -> BrowseGridScreen(nav, s.kind, s.param, s.title)
        Screen.History -> com.mcd.tv.ui.HistoryScreen(nav)
        Screen.Sports -> SportsScreen(nav)
        Screen.Live -> LiveTvScreen(nav)
        Screen.Multiview -> com.mcd.tv.ui.MultiviewScreen(nav)
        is Screen.Games -> com.mcd.tv.ui.GamesScreen(nav, s.league)
        Screen.Services -> ServicesScreen(nav)
        is Screen.ServiceGrid -> ServiceGridScreen(nav, s.service)
        Screen.Noise -> NoiseScreen(nav)
        Screen.NoiseRun -> NoiseRunScreen(nav)
        Screen.Genres -> GenresScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        Screen.Update -> com.mcd.tv.ui.UpdateScreen(nav)
        is Screen.Taste -> com.mcd.tv.ui.TasteScreen(nav, s.profile)
        Screen.JoinHouse -> com.mcd.tv.ui.JoinHouseScreen(nav)
        Screen.ConnectPhone -> com.mcd.tv.ui.ConnectPhoneScreen(nav)
        Screen.Wall -> com.mcd.tv.ui.WallScreen(nav)
        Screen.PhoneSetup -> PhoneSetupScreen(nav)
        Screen.RdConnect -> RdConnectScreen(nav)
        Screen.RdCloud -> RdCloudScreen(nav)
        is Screen.Person -> PersonScreen(nav, s.id)
        is Screen.Detail -> DetailScreen(nav, s.type, s.id, s.openSources)
        is Screen.Sources -> SourcesScreen(nav, s.meta, s.imdbId, s.autoPlay)
        is Screen.LiveChannel -> com.mcd.tv.ui.LiveChannelScreen(nav, s.item)
        is Screen.LivePlay -> com.mcd.tv.ui.LivePlayerScreen(nav, s.index)
        is Screen.Web -> WebScreen(s.url) { url, headers, title -> nav.push(Screen.Player(url, title, headers = headers)) }
        is Screen.Player -> {
            val m = s.meta
            val next: (() -> Unit)? = if (m != null && m.type == "tv" && s.imdbId != null) {
                { nav.replace(Screen.Sources(m.copy(episode = m.episode + 1), s.imdbId, autoPlay = true)) }
            } else null
            androidx.compose.runtime.key(s.url, s.queuePos) {
                // A failed stream offers "Try next source" from the list it was picked from.
                val nextSource = com.mcd.tv.ui.rememberNextSource(nav, s)
                val sourceKey = com.mcd.tv.data.SourceQueue.get(s.queue)?.getOrNull(s.queuePos)?.let { com.mcd.tv.data.SourceHealth.key(it) }
                // Quality fallback: the source's own name can say 4K; a struggling 4K file re-picks a 1080p source.
                val label = androidx.compose.runtime.remember(s) {
                    com.mcd.tv.data.SourceQueue.get(s.queue)?.getOrNull(s.queuePos)?.let { "${it.name} ${it.title}" } ?: s.title
                }
                val playerScope = androidx.compose.runtime.rememberCoroutineScope()
                val reSource: (Long) -> Unit = { pos ->
                    playerScope.launch {
                        if (nextSource?.stepDown(pos) != true) {
                            com.mcd.tv.data.QualityLog.add(
                                com.mcd.tv.data.QualityLog.Entry(
                                    System.currentTimeMillis(), s.title, "4K source", "4K source", "no_lower",
                                    com.mcd.tv.data.QualityMemory.estimate(),
                                ),
                            )
                        }
                    }
                }
                val stepDown = if (nextSource == null || s.stepped) null else reSource
                val notice = if (s.stepped) com.mcd.tv.data.QualityFallback.NOTICE else null
                val startMs = s.startMs
                if (m != null && next != null) {
                    // TV episode: "Up next" countdown card before rolling into the next episode.
                    com.mcd.tv.ui.UpNextPlayer(
                        url = s.url, title = s.title, meta = m, headers = s.headers,
                        onNext = next, onCancel = { nav.back() }, onSleep = { nav.back() },
                        nextSource = nextSource, sourceKey = sourceKey,
                        qualityLabel = label, onStepDown = stepDown, startPositionMs = startMs, startNotice = notice,
                    )
                } else {
                    PlayerScreen(
                        url = s.url, title = s.title, meta = m, onEnded = next, headers = s.headers, onSleep = { nav.back() },
                        nextSource = nextSource, onBack = { nav.back() }, sourceKey = sourceKey,
                        qualityLabel = label, onStepDown = stepDown, startPositionMs = startMs, startNotice = notice,
                    )
                }
            }
        }
    }
}
