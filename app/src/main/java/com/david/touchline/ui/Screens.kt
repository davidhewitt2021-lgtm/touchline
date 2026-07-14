package com.david.touchline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val confidence = boardConfidence(s)
    val wageBill = s.squad(user.id).sumOf { it.wage }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Club header
        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelGreen), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Crest(user, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.name, fontSize = 19.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Season ${s.season} · Round ${s.round}/${s.totalRounds} · ${ordinalText(pos)}",
                            color = MutedGrass, fontSize = 12.sp
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Board confidence", color = MutedGrass, fontSize = 11.sp)
                        Text("$confidence%", color = if (confidence < 30) Color(0xFFEF5350) else TouchLime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { confidence / 100f },
                        color = if (confidence < 30) Color(0xFFEF5350) else TouchLime,
                        trackColor = Color(0xFF0E2015),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Budget ${money(user.budget)} · Wages ${money(wageBill)}/wk",
                        color = if (user.budget < 0) Color(0xFFEF5350) else MutedGrass, fontSize = 12.sp
                    )
                }
            }
        }

        // Next match
        item {
            Card(colors = CardDefaults.cardColors(containerColor = PanelGreen), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (fixture != null) {
                        val opp = if (fixture.homeId == user.id) s.team(fixture.awayId) else s.team(fixture.homeId)
                        val venue = if (fixture.homeId == user.id) "Home" else "Away"
                        val oppPos = table.indexOfFirst { it.teamId == opp.id } + 1
                        Text("NEXT MATCH", color = TouchLime, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("${opp.name}  ·  $venue", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${ordinalText(oppPos)} in the league · reputation ${opp.reputation}", color = MutedGrass, fontSize = 12.sp)
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
        items(s.inbox.takeLast(10).reversed()) { msg ->
            var expanded by remember(msg) { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { expanded = !expanded }
            ) {
                Text(
                    msg,
                    Modifier.fillMaxWidth().padding(12.dp),
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        item {
            var confirm by remember { mutableStateOf(false) }
            var updateMsg by remember { mutableStateOf<String?>(null) }
            var updateInfo by remember { mutableStateOf<Updater.UpdateInfo?>(null) }
            var checking by remember { mutableStateOf(false) }
            val context = androidx.compose.ui.platform.LocalContext.current

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        updateMsg = null
                        Updater.check(com.david.touchline.BuildConfig.VERSION_CODE) { info, err ->
                            checking = false
                            updateInfo = info
                            updateMsg = when {
                                info != null -> "Build ${info.buildNumber} available"
                                err != null -> err
                                else -> "You're on the latest build"
                            }
                        }
                    }
                ) { Text(if (checking) "Checking…" else "Check for updates", color = TouchLime, fontSize = 12.sp) }
                TextButton(onClick = { confirm = true }) { Text("Abandon save", color = MutedGrass, fontSize = 12.sp) }
            }
            updateMsg?.let { msg ->
                Text(msg, color = MutedGrass, fontSize = 12.sp)
                updateInfo?.let { info ->
                    Button(
                        onClick = { Updater.openDownload(context, info.downloadUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = TouchLime),
                        modifier = Modifier.padding(top = 6.dp)
                    ) { Text("Download update", fontWeight = FontWeight.Bold) }
                }
            }
            Text(
                "Touchline v${com.david.touchline.BuildConfig.VERSION_NAME}",
                color = MutedGrass.copy(alpha = 0.6f), fontSize = 10.sp
            )
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

fun surname(name: String): String = name.substringAfterLast(' ')

// ----------------------------------------------------------------- SQUAD ----

@Composable
fun SquadScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val squad = s.squad(s.userTeamId).sortedWith(compareBy({ it.position.ordinal }, { -it.overall }))
    val xi = s.userTeam().tactics.startingXI

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            HeroHeader(Tab.SQUAD, "Squad", "${squad.size} players · tap for details")
            Spacer(Modifier.height(6.dp))
        }
        items(squad) { p ->
            PlayerRow(p, starter = p.id in xi) { vm.detailPlayerId = p.id }
        }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Black)
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (p.injuryWeeks > 0) StatusBadge("INJ ${p.injuryWeeks}w", Color(0xFFEF5350))
                    if (p.banMatches > 0) StatusBadge("BAN", Color(0xFFE0C341))
                }
                Text(
                    "Age ${p.age} · form ${"%.1f".format(p.form)} · ${p.seasonGoals} gls · £${p.wage}/wk",
                    color = MutedGrass, fontSize = 11.sp
                )
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
                if (p.injuryWeeks > 0) Text("Injured — ${p.injuryWeeks} week(s) remaining", color = Color(0xFFEF5350), fontSize = 12.sp)
                if (p.banMatches > 0) Text("Suspended — misses the next match", color = Color(0xFFE0C341), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                AttrBar("Pace", p.attr.pace)
                AttrBar("Shooting", p.attr.shooting)
                AttrBar("Passing", p.attr.passing)
                AttrBar("Dribbling", p.attr.dribbling)
                AttrBar("Defending", p.attr.defending)
                AttrBar("Physical", p.attr.physical)
                if (p.position == Position.GK) AttrBar("Keeping", p.attr.keeping)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Overall ${p.overall} · Form ${"%.1f".format(p.form)} · £${p.wage}/wk\nValue ${money(p.value)} · Morale ${p.morale} · ${p.seasonYellows} yellow(s)",
                    fontSize = 12.sp, color = MutedGrass
                )
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

private data class SlotSpec(val fx: Float, val fy: Float)

/** Slot layout for the formation: index-aligned with startingXI (GK, DFs, MFs, FWs). */
private fun slotSpecs(f: Formation): List<SlotSpec> {
    val specs = mutableListOf<SlotSpec>()
    fun line(fy: Float, count: Int) {
        for (i in 0 until count) specs.add(SlotSpec((i + 1f) / (count + 1f), fy))
    }
    line(0.90f, 1)
    line(0.70f, f.df)
    line(0.46f, f.mf)
    line(0.22f, f.fw)
    return specs
}

@Composable
fun TacticsScreen(vm: GameViewModel) {
    val s = vm.state ?: return
    val user = s.userTeam()
    val tactics = user.tactics
    var slotDialogFor by remember { mutableStateOf<Int?>(null) }   // player id

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        HeroHeader(Tab.TACTICS, "Tactics", "${tactics.formation.label} · drag the shape of your side")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Formation.entries.forEach { f ->
                FilterChip(
                    selected = tactics.formation == f,
                    onClick = { vm.setFormation(f) },
                    label = { Text(f.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TouchLime,
                        selectedLabelColor = Color(0xFF10240F)
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // The pitch board — attack plays up the screen
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            val bw = maxWidth
            val bh = maxHeight
            TacticsPitch(Modifier.fillMaxSize())
            val specs = slotSpecs(tactics.formation)
            val xi = tactics.startingXI
            for (i in 0 until minOf(11, xi.size)) {
                val p = s.player(xi[i]) ?: continue
                val spec = specs[i.coerceAtMost(specs.size - 1)]
                PlayerSlot(
                    p = p,
                    kitColor = Color(user.colorPrimary),
                    modifier = Modifier
                        .offset(x = bw * spec.fx - 31.dp, y = bh * spec.fy - 34.dp)
                        .width(62.dp)
                        .clickable { slotDialogFor = p.id }
                )
            }
        }
        Text(
            "Attacking up the pitch · tap a player to change or reposition",
            color = MutedGrass, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp)
        )

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
            Text("Attacking", color = MutedGrass, fontSize = 11.sp)
        }
    }

    slotDialogFor?.let { pid ->
        val p = s.player(pid)
        val xi = tactics.startingXI
        val bench = s.squad(s.userTeamId).filter { it.id !in xi }
            .sortedWith(compareBy({ it.position != p?.position }, { -it.overall }))
        val others = xi.mapNotNull { s.player(it) }.filter { it.id != pid }
        AlertDialog(
            onDismissRequest = { slotDialogFor = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { slotDialogFor = null }) { Text("Cancel") } },
            title = { Text(p?.name ?: "") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.height(400.dp)) {
                    item { SectionLabel("BRING ON") }
                    items(bench) { b ->
                        PlayerRow(b) {
                            vm.swapStarter(pid, b.id)
                            slotDialogFor = null
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        SectionLabel("SWAP POSITION WITH")
                    }
                    items(others) { o ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = PanelGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.swapPositions(pid, o.id)
                                slotDialogFor = null
                            }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(o.position.label, color = MutedGrass, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                                Text(o.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("${o.overall}", color = TouchLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PlayerSlot(p: Player, kitColor: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PanelGreen)
                .border(2.5.dp, if (p.available) kitColor else Color(0xFFEF5350), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("${p.overall}", color = ChalkWhite, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Text(
            surname(p.name),
            color = ChalkWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            if (!p.available) (if (p.injuryWeeks > 0) "INJ" else "BAN") else "%.1f".format(p.form),
            color = if (!p.available) Color(0xFFEF5350) else MutedGrass,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun TacticsPitch(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Grass
        val stripes = 8
        for (i in 0 until stripes) {
            drawRect(
                color = if (i % 2 == 0) Color(0xFF11351F) else Color(0xFF153E24),
                topLeft = Offset(0f, h * i / stripes),
                size = Size(w, h / stripes + 1f)
            )
        }
        val lc = Color(0x55FFFFFF)
        val line = Stroke(width = 1.5.dp.toPx())
        drawRect(lc, topLeft = Offset(2f, 2f), size = Size(w - 4f, h - 4f), style = line)
        drawLine(lc, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = line.width)
        drawCircle(lc, radius = w * 0.13f, center = Offset(w / 2, h / 2), style = line)
        // Boxes top and bottom
        val bw = w * 0.55f
        val bh = h * 0.14f
        drawRect(lc, topLeft = Offset((w - bw) / 2, 2f), size = Size(bw, bh), style = line)
        drawRect(lc, topLeft = Offset((w - bw) / 2, h - bh - 2f), size = Size(bw, bh), style = line)
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
            HeroHeader(Tab.LEAGUE, "League", "Season ${s.season} · Round ${s.round}/${s.totalRounds}")
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
                Crest(team, 20.dp, showText = false)
                Spacer(Modifier.width(6.dp))
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
            HeroHeader(Tab.TRANSFERS, "Market", "Budget ${money(s.userTeam().budget)} · tap a player to buy")
            Spacer(Modifier.height(6.dp))
        }
        if (listed.isEmpty()) {
            item { Text("Nobody is listed right now. The market refreshes each season.", color = MutedGrass, fontSize = 13.sp) }
        }
        items(listed) { p ->
            PlayerRow(p, trailing = money(p.value)) { vm.detailPlayerId = p.id }
        }
    }
}
