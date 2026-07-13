package com.david.touchline.engine

import kotlin.random.Random

object WorldGen {

    private val firstNames = listOf(
        "Alfie", "Ben", "Callum", "Dan", "Ewan", "Finn", "George", "Harvey", "Isaac", "Jude",
        "Kian", "Lewis", "Mason", "Nathan", "Ollie", "Patrick", "Quinn", "Rhys", "Sam", "Theo",
        "Vitor", "Wes", "Xavi", "Yannick", "Zane", "Andre", "Bruno", "Carlos", "Diego", "Emil",
        "Fabio", "Goran", "Hugo", "Ivan", "Joao", "Kofi", "Luka", "Mateo", "Nico", "Otto",
        "Pavel", "Rafa", "Sander", "Timo", "Umar", "Viktor", "Wilf", "Yusuf", "Zeki", "Aron"
    )

    private val lastNames = listOf(
        "Ashcombe", "Bellamy", "Cardew", "Draycott", "Ellerton", "Fenwick", "Garrow", "Hallam",
        "Ingram", "Jessop", "Kellaway", "Lockhart", "Marsden", "Norcliffe", "Ormsby", "Penhale",
        "Quiller", "Rowntree", "Selwyn", "Tarleton", "Ulverston", "Vance", "Wetherby", "Yardley",
        "Zouch", "Almara", "Bastida", "Coreno", "Duarte", "Estevan", "Ferraz", "Golan",
        "Horvat", "Ilic", "Jorquera", "Kovac", "Lombardi", "Moravec", "Novak", "Oliveira",
        "Petrov", "Quaresma", "Reznik", "Santoro", "Tavares", "Ustinov", "Varela", "Zoric"
    )

    private val townNames = listOf(
        "Aldermoor", "Bexcliffe", "Carnbrook", "Duncastle", "Eastenvale", "Ferrowgate",
        "Glenmarsh", "Harrowden", "Ivelford", "Kestwick", "Lynmere", "Morecliffe",
        "Netherholt", "Oakhampden"
    )

    private val suffixes = listOf(
        "United", "Town", "Rovers", "Athletic", "City", "Wanderers", "County", "Albion"
    )

    private val kitColors = listOf(
        0xFFD32F2FL to 0xFFFFFFFFL, // red / white
        0xFF1565C0L to 0xFFFFFFFFL, // blue / white
        0xFFFFFFFFL to 0xFF000000L, // white / black
        0xFF2E7D32L to 0xFFFFEB3BL, // green / yellow
        0xFFF9A825L to 0xFF000000L, // amber / black
        0xFF6A1B9AL to 0xFFFFFFFFL, // purple / white
        0xFF000000L to 0xFFFFFFFFL, // black / white
        0xFFF57C00L to 0xFF1A237EL, // orange / navy
        0xFF00838FL to 0xFFFFFFFFL, // teal / white
        0xFF880E4FL to 0xFFB0BEC5L, // claret / grey
        0xFF3E2723L to 0xFFFFC107L, // brown / gold
        0xFF0D47A1L to 0xFFEF5350L, // navy / red
        0xFF9E9D24L to 0xFF37474FL, // olive / slate
        0xFF4527A0L to 0xFF80DEEAL  // violet / cyan
    )

    fun randomName(rng: Random): String =
        "${firstNames.random(rng)} ${lastNames.random(rng)}"

    fun newGame(seed: Long): GameState {
        val rng = Random(seed)
        val state = GameState(seed = seed)

        val towns = townNames.shuffled(rng)
        for (i in 0 until 14) {
            // Team quality spread: reputation 40..85
            val rep = 40 + (i * 45 / 13)
            val team = Team(
                id = i,
                name = "${towns[i]} ${suffixes.random(rng)}",
                short = towns[i].substring(0, 3).uppercase(),
                colorPrimary = kitColors[i].first,
                colorSecondary = kitColors[i].second,
                budget = 500_000 + rep * 25_000,
                reputation = rep
            )
            state.teams.add(team)
            generateSquad(state, team, rng)
            team.tactics.formation = Formation.entries.random(rng)
            team.tactics.startingXI = autoPickXI(state, team.id)
        }
        state.teams.shuffle(rng)
        state.fixtures = buildFixtures(state.teams.map { it.id }, rng)
        state.inbox.add("Welcome to Touchline. Season ${state.season} begins now — good luck, boss.")
        return state
    }

    fun generateSquad(state: GameState, team: Team, rng: Random) {
        fun add(pos: Position) {
            state.players.add(generatePlayer(state, team.id, pos, team.reputation, rng))
        }
        repeat(2) { add(Position.GK) }
        repeat(7) { add(Position.DF) }
        repeat(7) { add(Position.MF) }
        repeat(4) { add(Position.FW) }
    }

    fun generatePlayer(state: GameState, teamId: Int, pos: Position, rep: Int, rng: Random): Player {
        val quality = (rep + rng.nextInt(-12, 13)).coerceIn(30, 92)
        fun stat(core: Boolean): Int {
            val base = if (core) quality else quality - rng.nextInt(8, 25)
            return (base + rng.nextInt(-6, 7)).coerceIn(15, 95)
        }
        val attr = when (pos) {
            Position.GK -> Attributes(stat(false), stat(false), stat(false), stat(false), stat(false), stat(true), stat(true))
            Position.DF -> Attributes(stat(true), stat(false), stat(false), stat(false), stat(true), stat(true), 10)
            Position.MF -> Attributes(stat(false), stat(false), stat(true), stat(true), stat(false), stat(true), 10)
            Position.FW -> Attributes(stat(true), stat(true), stat(false), stat(true), stat(false), stat(false), 10)
        }
        val age = rng.nextInt(17, 34)
        val p = Player(
            id = state.nextPlayerId++,
            name = randomName(rng),
            age = age,
            position = pos,
            attr = attr,
            potential = (quality + rng.nextInt(0, 15)).coerceIn(40, 95),
            teamId = teamId
        )
        return p
    }

    /** Double round-robin fixtures via the circle method. */
    fun buildFixtures(teamIds: List<Int>, rng: Random): MutableList<Fixture> {
        val ids = teamIds.shuffled(rng).toMutableList()
        val n = ids.size
        val rounds = n - 1
        val fixtures = mutableListOf<Fixture>()
        for (r in 0 until rounds) {
            for (i in 0 until n / 2) {
                val a = ids[i]
                val b = ids[n - 1 - i]
                val home = if (r % 2 == 0) a else b
                val away = if (r % 2 == 0) b else a
                fixtures.add(Fixture(round = r + 1, homeId = home, awayId = away))
                fixtures.add(Fixture(round = r + 1 + rounds, homeId = away, awayId = home))
            }
            // rotate all but first
            val last = ids.removeAt(n - 1)
            ids.add(1, last)
        }
        return fixtures
    }
}
