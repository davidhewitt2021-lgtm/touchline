package com.david.touchline.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.touchline.engine.GameState
import com.david.touchline.engine.Team
import kotlin.math.abs

// ---- Floodlit-night palette -------------------------------------------------
val NightPitch = Color(0xFF0B1A12)
val PanelGreen = Color(0xFF12281B)
val ChalkWhite = Color(0xFFEDF3EC)
val TouchLime = Color(0xFFA8F04B)
val MutedGrass = Color(0xFF6F8A76)

// ---- Per-section identities -------------------------------------------------
data class TabMeta(val label: String, val accent: Color, val icon: ImageVector)

val tabMeta: Map<Tab, TabMeta> = mapOf(
    Tab.HOME to TabMeta("Home", TouchLime, Icons.Filled.Home),
    Tab.SQUAD to TabMeta("Squad", Color(0xFF4BD9F0), Icons.Filled.Person),
    Tab.TACTICS to TabMeta("Tactics", Color(0xFFF0B24B), Icons.Filled.Build),
    Tab.LEAGUE to TabMeta("League", Color(0xFFB08CFF), Icons.Filled.Star),
    Tab.TRANSFERS to TabMeta("Market", Color(0xFFF0D34B), Icons.Filled.ShoppingCart)
)

fun money(pounds: Int): String {
    val a = abs(pounds)
    val sign = if (pounds < 0) "-" else ""
    return when {
        a >= 1_000_000 -> "$sign£%.2fm".format(a / 1_000_000.0)
        a >= 1_000 -> "$sign£${a / 1000}k"
        else -> "$sign£$a"
    }
}

private val TouchlineColors = darkColorScheme(
    primary = TouchLime,
    onPrimary = Color(0xFF10240F),
    background = NightPitch,
    onBackground = ChalkWhite,
    surface = PanelGreen,
    onSurface = ChalkWhite,
    surfaceVariant = Color(0xFF1A3323),
    onSurfaceVariant = MutedGrass,
    secondaryContainer = Color(0xFF1F3D28),
    onSecondaryContainer = ChalkWhite
)

@Composable
fun TouchlineApp(vm: GameViewModel) {
    MaterialTheme(colorScheme = TouchlineColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            @Suppress("UNUSED_VARIABLE") val v = vm.version
            var showTitle by rememberSaveable { mutableStateOf(true) }
            when {
                vm.liveMatch != null -> MatchScreen(vm)
                showTitle -> TitleScreen(vm, onEnter = { showTitle = false })
                vm.state == null -> NewGameScreen(vm)
                else -> MainScaffold(vm)
            }
        }
    }
}

// ------------------------------------------------------------ title screen ----

@Composable
fun TitleScreen(vm: GameViewModel, onEnter: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "glow"
    )
    var confirmNew by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF06110A), NightPitch, Color(0xFF0E2416))
                )
            )
    ) {
        // Faint pitch geometry behind everything
        Canvas(Modifier.fillMaxSize().alpha(0.10f)) {
            val w = size.width
            val h = size.height
            val lw = 3f
            drawCircle(ChalkWhite, radius = w * 0.42f, center = Offset(w / 2, h * 0.40f), style = Stroke(lw))
            drawLine(ChalkWhite, Offset(0f, h * 0.40f), Offset(w, h * 0.40f), strokeWidth = lw)
            drawCircle(ChalkWhite, radius = w * 0.05f, center = Offset(w / 2, h * 0.40f), style = Stroke(lw))
        }

        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.9f))

            // Glowing match ball
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(120.dp)
                        .alpha(glow)
                        .background(
                            Brush.radialGradient(listOf(TouchLime.copy(alpha = 0.45f), Color.Transparent)),
                            CircleShape
                        )
                )
                Canvas(Modifier.size(64.dp)) {
                    drawCircle(ChalkWhite)
                    drawCircle(Color(0xFF10240F), radius = size.minDimension * 0.11f)
                    for (i in 0 until 5) {
                        val ang = Math.toRadians(90.0 + i * 72)
                        val r = size.minDimension * 0.30f
                        drawCircle(
                            Color(0xFF10240F),
                            radius = size.minDimension * 0.075f,
                            center = Offset(
                                (size.width / 2 + r * Math.cos(ang)).toFloat(),
                                (size.height / 2 - r * Math.sin(ang)).toFloat()
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "TOUCHLINE",
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                color = ChalkWhite,
                letterSpacing = 6.sp
            )
            Text(
                "EVERY DECISION IS YOURS",
                fontSize = 12.sp,
                color = TouchLime,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.weight(1f))

            if (vm.state != null) {
                val s = vm.state!!
                Button(
                    onClick = onEnter,
                    colors = ButtonDefaults.buttonColors(containerColor = TouchLime),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Continue — ${s.userTeam().name}", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmNew = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("New career", color = ChalkWhite) }
            } else {
                Button(
                    onClick = onEnter,
                    colors = ButtonDefaults.buttonColors(containerColor = TouchLime),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Start your career", fontWeight = FontWeight.Black, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "v${com.david.touchline.BuildConfig.VERSION_NAME}",
                color = MutedGrass.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    vm.deleteSave()
                    onEnter()
                }) { Text("Start fresh", color = Color(0xFFEF5350)) }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancel") } },
            title = { Text("Start a new career?") },
            text = { Text("Your current save will be deleted.") }
        )
    }
}

// ---------------------------------------------------------------- scaffold ----

@Composable
private fun MainScaffold(vm: GameViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0E2013), tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val meta = tabMeta.getValue(t)
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = { Icon(meta.icon, contentDescription = meta.label, modifier = Modifier.size(22.dp)) },
                        label = { Text(meta.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = meta.accent,
                            selectedTextColor = meta.accent,
                            unselectedIconColor = MutedGrass,
                            unselectedTextColor = MutedGrass,
                            indicatorColor = meta.accent.copy(alpha = 0.14f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            Crossfade(targetState = vm.tab, label = "tab") { tab ->
                when (tab) {
                    Tab.HOME -> HomeScreen(vm)
                    Tab.SQUAD -> SquadScreen(vm)
                    Tab.TACTICS -> TacticsScreen(vm)
                    Tab.LEAGUE -> LeagueScreen(vm)
                    Tab.TRANSFERS -> TransfersScreen(vm)
                }
            }
        }
    }
    vm.detailPlayerId?.let { PlayerDetailDialog(vm, it) }
}

// --------------------------------------------------------- shared components ----

/** Section hero: gradient wash in the tab's accent, oversized watermark icon. */
@Composable
fun HeroHeader(tab: Tab, title: String, subtitle: String) {
    val meta = tabMeta.getValue(tab)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(meta.accent.copy(alpha = 0.20f), meta.accent.copy(alpha = 0.04f))
                )
            )
    ) {
        Icon(
            meta.icon,
            contentDescription = null,
            tint = meta.accent.copy(alpha = 0.12f),
            modifier = Modifier
                .size(84.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 12.dp)
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Black, color = ChalkWhite)
            Text(subtitle, fontSize = 12.sp, color = meta.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Drawn two-tone club crest. */
@Composable
fun Crest(team: Team, size: androidx.compose.ui.unit.Dp, showText: Boolean = true) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Color(team.colorPrimary), radius = r)
            // Central band in the second colour
            drawRect(
                Color(team.colorSecondary),
                topLeft = Offset(this.size.width * 0.38f, 0f),
                size = androidx.compose.ui.geometry.Size(this.size.width * 0.24f, this.size.height)
            )
            drawCircle(Color.White.copy(alpha = 0.55f), radius = r - 1f, style = Stroke(width = r * 0.10f))
        }
        if (showText) {
            Text(
                team.short,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.28f).sp,
                letterSpacing = 0.5.sp,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f)
                )
            )
        }
    }
}

@Composable
fun NewGameScreen(vm: GameViewModel) {
    var world by remember { mutableStateOf<GameState?>(null) }
    LaunchedEffect(Unit) { if (world == null) world = vm.buildWorldPreview() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("CHOOSE YOUR CLUB", fontSize = 26.sp, fontWeight = FontWeight.Black, color = ChalkWhite, letterSpacing = 2.sp)
        Text("Take them to the title. The board will be watching.", color = MutedGrass, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        val w = world
        if (w == null) {
            CircularProgressIndicator(color = TouchLime)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(w.teams.sortedByDescending { it.reputation }) { team ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PanelGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.newGame(team.id, w) }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Crest(team, 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(team.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Reputation ${team.reputation} · Budget ${money(team.budget)}",
                                    color = MutedGrass, fontSize = 12.sp
                                )
                            }
                            Text("MANAGE", color = TouchLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
