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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.touchline.engine.*

// ------------------------------------------------------------------ HOME ----

@Composable
fun HomeScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val user = s.userTeam()
    val fixture = Season.userFixture(s)
    val table = leagueTable(s)
    val pos = table.indexOfFirst { it.teamId == user.id } + 1

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text(user.name, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    "Season ${s.season} · Round ${s.round}/${s.totalRounds} · ${ordinalText(pos)} in the league · Budget £${user.budget / 1000}k",
                    color = MutedGrass, fontSize = 13.sp
                )
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelGreen), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (fixture != null) {
                        val opp = if (fixture.homeId == user.id) s.team(fixture.awayId) else s.team(fixture.homeId)
                        val venue = if (fixture.homeId == user.id) "Home" else "Away"
                        Text("NEXT MATCH", color = TouchLime, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("${opp.name}  ·  $venue", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Their reputation: ${opp.reputation}", color = MutedGrass, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.kickOffUserMatch(watch = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = TouchLime)
                            ) { Text("Watch match", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { vm.kickOffUserMatch(watch = false) }) {
                                Text("Quick sim", color = ChalkWhite)
                            }
                        }
                    } else {
                        Text("No fixture this round.", color = MutedGrass)
                    }
                }
            }
        }

        // Last round's results
        val lastRound = s.round - 1
        if (lastRound >= 1) {
            val results = s.fixtures.filter { it.round == lastRound && it.played }
            if (results.isNotEmpty()) {
                item { SectionLabel("LAST ROUND") }
                items(results) { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val mine = f.homeId == user.id || f.awayId == user.id
                        val weight = if (mine) FontWeight.Bold else FontWeight.Normal
                        Text(s.team(f.homeId).name, fontWeight = weight, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${f.homeGoals} - ${f.awayGoals}", fontWeight = weight, fontSize = 13.sp, color = if (mine) TouchLime else ChalkWhite)
                        Text(s.team(f.awayId).name, fontWeight = weight, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        item { SectionLabel("INBOX") }
        items(s.inbox.takeLast(8).reversed()) { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = PanelGreen), shape = RoundedCornerShape(10.dp)) {
                Text(msg, Modifier.fillMaxWidth().padding(12.dp), fontSize = 13.sp)
            }
        }

        item {
            var confirm by remember { mutableStateOf(false) }
            TextButton(onClick = { confirm = true }) { Text("Abandon save", color = MutedGrass, fontSize = 12.sp) }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    confirmButton = {
                        TextButton(onClick = { confirm = false; vm.deleteSave() }) { Text("Delete", color = Color(0xFFEF5350)) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    title = { Text("Delete this save?") },
                    text = { Text("Your career will be gone for good.") }
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = TouchLime, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
}

fun ordinalText(n: Int): String = when {
    n % 100 in 11..13 -> "${n}th"
    n % 10 == 1 -> "${n}st"
    n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"
    else -> "${n}th"
}

// ----------------------------------------------------------------- SQUAD ----

@Composable
fun SquadScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val squad = s.squad(s.userTeamId).sortedWith(compareBy({ it.position.ordinal }, { -it.overall }))
    val xi = s.userTeam().tactics.startingXI

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Squad", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("${squad.size} players · tap for details", color = MutedGrass, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        items(squad) { p ->
            PlayerRow(p, starter = p.id in xi) { vm.detailPlayerId = p.id }
        }
    }
}

@Composable
fun PlayerRow(p: Player, starter: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelGreen),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.position.label,
                color = if (starter) TouchLime else MutedGrass,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Age ${p.age} · ${p.seasonGoals} gls · ${p.seasonApps} apps", color = MutedGrass, fontSize = 11.sp)
            }
            if (trailing != null) {
                Text(trailing, color = TouchLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
            }
            OverallBadge(p.overall)
        }
    }
}

@Composable
fun OverallBadge(overall: Int) {
    val c = when {
        overall >= 75 -> TouchLime
        overall >= 60 -> Color(0xFFE0C341)
        else -> MutedGrass
    }
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Text("$overall", color = c, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}

@Composable
fun PlayerDetailDialog(vm: GameViewModel, playerId: Int) {
    val s = vm.state ?: return
    val p = s.player(playerId) ?: run { vm.detailPlayerId = null; return }
    val mine = p.teamId == s.userTeamId
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { vm.detailPlayerId = null },
        confirmButton = {
            if (mine) {
                TextButton(onClick = {
                    message = vm.sell(p.id)
                    if (message == null) vm.detailPlayerId = null
                }) { Text("Sell (£${(p.value * 9 / 10) / 1000}k)", color = Color(0xFFEF9A50)) }
            } else {
                TextButton(onClick = {
                    message = vm.buy(p.id)
                    if (message == null) vm.detailPlayerId = null
                }) { Text("Buy (£${p.value / 1000}k)", color = TouchLime) }
            }
        },
        dismissButton = { TextButton(onClick = { vm.detailPlayerId = null }) { Text("Close") } },
        title = { Text(p.name) },
        text = {
            Column {
                Text("${p.position.label} · Age ${p.age} · ${s.team(p.teamId).name}", color = MutedGrass, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                AttrBar("Pace", p.attr.pace)
                AttrBar("Shooting", p.attr.shooting)
                AttrBar("Passing", p.attr.passing)
                AttrBar("Dribbling", p.attr.dribbling)
                AttrBar("Defending", p.attr.defending)
                AttrBar("Physical", p.attr.physical)
                if (p.position == Position.GK) AttrBar("Keeping", p.attr.keeping)
                Spacer(Modifier.height(8.dp))
                Text("Overall ${p.overall} · Value £${p.value / 1000}k · Morale ${p.morale}", fontSize = 12.sp, color = MutedGrass)
                message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFEF5350), fontSize = 12.sp)
                }
            }
        }
    )
}

@Composable
fun AttrBar(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MutedGrass, modifier = Modifier.width(76.dp))
        LinearProgressIndicator(
            progress = { value / 100f },
            color = if (value >= 70) TouchLime else ChalkWhite,
            trackColor = Color(0xFF0E2015),
            modifier = Modifier.weight(1f).height(6.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("$value", fontSize = 12.sp, modifier = Modifier.width(26.dp))
    }
}

// --------------------------------------------------------------- TACTICS ----

@Composable
fun TacticsScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val user = s.userTeam()
    val tactics = user.tactics
    var swapping by remember { mutableStateOf<Int?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Tactics", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            SectionLabel("FORMATION")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Formation.entries.forEach { f ->
                    FilterChip(
                        selected = tactics.formation == f,
                        onClick = { vm.setFormation(f) },
                        label = { Text(f.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TouchLime,
                            selectedLabelColor = Color(0xFF10240F)
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("MENTALITY")
            Slider(
                value = tactics.mentality.toFloat(),
                onValueChange = { vm.setMentality(it.toInt()) },
                onValueChangeFinished = { vm.saveGame() },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = TouchLime, activeTrackColor = TouchLime)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Defensive", color = MutedGrass, fontSize = 11.sp)
                Text("Balanced", color = MutedGrass, fontSize = 11.sp)
                Text("Attacking", color = MutedGrass, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("STARTING XI — tap a starter to swap")
            Spacer(Modifier.height(4.dp))
        }

        val starters = tactics.startingXI.mapNotNull { s.player(it) }
        items(starters) { p ->
            PlayerRow(p, starter = true) { swapping = p.id }
        }
    }

    swapping?.let { outId ->
        val outPlayer = s.player(outId)
        val bench = s.squad(s.userTeamId).filter { it.id !in tactics.startingXI }
            .sortedByDescending { it.overall }
        AlertDialog(
            onDismissRequest = { swapping = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { swapping = null }) { Text("Cancel") } },
            title = { Text("Replace ${outPlayer?.name ?: ""}") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(360.dp)) {
                    items(bench) { b ->
                        PlayerRow(b) {
                            vm.swapStarter(outId, b.id)
                            swapping = null
                        }
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------- LEAGUE ----

@Composable
fun LeagueScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val table = leagueTable(s)
    val scorers = s.players.filter { it.seasonGoals > 0 }.sortedByDescending { it.seasonGoals }.take(10)

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("League", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("#", color = MutedGrass, fontSize = 11.sp, modifier = Modifier.width(24.dp))
                Text("CLUB", color = MutedGrass, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("P", color = MutedGrass, fontSize = 11.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                Text("GD", color = MutedGrass, fontSize = 11.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                Text("PTS", color = MutedGrass, fontSize = 11.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }
        }
        items(table.withIndex().toList()) { (idx, row) ->
            val team = s.team(row.teamId)
            val mine = team.id == s.userTeamId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (mine) TouchLime.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${idx + 1}", fontSize = 13.sp, color = if (idx == 0) TouchLime else ChalkWhite, modifier = Modifier.width(24.dp))
                Text(team.name, fontSize = 13.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                Text("${row.played}", fontSize = 13.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                Text("${row.gd}", fontSize = 13.sp, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                Text("${row.points}", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }
        }

        if (scorers.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                SectionLabel("TOP SCORERS")
                Spacer(Modifier.height(4.dp))
            }
            items(scorers) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(p.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(s.team(p.teamId).short, fontSize = 12.sp, color = MutedGrass, modifier = Modifier.width(44.dp))
                    Text("${p.seasonGoals}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TouchLime)
                }
            }
        }
    }
}

// ------------------------------------------------------------- TRANSFERS ----

@Composable
fun TransfersScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val listed = s.players.filter { it.transferListed && it.teamId != s.userTeamId }
        .sortedByDescending { it.overall }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Transfer market", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Budget £${s.userTeam().budget / 1000}k · tap a player to buy", color = MutedGrass, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
        if (listed.isEmpty()) {
            item { Text("Nobody is listed right now. The market refreshes each season.", color = MutedGrass, fontSize = 13.sp) }
        }
        items(listed) { p ->
            PlayerRow(p, trailing = "£${p.value / 1000}k") { vm.detailPlayerId = p.id }
        }
    }
}
