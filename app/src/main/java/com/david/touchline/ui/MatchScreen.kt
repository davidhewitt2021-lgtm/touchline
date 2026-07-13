package com.david.touchline.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.david.touchline.engine.EventType
import com.david.touchline.engine.PITCH_H
import com.david.touchline.engine.PITCH_W
import kotlinx.coroutines.delay

private val GrassDark = Color(0xFF14572E)
private val GrassLight = Color(0xFF1A6437)
private val LineWhite = Color(0xCCFFFFFF)

@Composable
fun MatchScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val match = vm.liveMatch ?: return
    val home = s.team(match.homeId)
    val away = s.team(match.awayId)
    val frames = match.frames
    val lastFrame = frames.size - 1

    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableIntStateOf(1) }
    var mode3d by remember { mutableStateOf(false) }
    var webReady by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Each frame = 2 seconds of match time. At 1x, one match minute per real second.
    LaunchedEffect(playing, speed) {
        while (playing && frameIndex < lastFrame) {
            delay(33)
            frameIndex = (frameIndex + speed).coerceAtMost(lastFrame)
        }
        if (frameIndex >= lastFrame) playing = false
    }

    // Drive the 3D view whenever the frame changes
    LaunchedEffect(frameIndex, mode3d, webReady) {
        if (mode3d && webReady) {
            webViewRef?.evaluateJavascript("sf($frameIndex)", null)
        }
    }

    val currentTick = frameIndex * 2
    val shownMinute = ((currentTick / 60) + 1).coerceAtMost(90)
    val homeGoals = match.events.count { it.type == EventType.GOAL && it.teamId == match.homeId && it.tick <= currentTick }
    val awayGoals = match.events.count { it.type == EventType.GOAL && it.teamId == match.awayId && it.tick <= currentTick }
    val visibleEvents = match.events.filter { it.tick <= currentTick && it.type != EventType.CHANCE }
    val finished = frameIndex >= lastFrame

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        // Scoreboard
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelGreen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KitDot(Color(home.colorPrimary))
            Spacer(Modifier.width(8.dp))
            Text(home.short, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text("$homeGoals - $awayGoals", fontWeight = FontWeight.Black, fontSize = 26.sp, color = TouchLime)
            Spacer(Modifier.weight(1f))
            Text(away.short, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            KitDot(Color(away.colorPrimary))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                if (finished) "FULL TIME" else "$shownMinute'",
                color = if (finished) TouchLime else MutedGrass,
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
        }

        // Pitch: 2D canvas or 3D WebView
        if (frames.isNotEmpty()) {
            val pitchModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (mode3d) 1.35f else (PITCH_W / PITCH_H).toFloat())
                .clip(RoundedCornerShape(10.dp))
            if (mode3d) {
                Match3DView(
                    frames = frames,
                    homeColor = home.colorPrimary,
                    awayColor = away.colorPrimary,
                    onReady = { wv -> webViewRef = wv; webReady = true },
                    modifier = pitchModifier
                )
            } else {
                val frame = frames[frameIndex.coerceIn(0, lastFrame)]
                PitchCanvas(
                    frame = frame,
                    homeColor = Color(home.colorPrimary),
                    awayColor = Color(away.colorPrimary),
                    modifier = pitchModifier
                )
            }
        }

        // Controls
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = mode3d,
                onClick = { mode3d = !mode3d },
                label = { Text(if (mode3d) "3D" else "2D", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TouchLime,
                    selectedLabelColor = Color(0xFF10240F)
                )
            )
            if (!finished) {
                Button(
                    onClick = { playing = !playing },
                    colors = ButtonDefaults.buttonColors(containerColor = TouchLime),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { Text(if (playing) "Pause" else "Play", fontWeight = FontWeight.Bold, fontSize = 13.sp) }

                listOf(1, 2, 4, 8).forEach { sp ->
                    FilterChip(
                        selected = speed == sp,
                        onClick = { speed = sp; playing = true },
                        label = { Text("${sp}x", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TouchLime,
                            selectedLabelColor = Color(0xFF10240F)
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { frameIndex = lastFrame; playing = false }) {
                    Text("Skip", color = MutedGrass, fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { vm.concludeLiveMatch() },
                    colors = ButtonDefaults.buttonColors(containerColor = TouchLime),
                    modifier = Modifier.weight(1f)
                ) { Text("Continue", fontWeight = FontWeight.Bold) }
            }
        }

        // Match stats at full time
        if (finished) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val st = match.stats
                val totalPoss = (st.homePossession + st.awayPossession).coerceAtLeast(1)
                StatCol("Shots", "${st.homeShots}", "${st.awayShots}")
                StatCol("On target", "${st.homeOnTarget}", "${st.awayOnTarget}")
                StatCol("Corners", "${st.homeCorners}", "${st.awayCorners}")
                StatCol("Poss.", "${st.homePossession * 100 / totalPoss}%", "${st.awayPossession * 100 / totalPoss}%")
            }
        }

        // Event ticker
        Spacer(Modifier.height(8.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(visibleEvents.size) {
            if (visibleEvents.isNotEmpty()) listState.animateScrollToItem(visibleEvents.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelGreen)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(visibleEvents) { e ->
                val color = when (e.type) {
                    EventType.GOAL -> TouchLime
                    EventType.CARD -> Color(0xFFE0C341)
                    EventType.PENALTY -> Color(0xFFEF9A50)
                    EventType.FREEKICK, EventType.CORNER -> ChalkWhite
                    else -> MutedGrass
                }
                Text("${e.minute}'  ${e.text}", color = color, fontSize = 12.sp)
            }
        }
    }
}

// ------------------------------------------------------------- 3D bridge ----

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Match3DView(
    frames: List<FloatArray>,
    homeColor: Long,
    awayColor: Long,
    onReady: (WebView) -> Unit,
    modifier: Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val homeHex = String.format("%06X", homeColor.toInt() and 0xFFFFFF)
                        val awayHex = String.format("%06X", awayColor.toInt() and 0xFFFFFF)
                        view.evaluateJavascript("init('$homeHex','$awayHex')", null)
                        // Push frames in chunks to stay under the JS bridge message limit
                        val perFrame = frames.firstOrNull()?.size ?: 47
                        val chunk = 150
                        var i = 0
                        while (i < frames.size) {
                            val end = minOf(i + chunk, frames.size)
                            val sb = StringBuilder("addFrames([")
                            for (f in i until end) {
                                val fr = frames[f]
                                for (v in fr.indices) {
                                    sb.append((fr[v] * 10).toInt() / 10.0)
                                    if (!(f == end - 1 && v == fr.size - 1)) sb.append(',')
                                }
                            }
                            sb.append("], $perFrame)")
                            view.evaluateJavascript(sb.toString(), null)
                            i = end
                        }
                        view.evaluateJavascript("sf(0)", null)
                        onReady(view)
                    }
                }
                loadUrl("file:///android_asset/match3d.html")
            }
        }
    )
}

// ---------------------------------------------------------------- shared ----

@Composable
private fun KitDot(color: Color) {
    Box(
        Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun StatCol(label: String, homeVal: String, awayVal: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MutedGrass, fontSize = 11.sp)
        Text("$homeVal · $awayVal", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- 2D view ----

@Composable
fun PitchCanvas(frame: FloatArray, homeColor: Color, awayColor: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = x / PITCH_W.toFloat() * w
        fun py(y: Float) = y / PITCH_H.toFloat() * h

        val stripes = 10
        for (i in 0 until stripes) {
            drawRect(
                color = if (i % 2 == 0) GrassDark else GrassLight,
                topLeft = Offset(w * i / stripes, 0f),
                size = Size(w / stripes + 1f, h)
            )
        }

        val line = Stroke(width = 2.dp.toPx() * 0.7f)
        drawRect(LineWhite, topLeft = Offset(1f, 1f), size = Size(w - 2f, h - 2f), style = line)
        drawLine(LineWhite, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = line.width)
        drawCircle(LineWhite, radius = px(9.15f), center = Offset(w / 2, h / 2), style = line)
        val boxD = px(16.5f)
        val boxTop = py((PITCH_H.toFloat() - 40.3f) / 2f)
        val boxH = py(40.3f)
        drawRect(LineWhite, topLeft = Offset(0f, boxTop), size = Size(boxD, boxH), style = line)
        drawRect(LineWhite, topLeft = Offset(w - boxD, boxTop), size = Size(boxD, boxH), style = line)
        val sixD = px(5.5f)
        val sixTop = py((PITCH_H.toFloat() - 18.3f) / 2f)
        val sixH = py(18.3f)
        drawRect(LineWhite, topLeft = Offset(0f, sixTop), size = Size(sixD, sixH), style = line)
        drawRect(LineWhite, topLeft = Offset(w - sixD, sixTop), size = Size(sixD, sixH), style = line)
        val goalTop = py((PITCH_H.toFloat() - 7.3f) / 2f)
        val goalH = py(7.3f)
        drawRect(LineWhite, topLeft = Offset(-3f, goalTop), size = Size(4f, goalH))
        drawRect(LineWhite, topLeft = Offset(w - 1f, goalTop), size = Size(4f, goalH))

        // Players (frame layout: ball xyz at 0..2, players from index 3)
        val r = 5.dp.toPx()
        for (i in 0 until 22) {
            val x = px(frame[3 + i * 2])
            val y = py(frame[4 + i * 2])
            val isHome = i < 11
            val isGK = i == 0 || i == 11
            val base = if (isHome) homeColor else awayColor
            val c = if (isGK) Color(0xFF37E6C0) else base
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = r + 1.5f, center = Offset(x, y + 1.5f))
            drawCircle(c, radius = r, center = Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.8f), radius = r, center = Offset(x, y), style = Stroke(1.5f))
        }

        // Ball: grows and casts a longer shadow when lofted
        val bx = px(frame[0])
        val by = py(frame[1])
        val bz = frame[2]
        val ballR = 3.dp.toPx() * 0.8f * (1f + bz * 0.18f)
        drawCircle(Color.Black.copy(alpha = (0.4f - bz * 0.05f).coerceAtLeast(0.1f)), radius = ballR, center = Offset(bx, by + 1f + px(bz * 0.6f)))
        drawCircle(Color.White, radius = ballR, center = Offset(bx, by))
    }
}
