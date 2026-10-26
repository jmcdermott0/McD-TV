package com.mcd.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.ProfilePhotos
import com.mcd.tv.data.sync.ProfileSync

/** Start-up switch for the profile picker. The automated QA launch ("--ez qa true") skips it. */
object ProfileStart {
    @Volatile var skip: Boolean = false
}

/**
 * "Who's watching?": three large HUD cards (Dad, Mom, Kids by default). Picking one makes it the active
 * profile (its own history, My List, favorites and Live TV favorites) and calls [onPicked].
 */
@Composable
fun ProfilePickerScreen(onPicked: () -> Unit) {
    val first = remember { FocusRequester() }
    val active = remember { Prefs.activeProfile }
    // Photos set from the phone; a copy that arrives from another TV while this screen is open shows at once.
    val photoRev by ProfilePhotos.revision.collectAsState()
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().hudBackground(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        McdLogo(scale = 0.9f)
        Spacer(Modifier.height(22.dp))
        Text("WHO'S WATCHING?", style = broadcastStyle(30.sp))
        Spacer(Modifier.height(6.dp))
        // Version on the first screen, so it's clear at a glance whether this TV has the latest update.
        Text("Jarvis v${com.mcd.tv.BuildConfig.VERSION_NAME}", color = McdColors.Muted, fontSize = 14.sp)
        val syncLine = remember { ProfileSync.statusLine() }
        if (syncLine.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(syncLine, color = McdColors.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Prefs.PROFILE_IDS.forEachIndexed { i, id ->
                ProfileCard(
                    name = Prefs.profileName(id),
                    photo = remember(photoRev, id) { ProfilePhotos.bitmap(id) },
                    kids = id == Prefs.KIDS_PROFILE,
                    current = id == active,
                    onClick = { Prefs.activeProfile = id; onPicked() },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(name: String, photo: ImageBitmap?, kids: Boolean, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HudCard(onClick = onClick, modifier = modifier.width(220.dp).height(250.dp), focusedScale = 1.08f) { focused ->
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ring = if (focused) McdColors.AccentBright else if (kids) McdColors.Amber else McdColors.Accent
            Box(
                Modifier.size(110.dp)
                    .background(ring.copy(alpha = if (focused) 0.18f else 0.08f), CircleShape)
                    .border(if (focused) 3.dp else 2.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // The ring (border) draws over the photo, so the circle looks the same with or without one.
                if (photo != null) {
                    Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Text(name.trim().take(1).uppercase().ifBlank { "?" }, style = broadcastStyle(48.sp, ring))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                name.uppercase(), style = broadcastStyle(20.sp, if (focused) McdColors.AccentBright else McdColors.White),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    current -> "Watching now"
                    kids -> "Family picks"
                    else -> " "
                },
                color = McdColors.Muted, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}
