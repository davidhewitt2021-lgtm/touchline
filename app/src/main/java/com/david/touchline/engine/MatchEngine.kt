package com.david.touchline.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A tick-based 2D match simulation.
 *
 * Pitch coordinates: x in [0, 105], y in [0, 68] (metres).
 * Home attacks towards x = 105; away attacks towards x = 0.
 * One tick = one second of match time; 5400 ticks = 90 minutes.
 * When [recordFrames] is true, a positional snapshot is stored every
 * 2 ticks so the match can be replayed visually.
 */

const val PITCH_W = 105.0
const val PITCH_H = 68.0
const val TICKS = 5400

data class MatchStats(
    var homeShots: Int = 0, var awayShots: Int = 0,
    var homeOnTarget: Int = 0, var awayOnTarget: Int = 0,
    var homePossession: Int = 0, var awayPossession: Int = 0
)

class MatchResult(
    val homeId: Int,
    val awayId: Int,
    var homeGoals: Int = 0,
    var awayGoals: Int = 0,
    val events: MutableList<MatchEvent> = mutableListOf(),
    val stats: MatchStats = MatchStats(),
    /** Each frame: [ballX, ballY, p0x, p0y, ..., p21x, p21y]. Players 0-10 home, 11-21 away. */
    val frames: MutableList<FloatArray> = mutableListOf()
)

private class SimPlayer(
    val player: Player,
    val globalIdx: Int,
    val home: Boolean,
    var anchorX: Double,
    var anchorY: Double,
    var x: Double,
    var y: Double
) {
    val speed: Double get() = 2.2 + player.attr.pace / 38.0
    val isGK: Boolean get() = player.position == Position.GK
}

class MatchEngine(
    private val state: GameState,
    private val fixture: Fixture,
    seed: Long,
    private val recordFrames: Boolean
) {
    private val rng = Random(seed)
    private val homeTeam = state.team(fixture.homeId)
    private val awayTeam = state.team(fixture.awayId)
    private val result = MatchResult(fixture.homeId, fixture.awayId)

    private val players = mutableListOf<SimPlayer>()
    private var ballX = PITCH_W / 2
    private var ballY = PITCH_H / 2
    private var owner: SimPlayer? = null

    // Ball in flight (pass or shot travelling)
    private var flightTicks = 0
    private var flightDX = 0.0
    private var flightDY = 0.0
    private var pendingReceiver: SimPlayer? = null

    private var tick = 0

    private fun mentalityShift(team: Team): Double = (team.tactics.mentality - 50) / 50.0 * 7.0

    private fun anchorsFor(team: Team, home: Boolean): List<Pair<Double, Double>> {
        val f = team.tactics.formation
        val shift = mentalityShift(team)
        val anchors = mutableListOf<Pair<Double, Double>>()
        fun line(xFrac: Double, count: Int) {
            for (i in 0 until count) {
                val y = PITCH_H * (i + 1) / (count + 1)
                var x = PITCH_W * xFrac + shift
                x = x.coerceIn(2.0, PITCH_W - 2.0)
                anchors.add(if (home) x to y else (PITCH_W - x) to y)
            }
        }
        line(0.05, 1)          // GK
        line(0.24, f.df)
        line(0.50, f.mf)
        line(0.76, f.fw)
        return anchors
    }

    private fun resolveXI(team: Team): List<Player> {
        var ids = team.tactics.startingXI
        val squadIds = state.squad(team.id).map { it.id }.toSet()
        if (ids.size != 11 || !ids.all { it in squadIds }) {
            ids = autoPickXI(state, team.id)
            team.tactics.startingXI = ids
        }
        return ids.mapNotNull { state.player(it) }
    }

    private fun setup() {
        players.clear()
        val homeXI = resolveXI(homeTeam)
        val awayXI = resolveXI(awayTeam)
        val homeAnchors = anchorsFor(homeTeam, true)
        val awayAnchors = anchorsFor(awayTeam, false)
        homeXI.forEachIndexed { i, p ->
            val (ax, ay) = homeAnchors[i.coerceAtMost(homeAnchors.size - 1)]
            players.add(SimPlayer(p, i, true, ax, ay, ax, ay))
        }
        awayXI.forEachIndexed { i, p ->
            val (ax, ay) = awayAnchors[i.coerceAtMost(awayAnchors.size - 1)]
            players.add(SimPlayer(p, 11 + i, false, ax, ay, ax, ay))
        }
    }

    private fun kickoff(toHome: Boolean) {
        ballX = PITCH_W / 2; ballY = PITCH_H / 2
        flightTicks = 0; pendingReceiver = null
        // Everyone back to anchors
        for (sp in players) { sp.x = sp.anchorX; sp.y = sp.anchorY }
        val side = players.filter { it.home == toHome && !it.isGK }
        owner = side.minByOrNull { hypot(it.anchorX - ballX, it.anchorY - ballY) }
        owner?.let { it.x = ballX; it.y = ballY }
    }

    private fun minuteOf(t: Int) = (t / 60) + 1

    private fun addEvent(type: EventType, sp: SimPlayer?, text: String) {
        val teamId = if (sp?.home != false) fixture.homeId else fixture.awayId
        result.events.add(
            MatchEvent(tick, minuteOf(tick), type, teamId, sp?.player?.id ?: -1, text)
        )
    }

    private fun goalXFor(home: Boolean) = if (home) PITCH_W else 0.0

    private fun teamQuality(home: Boolean): Double =
        players.filter { it.home == home }.map { it.player.overall }.average()

    private fun keeperOf(home: Boolean): SimPlayer =
        players.first { it.home == home && it.isGK }

    fun simulate(): MatchResult {
        setup()
        kickoff(toHome = true)
        var secondHalfStarted = false

        while (tick < TICKS) {
            if (!secondHalfStarted && tick >= TICKS / 2) {
                secondHalfStarted = true
                addEvent(EventType.INFO, null, "Half-time: ${homeTeam.short} ${result.homeGoals} - ${result.awayGoals} ${awayTeam.short}")
                kickoff(toHome = false)
            }

            stepTick()

            owner?.let {
                if (it.home) result.stats.homePossession++ else result.stats.awayPossession++
            }
            if (recordFrames && tick % 2 == 0) {
                val f = FloatArray(2 + 22 * 2)
                f[0] = ballX.toFloat(); f[1] = ballY.toFloat()
                for (sp in players) {
                    f[2 + sp.globalIdx * 2] = sp.x.toFloat()
                    f[3 + sp.globalIdx * 2] = sp.y.toFloat()
                }
                result.frames.add(f)
            }
            tick++
        }
        addEvent(EventType.INFO, null, "Full-time: ${homeTeam.short} ${result.homeGoals} - ${result.awayGoals} ${awayTeam.short}")
        return result
    }

    private fun stepTick() {
        if (kickoffAfter != KickoffSide.NONE && flightTicks <= 0) {
            handleKickoffIfNeeded()
        }
        movePlayers()
        when {
            flightTicks > 0 -> stepFlight()
            owner != null -> carrierDecision()
            else -> looseBall()
        }
    }

    private fun movePlayers() {
        val o = owner
        for (sp in players) {
            var tx: Double
            var ty: Double
            when {
                sp === o -> {
                    // Carrier advances towards the opposition goal, drifting off defenders
                    tx = goalXFor(sp.home)
                    ty = ballY + (34.0 - ballY) * 0.02 + rng.nextDouble(-2.0, 2.0)
                }
                sp.isGK -> {
                    tx = sp.anchorX
                    ty = (PITCH_H / 2 + (ballY - PITCH_H / 2) * 0.25).coerceIn(24.0, 44.0)
                }
                else -> {
                    val attacking = o != null && o.home == sp.home
                    val pull = if (attacking) 0.38 else 0.30
                    tx = sp.anchorX + (ballX - PITCH_W / 2) * pull
                    ty = sp.anchorY + (ballY - sp.anchorY) * 0.25
                    // Nearest defender presses the carrier
                    if (o != null && o.home != sp.home) {
                        val d = hypot(sp.x - ballX, sp.y - ballY)
                        if (d < 14.0) { tx = ballX; ty = ballY }
                    }
                }
            }
            tx = tx.coerceIn(0.5, PITCH_W - 0.5)
            ty = ty.coerceIn(0.5, PITCH_H - 0.5)
            val dx = tx - sp.x
            val dy = ty - sp.y
            val dist = hypot(dx, dy)
            val step = if (sp === o) sp.speed * 0.75 else sp.speed
            if (dist > 0.3) {
                val k = minOf(1.0, step / dist)
                sp.x += dx * k
                sp.y += dy * k
            }
        }
        owner?.let { ballX = it.x; ballY = it.y }
    }

    private fun stepFlight() {
        ballX = (ballX + flightDX).coerceIn(0.0, PITCH_W)
        ballY = (ballY + flightDY).coerceIn(0.0, PITCH_H)
        flightTicks--
        if (flightTicks <= 0) {
            val recv = pendingReceiver
            pendingReceiver = null
            if (recv != null) {
                owner = recv
                recv.x = ballX; recv.y = ballY
            } else {
                owner = null
            }
        }
    }

    private fun looseBall() {
        val nearest = players.minByOrNull { hypot(it.x - ballX, it.y - ballY) } ?: return
        if (hypot(nearest.x - ballX, nearest.y - ballY) < 1.5) {
            owner = nearest
        } else {
            for (sp in players) {
                val d = hypot(sp.x - ballX, sp.y - ballY)
                if (d < 20.0) {
                    val k = minOf(1.0, sp.speed / d)
                    sp.x += (ballX - sp.x) * k
                    sp.y += (ballY - sp.y) * k
                }
            }
        }
    }

    private fun startFlight(tx: Double, ty: Double, speed: Double, receiver: SimPlayer?) {
        val dist = hypot(tx - ballX, ty - ballY)
        val t = maxOf(1, ceil(dist / speed).toInt())
        flightTicks = t
        flightDX = (tx - ballX) / t
        flightDY = (ty - ballY) / t
        pendingReceiver = receiver
        owner = null
    }

    private fun carrierDecision() {
        val c = owner ?: return
        val goalX = goalXFor(c.home)
        val distGoal = hypot(goalX - c.x, PITCH_H / 2 - c.y)
        val pressure = players.count { it.home != c.home && hypot(it.x - c.x, it.y - c.y) < 3.5 }
        val mentality = (if (c.home) homeTeam else awayTeam).tactics.mentality
        val mFactor = 0.75 + mentality / 200.0

        // Goalkeeper just distributes
        if (c.isGK) {
            if (rng.nextDouble() < 0.5) passBall(c)
            return
        }

        // Tackle attempt against the carrier
        val tackler = players.filter { it.home != c.home && !it.isGK }
            .minByOrNull { hypot(it.x - c.x, it.y - c.y) }
        if (tackler != null && hypot(tackler.x - c.x, tackler.y - c.y) < 2.2) {
            val def = tackler.player.attr.defending.toDouble()
            val dri = c.player.attr.dribbling.toDouble()
            if (rng.nextDouble() < def / (def + dri) * 0.22) {
                if (rng.nextDouble() < 0.12) {
                    addEvent(EventType.CARD, tackler, "${tackler.player.name} is booked for a late challenge")
                } else {
                    owner = tackler
                    return
                }
            }
        }

        // Shooting
        if (distGoal < 28.0) {
            val homeBoost = if (c.home) 1.08 else 1.0
            val shootProb = ((30.0 - distGoal) / 30.0) * 0.05 * mFactor * homeBoost *
                (0.6 + c.player.attr.shooting / 200.0) / (1.0 + pressure * 0.6)
            if (rng.nextDouble() < shootProb) {
                attemptShot(c, distGoal, pressure)
                return
            }
        }

        // Passing
        val basePassProb = 0.22 + pressure * 0.15
        if (rng.nextDouble() < basePassProb) {
            passBall(c)
        }
        // otherwise: keep dribbling (movement handled in movePlayers)
    }

    private fun attemptShot(c: SimPlayer, distGoal: Double, pressure: Int) {
        val keeper = keeperOf(!c.home)
        if (c.home) result.stats.homeShots++ else result.stats.awayShots++
        val sh = c.player.attr.shooting.toDouble()
        val kp = keeper.player.attr.keeping.toDouble()
        var goalProb = (sh / (sh + kp * 1.35)) * (1.05 - distGoal / 34.0)
        if (pressure > 0) goalProb *= 0.55
        goalProb = goalProb.coerceIn(0.02, 0.42)
        val onTarget = rng.nextDouble() < 0.55 + sh / 400.0
        val goalX = goalXFor(c.home)

        if (onTarget) {
            if (c.home) result.stats.homeOnTarget++ else result.stats.awayOnTarget++
            if (rng.nextDouble() < goalProb) {
                if (c.home) result.homeGoals++ else result.awayGoals++
                c.player.seasonGoals++
                addEvent(EventType.GOAL, c, "GOAL! ${c.player.name} scores for ${(if (c.home) homeTeam else awayTeam).name}!")
                // Fly the ball into the net, then restart
                startFlight(goalX, PITCH_H / 2, 18.0, null)
                flightTicks = maxOf(flightTicks, 2)
                kickoffAfter = if (c.home) KickoffSide.AWAY else KickoffSide.HOME
                return
            } else {
                addEvent(EventType.SAVE, c, "${keeper.player.name} saves from ${c.player.name}")
                owner = keeper
                keeper.x = keeper.anchorX; keeper.y = keeper.anchorY
                ballX = keeper.x; ballY = keeper.y
                return
            }
        } else {
            addEvent(EventType.CHANCE, c, "${c.player.name} shoots wide")
            // Goal kick
            owner = keeper
            keeper.x = keeper.anchorX; keeper.y = keeper.anchorY
            ballX = keeper.x; ballY = keeper.y
        }
    }

    private enum class KickoffSide { NONE, HOME, AWAY }
    private var kickoffAfter = KickoffSide.NONE

    private fun passBall(c: SimPlayer) {
        val mates = players.filter { it.home == c.home && it !== c && !it.isGK }
        if (mates.isEmpty()) return
        // Prefer teammates further upfield, within range
        val goalX = goalXFor(c.home)
        val candidates = mates.filter { hypot(it.x - c.x, it.y - c.y) < 42.0 }
        if (candidates.isEmpty()) return
        val target = candidates.maxByOrNull {
            -abs(it.x - goalX) + rng.nextDouble(0.0, 30.0)
        } ?: return

        val dist = hypot(target.x - c.x, target.y - c.y)
        val nearOpp = players.count {
            it.home != c.home && hypot(it.x - (c.x + target.x) / 2, it.y - (c.y + target.y) / 2) < 6.0
        }
        val pas = c.player.attr.passing.toDouble()
        val success = (0.62 + pas / 350.0 - nearOpp * 0.09 - dist / 260.0).coerceIn(0.30, 0.96)
        if (rng.nextDouble() < success) {
            startFlight(target.x, target.y, 14.0, target)
        } else {
            val interceptor = players.filter { it.home != c.home }
                .minByOrNull { hypot(it.x - target.x, it.y - target.y) }
            startFlight(
                target.x + rng.nextDouble(-6.0, 6.0),
                target.y + rng.nextDouble(-6.0, 6.0),
                14.0,
                interceptor
            )
        }
    }

    init {
        // no-op
    }

    // Handle post-goal kickoff inside the main loop
    fun handleKickoffIfNeeded() {
        when (kickoffAfter) {
            KickoffSide.HOME -> kickoff(true)
            KickoffSide.AWAY -> kickoff(false)
            KickoffSide.NONE -> return
        }
        kickoffAfter = KickoffSide.NONE
    }
}
