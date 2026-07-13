package com.david.touchline.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Position(val label: String) { GK("GK"), DF("DF"), MF("MF"), FW("FW") }

@Serializable
data class Attributes(
    var pace: Int,
    var shooting: Int,
    var passing: Int,
    var dribbling: Int,
    var defending: Int,
    var physical: Int,
    var keeping: Int
)

@Serializable
data class Player(
    val id: Int,
    val name: String,
    var age: Int,
    val position: Position,
    val attr: Attributes,
    var potential: Int,
    var teamId: Int,
    var fitness: Int = 100,
    var morale: Int = 70,
    var seasonGoals: Int = 0,
    var seasonApps: Int = 0,
    var transferListed: Boolean = false
) {
    val overall: Int
        get() = when (position) {
            Position.GK -> (attr.keeping * 5 + attr.physical + attr.passing) / 7
            Position.DF -> (attr.defending * 3 + attr.physical * 2 + attr.pace + attr.passing) / 7
            Position.MF -> (attr.passing * 3 + attr.dribbling * 2 + attr.defending + attr.physical) / 7
            Position.FW -> (attr.shooting * 3 + attr.pace * 2 + attr.dribbling * 2) / 7
        }

    val value: Int
        get() {
            val base = overall * overall * 14
            val ageFactor = when {
                age <= 21 -> 1.5
                age <= 24 -> 1.3
                age <= 28 -> 1.1
                age <= 31 -> 0.8
                else -> 0.45
            }
            return ((base * ageFactor).toInt() / 5000) * 5000 + 20000
        }
}

@Serializable
enum class Formation(val label: String, val df: Int, val mf: Int, val fw: Int) {
    F442("4-4-2", 4, 4, 2),
    F433("4-3-3", 4, 3, 3),
    F352("3-5-2", 3, 5, 2),
    F4231("4-2-3-1", 4, 5, 1),
    F532("5-3-2", 5, 3, 2)
}

@Serializable
data class Tactics(
    var formation: Formation = Formation.F442,
    /** 0 = ultra defensive, 100 = all-out attack */
    var mentality: Int = 50,
    var startingXI: MutableList<Int> = mutableListOf()
)

@Serializable
data class Team(
    val id: Int,
    val name: String,
    val short: String,
    val colorPrimary: Long,
    val colorSecondary: Long,
    var budget: Int,
    var reputation: Int,
    var tactics: Tactics = Tactics()
)

@Serializable
data class Fixture(
    val round: Int,
    val homeId: Int,
    val awayId: Int,
    var played: Boolean = false,
    var homeGoals: Int = 0,
    var awayGoals: Int = 0
)

@Serializable
enum class EventType { GOAL, CHANCE, SAVE, CARD, INFO, FREEKICK, PENALTY, CORNER }

@Serializable
data class MatchEvent(
    val tick: Int,
    val minute: Int,
    val type: EventType,
    val teamId: Int,
    val playerId: Int,
    val text: String
)

@Serializable
data class GameState(
    var season: Int = 1,
    var round: Int = 1,
    var userTeamId: Int = 0,
    val teams: MutableList<Team> = mutableListOf(),
    val players: MutableList<Player> = mutableListOf(),
    var fixtures: MutableList<Fixture> = mutableListOf(),
    val inbox: MutableList<String> = mutableListOf(),
    var nextPlayerId: Int = 1,
    var seed: Long = 0L
) {
    fun team(id: Int): Team = teams.first { it.id == id }
    fun player(id: Int): Player? = players.firstOrNull { it.id == id }
    fun squad(teamId: Int): List<Player> = players.filter { it.teamId == teamId }
    fun userTeam(): Team = team(userTeamId)
    val totalRounds: Int get() = (teams.size - 1) * 2
}

data class TableRow(
    val teamId: Int,
    var played: Int = 0,
    var won: Int = 0,
    var drawn: Int = 0,
    var lost: Int = 0,
    var gf: Int = 0,
    var ga: Int = 0
) {
    val gd: Int get() = gf - ga
    val points: Int get() = won * 3 + drawn
}

fun leagueTable(state: GameState): List<TableRow> {
    val rows = state.teams.associate { it.id to TableRow(it.id) }
    for (f in state.fixtures) {
        if (!f.played) continue
        val h = rows.getValue(f.homeId)
        val a = rows.getValue(f.awayId)
        h.played++; a.played++
        h.gf += f.homeGoals; h.ga += f.awayGoals
        a.gf += f.awayGoals; a.ga += f.homeGoals
        when {
            f.homeGoals > f.awayGoals -> { h.won++; a.lost++ }
            f.homeGoals < f.awayGoals -> { a.won++; h.lost++ }
            else -> { h.drawn++; a.drawn++ }
        }
    }
    return rows.values.sortedWith(
        compareByDescending<TableRow> { it.points }
            .thenByDescending { it.gd }
            .thenByDescending { it.gf }
    )
}

/** Picks the best available XI for a team's formation. Returns player ids: GK, DFs, MFs, FWs. */
fun autoPickXI(state: GameState, teamId: Int): MutableList<Int> {
    val squad = state.squad(teamId).sortedByDescending { it.overall }
    val f = state.team(teamId).tactics.formation
    val xi = mutableListOf<Int>()
    fun take(pos: Position, n: Int) {
        squad.filter { it.position == pos && it.id !in xi }.take(n).forEach { xi.add(it.id) }
    }
    take(Position.GK, 1)
    take(Position.DF, f.df)
    take(Position.MF, f.mf)
    take(Position.FW, f.fw)
    // Fill any gaps (e.g. not enough natural fits) with the best remaining outfielders
    for (p in squad) {
        if (xi.size >= 11) break
        if (p.id !in xi && p.position != Position.GK) xi.add(p.id)
    }
    return xi
}
