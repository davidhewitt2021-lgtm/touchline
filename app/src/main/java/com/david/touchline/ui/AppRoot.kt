package com.david.touchline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.touchline.engine.GameState

// ---- Floodlit-night palette -------------------------------------------------
val NightPitch = Color(0xFF0B1A12)     // near-black green background
val PanelGreen = Color(0xFF12281B)     // card surfaces
val ChalkWhite = Color(0xFFEDF3EC)     // primary text
val TouchLime = Color(0xFFA8F04B)      // single accent: floodlit kit trim
val MutedGrass = Color(0xFF6F8A76)     // secondary text

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
            // Reading vm.version makes the whole tree recompose on engine mutations
            @Suppress("UNUSED_VARIABLE") val v = vm.version
            when {
                vm.liveMatch != null -> MatchScreen(vm)
                vm.state == null -> NewGameScreen(vm)
                else -> MainScaffold(vm)
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: GameViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = PanelGreen) {
                TabItem(vm, Tab.HOME, "Home")
                TabItem(vm, Tab.SQUAD, "Squad")
                TabItem(vm, Tab.TACTICS, "Tactics")
                TabItem(vm, Tab.LEAGUE, "League")
                TabItem(vm, Tab.TRANSFERS, "Market")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (vm.tab) {
                Tab.HOME -> HomeScreen(vm)
                Tab.SQUAD -> SquadScreen(vm)
                Tab.TACTICS -> TacticsScreen(vm)
                Tab.LEAGUE -> LeagueScreen(vm)
                Tab.TRANSFERS -> TransfersScreen(vm)
            }
        }
    }
    vm.detailPlayerId?.let { PlayerDetailDialog(vm, it) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(vm: GameViewModel, t: Tab, label: String) {
    NavigationBarItem(
        selected = vm.tab == t,
        onClick = { vm.tab = t },
        icon = {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (vm.tab == t) TouchLime else MutedGrass)
            )
        },
        label = { Text(label, fontSize = 11.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedTextColor = TouchLime,
            unselectedTextColor = MutedGrass,
            indicatorColor = Color.Transparent
        )
    )
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
        Text("TOUCHLINE", fontSize = 34.sp, fontWeight = FontWeight.Black, color = TouchLime, letterSpacing = 4.sp)
        Text("Pick a club. Take them to the title.", color = MutedGrass, fontSize = 14.sp)
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
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(team.colorPrimary))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(team.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Reputation ${team.reputation} · Budget £${team.budget / 1000}k",
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
