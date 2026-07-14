package com.david.touchline.engine

import kotlin.random.Random

object Season {

    /** Simulates a single fixture quickly (no frames) and applies the result. */
    fun simulateFixture(state: GameState, fixture: Fixture) {
        val engine = MatchEngine(state, fixture, state.seed + fixture.round * 1000L + fixture.homeId, recordFrames = false)
        val res = engine.simulate()
        applyResult(state, fixture, res)
    }

    fun applyResult(state: GameState, fixture: Fixture, res: MatchResult) {
        fixture.played = true
        fixture.homeGoals = res.homeGoals
        fixture.awayGoals = res.awayGoals
        val rng = Random(state.seed + fixture.round * 31L + fixture.homeId)
        ratePlayers(state, state.team(fixture.homeId), res.homeGoals - res.awayGoals, res, rng)
        ratePlayers(state, state.team(fixture.awayId), res.awayGoals - res.homeGoals, res, rng)
        adjustMorale(state, fixture.homeId, res.homeGoals - res.awayGoals)
        adjustMorale(state, fixture.awayId, res.awayGoals - res.homeGoals)
    }

    private fun ratePlayers(state: GameState, team: Team, gd: Int, res: MatchResult, rng: Random) {
        val conceded = if (team.id == res.homeId) res.awayGoals else res.homeGoals
        for (id in team.tactics.startingXI) {
            val p = state.player(id) ?: continue
            p.seasonApps++
            val goals = res.events.count { it.type == EventType.GOAL && it.playerId == p.id }
            var rating = 6.1 + goals * 1.2 +
                (if (gd > 0) 0.5 else if (gd < 0) -0.5 else 0.0) +
                rng.nextDouble(-0.8, 0.8)
            if (p.position == Position.GK && conceded == 0) rating += 0.8
            rating = rating.coerceIn(4.0, 10.0)
            p.form = p.form * 0.7 + rating * 0.3
            p.seasonRatingSum += rating
            // Bookings accumulate towards a one-match ban
            val booked = res.events.any { it.type == EventType.CARD && it.playerId == p.id }
            if (booked) {
                p.seasonYellows++
                if (p.seasonYellows % 5 == 0) {
                    p.banMatches = 2   // decremented once this round -> misses the next
                    if (p.teamId == state.userTeamId) {
                        state.inbox.add("${p.name} is suspended for the next match (5 bookings).")
                    }
                }
            }
            // Knocks and injuries
            if (rng.nextDouble() < 0.025) {
                val weeks = rng.nextInt(1, 5)
                p.injuryWeeks = weeks + 1   // decremented once this round
                if (p.teamId == state.userTeamId) {
                    state.inbox.add("${p.name} has picked up an injury — out for $weeks week${if (weeks > 1) "s" else ""}.")
                }
            }
        }
    }

    private fun adjustMorale(state: GameState, teamId: Int, gd: Int) {
        val delta = when {
            gd > 0 -> 4
            gd == 0 -> 0
            else -> -4
        }
        for (p in state.squad(teamId)) p.morale = (p.morale + delta).coerceIn(20, 100)
    }

    /** Simulates every unplayed fixture in the current round, then advances. */
    fun completeRound(state: GameState) {
        val roundFixtures = state.fixtures.filter { it.round == state.round }
        for (f in roundFixtures) {
            if (!f.played) simulateFixture(state, f)
        }

        // Weekly wages come off every budget
        for (team in state.teams) {
            team.budget -= state.squad(team.id).sumOf { it.wage }
        }
        val user = state.userTeam()
        if (user.budget < 0 && state.round % 4 == 0) {
            state.inbox.add("The accounts are in the red. The board expects player sales to balance the books.")
        }

        // Injuries heal and bans are served
        for (p in state.players) {
            if (p.injuryWeeks > 0) p.injuryWeeks--
            if (p.banMatches > 0) p.banMatches--
        }

        // Board keeps an eye on the table
        val conf = boardConfidence(state)
        if (state.round % 6 == 0) {
            when {
                conf >= 85 -> state.inbox.add("The board is delighted with the club's progress this season.")
                conf <= 25 -> state.inbox.add("The board is concerned about results. Improvement is expected soon.")
            }
        }

        state.round++
        if (state.round > state.totalRounds) {
            endOfSeason(state)
        }
    }

    fun userFixture(state: GameState): Fixture? =
        state.fixtures.firstOrNull {
            it.round == state.round && !it.played &&
                (it.homeId == state.userTeamId || it.awayId == state.userTeamId)
        }

    fun endOfSeason(state: GameState) {
        val table = leagueTable(state)
        val rng = Random(state.seed + state.season)

        // Prize money and board messages
        table.forEachIndexed { idx, row ->
            val team = state.team(row.teamId)
            val prize = 1_200_000 - idx * 70_000
            team.budget += prize
            if (team.id == state.userTeamId) {
                val pos = idx + 1
                state.inbox.add(
                    "Season ${state.season} complete. ${team.name} finished ${ordinal(pos)} " +
                        "with ${row.points} points. Prize money: £${prize / 1000}k."
                )
                val champs = state.team(table.first().teamId)
                if (pos == 1) state.inbox.add("CHAMPIONS! The board is delighted. Celebrations all round.")
                else state.inbox.add("${champs.name} are champions this season.")
            }
        }

        // End-of-season awards
        val golden = state.players.maxByOrNull { it.seasonGoals }
        if (golden != null && golden.seasonGoals > 0) {
            state.inbox.add("Golden Boot: ${golden.name} (${state.team(golden.teamId).name}) with ${golden.seasonGoals} goals.")
        }
        val pots = state.players.filter { it.seasonApps >= 15 }
            .maxByOrNull { it.seasonRatingSum / it.seasonApps }
        if (pots != null) {
            val avg = pots.seasonRatingSum / pots.seasonApps
            state.inbox.add("Player of the Season: ${pots.name} (${state.team(pots.teamId).name}), average rating ${"%.2f".format(avg)}.")
        }

        // Player development, ageing, retirement
        val retiring = mutableListOf<Player>()
        for (p in state.players) {
            p.age++
            p.seasonGoals = 0
            p.seasonApps = 0
            p.seasonYellows = 0
            p.seasonRatingSum = 0.0
            p.form = 6.5
            p.injuryWeeks = 0
            p.banMatches = 0
            p.fitness = 100
            developPlayer(p, rng)
            if (p.age >= 34 && rng.nextInt(100) < (p.age - 32) * 25) retiring.add(p)
        }
        for (p in retiring) {
            state.players.remove(p)
            if (p.teamId == state.userTeamId) {
                state.inbox.add("${p.name} has retired at ${p.age}.")
            }
        }

        // Youth intake: each club receives 2 regens
        for (team in state.teams) {
            repeat(2) {
                val pos = listOf(Position.GK, Position.DF, Position.DF, Position.MF, Position.MF, Position.FW).random(rng)
                val youth = WorldGen.generatePlayer(state, team.id, pos, team.reputation - 15, rng)
                val young = youth.copy(age = rng.nextInt(16, 19))
                state.players.add(young)
            }
            // Refresh AI starting XIs
            if (team.id != state.userTeamId) {
                team.tactics.startingXI = autoPickXI(state, team.id)
            }
        }

        // AI transfer shuffle: a few sales between AI clubs
        aiTransferWindow(state, rng)

        state.season++
        state.round = 1
        state.fixtures = WorldGen.buildFixtures(state.teams.map { it.id }, rng)
        state.inbox.add("Season ${state.season} begins. Fixtures are out.")

        // Re-list transfer targets
        refreshTransferList(state, rng)
    }

    private fun developPlayer(p: Player, rng: Random) {
        fun bump(delta: Int) {
            val a = p.attr
            when (rng.nextInt(6)) {
                0 -> a.pace = (a.pace + delta).coerceIn(10, 95)
                1 -> a.shooting = (a.shooting + delta).coerceIn(10, 95)
                2 -> a.passing = (a.passing + delta).coerceIn(10, 95)
                3 -> a.dribbling = (a.dribbling + delta).coerceIn(10, 95)
                4 -> a.defending = (a.defending + delta).coerceIn(10, 95)
                else -> a.physical = (a.physical + delta).coerceIn(10, 95)
            }
            if (p.position == Position.GK) a.keeping = (a.keeping + delta).coerceIn(10, 95)
        }
        when {
            p.age <= 23 && p.overall < p.potential -> repeat(rng.nextInt(2, 5)) { bump(1) }
            p.age in 24..29 -> if (rng.nextBoolean()) bump(1) else bump(-1)
            else -> repeat(rng.nextInt(1, 4)) { bump(-1) }
        }
    }

    fun refreshTransferList(state: GameState, rng: Random) {
        for (p in state.players) p.transferListed = false
        for (team in state.teams) {
            if (team.id == state.userTeamId) continue
            val squad = state.squad(team.id)
            // List the weakest couple of players plus one random squad member
            squad.sortedBy { it.overall }.take(2).forEach { it.transferListed = true }
            squad.randomOrNull(rng)?.transferListed = true
        }
    }

    private fun aiTransferWindow(state: GameState, rng: Random) {
        val listed = state.players.filter { it.transferListed && it.teamId != state.userTeamId }
        for (p in listed.shuffled(rng).take(6)) {
            val buyer = state.teams.filter { it.id != p.teamId && it.id != state.userTeamId && it.budget >= p.value }
                .randomOrNull(rng) ?: continue
            val seller = state.team(p.teamId)
            buyer.budget -= p.value
            seller.budget += p.value
            p.teamId = buyer.id
            p.transferListed = false
        }
    }

    /** User buys a listed player. Returns an error message, or null on success. */
    fun buyPlayer(state: GameState, playerId: Int): String? {
        val p = state.player(playerId) ?: return "Player not found."
        val user = state.userTeam()
        if (p.teamId == user.id) return "Already in your squad."
        if (user.budget < p.value) return "Not enough budget."
        if (state.squad(user.id).size >= 30) return "Squad is full (30 max)."
        state.team(p.teamId).budget += p.value
        user.budget -= p.value
        p.teamId = user.id
        p.transferListed = false
        p.morale = 80
        state.inbox.add("${p.name} has joined ${user.name} for £${p.value / 1000}k.")
        return null
    }

    /** User sells a player at 90% of value. Returns an error message, or null on success. */
    fun sellPlayer(state: GameState, playerId: Int): String? {
        val p = state.player(playerId) ?: return "Player not found."
        val user = state.userTeam()
        if (p.teamId != user.id) return "Not your player."
        if (state.squad(user.id).size <= 14) return "Squad too small to sell (14 min)."
        val rng = Random(state.seed + playerId)
        val buyer = state.teams.filter { it.id != user.id }.random(rng)
        val fee = (p.value * 9) / 10
        user.budget += fee
        p.teamId = buyer.id
        p.transferListed = false
        user.tactics.startingXI.remove(p.id)
        state.inbox.add("${p.name} sold to ${buyer.name} for £${fee / 1000}k.")
        return null
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
