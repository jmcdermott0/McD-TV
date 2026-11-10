package com.mcd.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.RdCloud
import com.mcd.tv.data.RdItem
import com.mcd.tv.data.RealDebrid
import kotlinx.coroutines.launch

@Composable
private fun RowCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = McdColors.Muted, fontSize = 13.sp)
        }
    }
}

/**
 * Your Real-Debrid cloud: everything in your own RD account, playable here.
 * Add more on the Control page (magnet link) or on real-debrid.com.
 */
@Composable
fun RdCloudScreen(nav: Nav) {
    var refresh by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<RdItem?>(null) }
    var status by remember { mutableStateOf("") }
    // One file opening at a time: a second OK while it opens would push two players.
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val items by rememberLoad(refresh) { RdCloud.list() }
    // Back inside a folder returns to the list instead of leaving the screen.
    BackHandler(enabled = opened != null) { opened = null }
    // After opening or closing a folder, focus its first row (or the Refresh button) once it is laid out.
    val firstRow = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    var switched by remember { mutableIntStateOf(0) }
    LaunchedEffect(opened) {
        status = "" // a note about the list or another folder does not belong here
        if (switched++ == 0) return@LaunchedEffect // first composition: the tab bar has focus
        withFrameNanos { }
        // Refresh first as a fallback; the first row takes over if it is already on screen.
        runCatching { refreshFocus.requestFocus() }
        runCatching { firstRow.requestFocus() }
    }

    TabPage(nav, NavTab.Library) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("REAL-DEBRID CLOUD", style = broadcastStyle(30.sp))
                ActionButton("Refresh", { status = ""; refresh++ }, Modifier.focusRequester(refreshFocus))
                if (opened != null) ActionButton("Back to list", { opened = null })
            }
            if (!RealDebrid.connected) StatusText("Connect Real-Debrid in Settings first.")
            if (status.isNotBlank()) Text(status, color = McdColors.Red, fontSize = 14.sp)
        }
        val item = opened
        if (item != null) {
            // Keyed on refresh too, so Refresh inside a folder reloads its files.
            val files by rememberLoad(item.id, refresh) { RdCloud.files(item.id) }
            when (val f = files) {
                is Load.Loading -> StatusText("Loading files…", Modifier.padding(start = 48.dp))
                is Load.Err -> StatusText(f.message, Modifier.padding(start = 48.dp))
                is Load.Ok -> LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(f.value) { i, file ->
                        RowCard(file.name, "%.2f GB".format(file.sizeGb), if (i == 0) Modifier.focusRequester(firstRow) else Modifier) {
                            if (!opening) {
                                opening = true
                                status = "Opening…"
                                scope.launch {
                                    try {
                                        runCatching { RealDebrid.unrestrict(file.link) }
                                            .onSuccess { status = ""; nav.push(Screen.Player(it, file.name)) }
                                            .onFailure { if (it !is kotlinx.coroutines.CancellationException) status = it.message ?: "Could not open" }
                                    } finally {
                                        opening = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else when (val l = items) {
            is Load.Loading -> StatusText("Loading your cloud…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(l.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> if (l.value.isEmpty()) StatusText("Your Real-Debrid cloud is empty.", Modifier.padding(start = 48.dp))
            else LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(l.value, key = { _, x -> x.id }) { i, it2 ->
                    val ready = it2.status == "downloaded"
                    RowCard(
                        it2.name,
                        "%.2f GB  •  %s  •  added %s".format(it2.sizeGb, if (ready) "Ready" else "${it2.status} ${it2.progress}%", it2.added),
                        if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                    ) { if (ready) opened = it2 else status = "Still downloading on Real-Debrid (${it2.progress}%)." }
                }
            }
        }
    }
}
