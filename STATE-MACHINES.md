# State machines in Jarvis

Every flow in Jarvis that has phases (loading, retrying, waiting, failed and so on) is listed here. For each one, this file gives the code that owns it, its states, its tests, and what is still loose. Read this before you change any flow that has states. When you add a flow like that, add its row.

Audit: November 9, 2026. The TV player, TV data and sync, TV screens and the web app were read in full.

## The house rule

1. **Keep one value for the state.** Do not use several booleans or nullable variables that must agree. Use an `enum`, a `sealed` type, or, on the web, a `{ status, ... }` object that you replace whole and never change in place.
2. **Put the transitions in one place.** Use a pure function or class in `data/` (TV) or a pure module function (web) that takes an event and returns the next state or an action. The screen only carries out what that code decides.
3. **Make one way in for terminal states.** A failure goes through a single function that also cancels timers and jobs. An example is `PlayerScreen.fail()`.
4. **Check async answers against the current request.** An answer to an older request must be dropped. Use a generation counter, a request key or a captured token, as `connGen`, the `RDL` poll's `mine` and the `LV` load key do.
5. **Test it.** On the TV, a JUnit test of the pure part. On the web, a vector or `qa.mjs` step that drives the real functions with slow stand-ins. The best example to copy is `PlaybackRescue` with `PlaybackRescueTest`.

Good examples already in the code:

- TV: `PlaybackRescue`, `PlayNextTrigger`, `NextEpisode.Result`, `RdLimiter` and `RdAddGate` (clock injected), `KeepAwake`, `WallWatch`, `Load<T>` with `rememberLoad`, and the sealed `Screen`, `RemoteNav` and `SettingsDialog`.
- Web: `LV`'s `st.status` (`idle|none|loading|ok|error`, replaced whole, key-guarded), `S.status` with `setStatus()`, the `QF.monitor` vectors shared with the TV, and the `rdSlot` and `rdAddGate` promise queues.

## Registry

Status values:

- **explicit**: one state value with a pure transition, and tested.
- **fixed 11-09**: the bug is fixed and the flow is guarded, but the states are still not one type.
- **open**: listed in BACKLOG.md.

### TV player

| Machine | Owner | States | Status | Tests |
|---|---|---|---|---|
| Player phase | `player/PlayerScreen.kt` `fail()` | Playing, Reconnecting, SwitchingSource, Restarting, Failed (terminal) | fixed 11-09: every failure goes through `fail()`, which stops the player and cancels `rescueJob` and `retryJob`. Before, the live "keeps freezing" panel played on behind itself, and a reconnect job could reload a source behind an error. | glue untested; `PlaybackRescueTest` covers the decider |
| Rescue decider | `data/PlaybackRescue.kt` | RECONNECT, FROM_START, NEXT_SOURCE, SKIP_AHEAD, GIVE_UP | explicit | `PlaybackRescueTest` |
| Quality step-down | `PlayerScreen.onStruggle`, `QualityTele` | NATIVE, CAPPED, SWITCH_ASKED, LOCKED | fixed 11-09: LOCKED no longer steps down again, so a 1080p stall no longer logs a "4K" step and biases `QualityMemory` | `QualityFallbackTest`, `QualityVectorsTest` (StallWatch) |
| Sleep timer | `data/SleepTimer.kt`, `PlayerScreen` `AppSleep` | Off, At(minutes, deadline), EndOfVideo | explicit (11-10): one app-wide timer, so it survives a source or episode change; it drops if no player runs for 3 minutes. EndOfVideo blocks the early Play next card. | `SleepTimerTest` |
| Play Next card | `ui/UpNext.kt`, `data/PlayNext.kt` `PlayNextTrigger` | Hidden, Showing, Held, Dismissed, then the end card | fixed 11-09: `hold()` puts back a SHOW the screen could not use (next episode not known yet, a failure, "Still watching?"), so the card still comes. The card takes no keys after the player fails. | `PlayNextTest` (23) |
| Multiview tile | `ui/MultiviewScreen.kt` | Playing, Retrying, Failed | fixed 11-09: the retry budget comes back after a READY, and a failed tile is prepared again on resume | none |
| Live retry budgets | `PlayerScreen` `networkRetries`, `busyTries`, `tsTried`, `recoveries` | NetRetry(n), Busy409(n), TsFallback, StallRecover(n) | open: these budgets do not share one cap (they do not overlap in practice) | `StreamDiagTest` (busyWait) |
| Stall watch | `data/QualityFallback.kt` `StallWatch` | stall list plus `started`, `seekPending` | fixed 11-10: `onSeek(now)` ignores buffering only for 2 s after the seek, so a seek inside the buffer no longer hides the next stall | `QualityFallbackTest` |
| Live zap / pre-swap | `ui/LivePlayer.kt` | Zapping, Resolving, Playing | open (no bug found; depends on effect order) | none |
| Captions default | `data/Captions.kt` `CaptionDefault`, glue in `PlayerScreen` `applyCaptions` | Waiting, NoCaptions, English, NoEnglish, DefaultOff, Chosen | explicit (11-11): one `CaptionState`; `onTracks` decides Select, TurnOff or None. A change to the caption parameters that Jarvis did not make is the CC menu (`onUserChange`), so Jarvis never switches it back. The pick holds for that title only (`Captions.carried`), in memory. | `CaptionsTest`; emulator QA 4cap |
| Holiday intro jingle | `ui/IntroScreen.kt` | Waiting (prepared, not playing), Playing, Released | explicit (11-10): one flag (`playWhenReady`), set once when the video first plays; both players are released in one `onDispose`, so a skip or the end stops the jingle with the video. The greeting reads the video's position, not a second timer. Only in Holiday mode. | `HolidayTest` (source check) |

### TV data and sync

| Machine | Owner | States | Status | Tests |
|---|---|---|---|---|
| App update | `data/Updater.kt` | Idle, Checking, Downloading, HandedOver, Failed | fixed 11-09: `busy` stays on until the installer answers (or for 3 minutes), so a second press can't delete the APK mid-install. Checks that overlap no longer clear "Checking…" early. | none |
| House membership | `data/HouseSync.kt` `join`/`leave` | Member, Joining (stamp 0), CopyOnceWaiting (`house_once`) | fixed 11-09: `leave()` ends a pending copy-once, and copy-once into the house you are already in stays Member. Before, both cases cut off profile sync and the tv_link announce for good. Open: Joining has no timeout. | none |
| Relay listener | `data/Relay.kt` `listen` | generation per link | fixed 11-09: an old listener can no longer write `relay_since` after "New link" | none |
| IPTV VOD index | `data/IptvVod.kt` | Memory, File, Downloading(account), Failed | fixed 11-09: a load is shared only with callers of the same account. Before, a new playlist could wait on the old account's index and build its links. | `XtreamVodTest` (parsing) |
| Extra guide cache | `data/GuideExtra.kt` | Fresh (6 h), Partial (1 h), Failed (retry 10 min) | fixed 11-09: another playlist's rows are never served, and a partial load (McD-Server away) is fetched again after an hour, not 6 | `GuideExtraTest` (parsing) |
| Provider guide cache | `data/Epg.kt` | Empty, Fresh, Failed-90s, Expired | open (mostly sound: mutex) | `EpgTimeTest`, `GuideMatchTest` |
| Playlist load | `data/Live.kt` `M3u` | Cached, Loading, Failed (30 s) | fixed 11-10: a failure is kept for 30 s (no retry storm unless forced), and `maxConnections` resets when the playlist changes | `M3uTest` |
| Real-Debrid sign-in / tokens | `data/RealDebrid.kt` | Disconnected, Valid, Expired, Refreshing, GrantDead | open (high risk: house-synced keys) | `RdErrorsTest` |
| Resume push | `data/ResumeCloud.kt` | Idle, Scheduled, Pushing | open: a failed "stopped" push is not retried | `ProgressMergeTest` |
| Profile sync scheduler | `data/sync/ProfileSync.kt` | many timers | open (policies explicit: FlushPolicy, Backoff) | `SyncPolicyTest` |
| Wall evidence | `data/WallRecorder.kt`, `WallWatch.kt` | `wall_left` "", "screen on", "screen off" | explicit core; values are wire strings | `WallWatchTest` |

### TV screens

| Machine | Owner | States | Status | Tests |
|---|---|---|---|---|
| Search | `ui/BrowseScreens.kt` SearchScreen | Typing, Searching, Results, Failed | fixed 11-09: submitting the same words again retries the title search too, not only Ask Jarvis | none |
| Service catalog grid | `ui/BrowseScreens.kt` ServiceGridScreen | Loading, Shown, Failed, Ended | fixed 11-09: an empty catalog said "Loading…" forever and Load more never stopped | none |
| Real-Debrid Cloud | `ui/RdCloudScreen.kt` | List, Folder, Opening | fixed 11-09: one file opens at a time, old notes clear on folder change or Refresh, and Refresh reloads an open folder | none |
| Sources | `ui/SourcesScreen.kt`, `data/SourcePick.kt` | Idle(note), Working(note), Opened | explicit (11-10): one `SourcePick` value; Retry clears the old message; a cancel no longer leaves the screen busy | `SourcePickTest`, `SourceQueueTest` |
| Games board | `ui/GamesScreen.kt` `LeagueLoad` | Loading, Ok, Failed | fixed 11-10: a request number per league; an older answer is dropped, and a cancel puts back the previous state | `GamesParseTest` |
| Screen loads | `ui/Common.kt` `Load<T>`, `rememberLoad` | Loading, Ok, Err | explicit (no Empty or Refreshing state; callers add retry keys) | indirect |
| Settings dialogs | `ui/SettingsScreen.kt` `SettingsDialog?` | sealed | explicit | `SettingsModelTest` |

### Web app (`docs/app/index.html`)

| Machine | Owner | States | Status | Tests |
|---|---|---|---|---|
| Relay connection | `connect()`, `unpair()`, `S.status` | none, connecting, online, offline | fixed 11-09: generation `connGen`. Overlapping connects no longer leak a second EventSource, and an unpair during the poll leaves no stream. | `qa.mjs` "state machines" |
| Live TV playlist | `LV` `st`, `loading` | idle, none, loading, ok, error | fixed 11-09: the load in flight is keyed by link, `reset()` drops it, and `st` is no longer changed in place. Before, a new link could spin for good. | `livehome-qa.mjs` |
| Real-Debrid device sign-in | `RDL`, `rdLoginStart()` | Idle, Waiting(code), Connected | fixed 11-09: each poll belongs to one code. Cancel during a poll saves nothing and starts no second loop. | `qa.mjs` "state machines" |
| Watchlist queue | `WLQ.flush()` | Queued, Sent, Acked | fixed 11-09: a flush re-reads each title, so an edit made during the flush wins | `qa.mjs` "state machines" |
| VOD player | `renderPlayer` | loading(i), needTap, playing, stalled, stepping, broken, gone | open (largest): `brokenDone` means two things; `started` and `goodT` are not reset per candidate | `interchange.mjs` |
| Live TV player | `LV.play` | started, stepped, locked, done | fixed 11-10: a switch gets its own 25 s limit (`armSwitch`), then goes back to the copy that played or fails; nothing runs after `done` | `qf-net.mjs` |
| Profile photos | `PH` | pending, confirmed, tombstone | open: a failed send can be undone by the TV's older copy | `qa.mjs` photos |
| Update flow | `updateBusy()` | idle, ready, busy | open: busy is worked out from the DOM, not from a registry | `qa.mjs` update |
| Control page save | `docs/index.html` `dirtyFlag` | clean, dirty, sent | open: a state message can overwrite a save still in flight | none |
