package com.david.touchline.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tick-based 2D match simulation, v2.
 *
 * Pitch coordinates: x in [0, 105], y in [0, 68] (metres).
 * Home attacks towards x = 105; away attacks towards x = 0.
 * One tick = one second of match time; 5400 ticks = 90 minutes.
 *
 * v2 additions:
 *  - Player roles (CB, FB, CM, WG, ST) with individual movement behaviour
 *  - Per-player variance and wobble so lines never move in lockstep
 *  - Wing play: wide players carry to the byline and cross; headers in the box
 *  - Set pieces: free kicks (direct or crossed), penalties, corners
 *  - Ball height for lofted deliveries (frame layout: [bx, by, bz, 22 * (x,y)])
 */

const val PITCH_W = 105.0
const val PITCH_H = 68.0
const val TICKS = 5400

data class MatchStats(
    var homeShots: Int = 0, var awayShots: Int = 0,
    var homeOnTarget: Int = 0, var awayOnTarget: Int = 0,
    var homePossession: Int = 0, var awayPossession: Int = 0,
    var homeCorners: Int = 0, var awayCorners: Int = 0
)

class MatchResult(
    val homeId: Int,
    val awayId: Int,
    var homeGoals: Int = 0,
    var awayGoals: Int = 0,
    val events: MutableList<MatchEvent> = mutableListOf(),
    val stats: MatchStats = MatchStats(),
    /** Each frame: [ballX, ballY, ballZ, p0x, p0y, ..., p21x, p21y]. Players 0-10 home, 11-21 away. */
    val frames: MutableList<FloatArray> = mutableListOf()
)

enum class Role { GK, CB, FB, CM, WG, ST }

private class SimPlayer(
    val player: Player,
    val globalIdx: Int,
    val home: Boolean,
    val role: Role,
    var anchorX: Double,
    var anchorY: Double,
    var x: Double,
    var y: Double,
    /** Individual movement personality so no two players track the ball identically. */
    val pullVar: Double,
    val wobblePhase: Double
) {
    val speed: Double get() = 2.2 + player.attr.pace / 38.0
    val isGK: Boolean get() = role == Role.GK
    /** Wide players live in a touchline lane. */
    val laneY: Double get() = if (anchorY < PITCH_H / 2) 7.0 else PITCH_H - 7.0
}

private enum class Phase { OPEN, SETUP }
private enum class SetPiece { NONE, KICKOFF, FREEKICK, PENALTY, CORNER }

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
    private var ballZ = 0.0
    private var owner: SimPlayer? = null

    // Ball in flight
    private var flightTicks = 0
    private var flightTotal = 0
    private var flightDX = 0.0
    private var flightDY = 0.0
    private var flightHeight = 0.0
    private var pendingReceiver: SimPlayer? = null
    private var headerOnArrival = false

    // Set pieces
    private var phase = Phase.OPEN
    private var setPiece = SetPiece.NONE
    private var spTicks = 0
    private var spX = 0.0
    private var spY = 0.0
    private var spHome = true       // attacking side for the set piece
    private var spKicker: SimPlayer? = null

    private var tick = 0

    // ------------------------------------------------------------- setup ----

    private fun mentalityShift(team: Team): Double = (team.tactics.mentality - 50) / 50.0 * 6.0

    private fun rolesFor(formation: Formation): List<Role> {
        val roles = mutableListOf(Role.GK)
        fun lineRoles(count: Int, wide: Role, central: Role) {
            for (i in 0 until count) {
                roles.add(if (count >= 4 && (i == 0 || i == count - 1)) wide else central)
            }
        }
        lineRoles(formation.df, Role.FB, Role.CB)
        lineRoles(formation.mf, Role.WG, Role.CM)
        repeat(formation.fw) { roles.add(Role.ST) }
        return roles
    }

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
        line(0.05, 1)
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
        for (side in listOf(true, false)) {
            val team = if (side) homeTeam else awayTeam
            val xi = resolveXI(team)
            val anchors = anchorsFor(team, side)
            val roles = rolesFor(team.tactics.formation)
            xi.forEachIndexed { i, p ->
                val (ax, ay) = anchors[i.coerceAtMost(anchors.size - 1)]
                val role = roles[i.coerceAtMost(roles.size - 1)]
                players.add(
                    SimPlayer(
                        p, if (side) i else 11 + i, side, role, ax, ay, ax, ay,
                        pullVar = rng.nextDouble(0.75, 1.25),
                        wobblePhase = rng.nextDouble(0.0, 6.28)
                    )
                )
            }
        }
    }

    private fun kickoff(toHome: Boolean) {
        ballX = PITCH_W / 2; ballY = PITCH_H / 2; ballZ = 0.0
        flightTicks = 0; pendingReceiver = null; headerOnArrival = false
        phase = Phase.OPEN; setPiece = SetPiece.NONE
        for (sp in players) { sp.x = sp.anchorX; sp.y = sp.anchorY }
        val side = players.filter { it.home == toHome && !it.isGK }
        owner = side.minByOrNull { hypot(it.anchorX - ballX, it.anchorY - ballY) }
        owner?.let { it.x = ballX; it.y = ballY }
    }

    private fun minuteOf(t: Int) = (t / 60) + 1

    private fun addEvent(type: EventType, sp: SimPlayer?, text: String, forHome: Boolean? = null) {
        val teamId = when {
            forHome != null -> if (forHome) fixture.homeId else fixture.awayId
            sp?.home != false -> fixture.homeId
            else -> fixture.awayId
        }
        result.events.add(MatchEvent(tick, minuteOf(tick), type, teamId, sp?.player?.id ?: -1, text))
    }

    private fun goalXFor(home: Boolean) = if (home) PITCH_W else 0.0
    private fun keeperOf(home: Boolean): SimPlayer = players.first { it.home == home && it.isGK }
    private fun teamOf(home: Boolean) = if (home) homeTeam else awayTeam

    /** Is (x, y) inside the penalty box that `attackingHome` is attacking? */
    private fun inAttackedBox(x: Double, y: Double, attackingHome: Boolean): Boolean {
        val inY = y > 13.85 && y < 54.15
        return if (attackingHome) x > PITCH_W - 16.5 && inY else x < 16.5 && inY
    }

    // ---------------------------------------------------------- main loop ----

    fun simulate(): MatchResult {
        setup()
        kickoff(toHome = true)
        var secondHalfStarted = false

        while (tick < TICKS) {
            if (!secondHalfStarted && tick >= TICKS / 2) {
                secondHalfStarted = true
                addEvent(EventType.INFO, null, "Half-time: ${homeTeam.short} ${result.homeGoals} - ${result.awayGoals} ${awayTeam.short}", forHome = true)
                kickoff(toHome = false)
            }

            stepTick()

            owner?.let {
                if (it.home) result.stats.homePossession++ else result.stats.awayPossession++
            }
            if (recordFrames && tick % 2 == 0) {
                val f = FloatArray(3 + 22 * 2)
                f[0] = ballX.toFloat(); f[1] = ballY.toFloat(); f[2] = ballZ.toFloat()
                for (sp in players) {
                    f[3 + sp.globalIdx * 2] = sp.x.toFloat()
                    f[4 + sp.globalIdx * 2] = sp.y.toFloat()
                }
                result.frames.add(f)
            }
            tick++
        }
        addEvent(EventType.INFO, null, "Full-time: ${homeTeam.short} ${result.homeGoals} - ${result.awayGoals} ${awayTeam.short}", forHome = true)
        return result
    }

    private fun stepTick() {
        maybeKickoff()
        if (phase == Phase.SETUP) {
            moveToSetPiecePositions()
            spTicks--
            if (spTicks <= 0) executeSetPiece()
            return
        }
        movePlayers()
        when {
            flightTicks > 0 -> stepFlight()
            owner != null -> carrierDecision()
            else -> looseBall()
        }
    }

    // ----------------------------------------------------------- movement ----

    private fun movePlayers() {
        val o = owner
        for (sp in players) {
            var tx: Double
            var ty: Double
            when {
                sp === o -> {
                    val wide = sp.role == Role.WG || sp.role == Role.FB
                    val inAttackHalf = if (sp.home) sp.x > PITCH_W * 0.45 else sp.x < PITCH_W * 0.55
                    if (wide && inAttackHalf) {
                        // Carry it down the wing towards the byline
                        tx = if (sp.home) PITCH_W - 3.0 else 3.0
                        ty = sp.laneY
                    } else {
                        tx = goalXFor(sp.home)
                        ty = ballY + (PITCH_H / 2 - ballY) * 0.02 + rng.nextDouble(-2.0, 2.0)
                    }
                }
                sp.isGK -> {
                    tx = sp.anchorX
                    ty = (PITCH_H / 2 + (ballY - PITCH_H / 2) * 0.25).coerceIn(24.0, 44.0)
                }
                else -> {
                    val attacking = o != null && o.home == sp.home
                    // Role-specific ball attraction, with per-player personality
                    val pull = when (sp.role) {
                        Role.CB -> if (attacking) 0.22 else 0.30
                        Role.FB -> if (attacking) 0.45 else 0.34
                        Role.CM -> if (attacking) 0.38 else 0.32
                        Role.WG -> if (attacking) 0.52 else 0.28
                        Role.ST -> if (attacking) 0.50 else 0.18
                        Role.GK -> 0.0
                    } * sp.pullVar
                    tx = sp.anchorX + (ballX - PITCH_W / 2) * pull
                    // Wide players hold their lane; central players compress towards the ball
                    ty = if (sp.role == Role.WG || sp.role == Role.FB) {
                        sp.laneY + (ballY - sp.laneY) * 0.10
                    } else {
                        sp.anchorY + (ballY - sp.anchorY) * 0.28
                    }
                    // Strikers push up onto the last line when their team attacks
                    if (attacking && sp.role == Role.ST) {
                        val lastDefX = players.filter { it.home != sp.home && !it.isGK }
                            .map { it.x }
                            .let { if (sp.home) it.maxOrNull() else it.minOrNull() } ?: tx
                        tx = if (sp.home) maxOf(tx, lastDefX - 1.0) else minOf(tx, lastDefX + 1.0)
                    }
                    // Gentle individual wobble so lines break up
                    ty += sin((tick + sp.wobblePhase * 60) * 0.045) * 1.6 * sp.pullVar
                    tx += sin((tick + sp.wobblePhase * 47) * 0.032) * 1.1 * sp.pullVar
                    // Nearest defenders press the carrier
                    if (o != null && o.home != sp.home) {
                        val d = hypot(sp.x - ballX, sp.y - ballY)
                        if (d < 13.0) { tx = ballX; ty = ballY }
                    }
                }
            }
            tx = tx.coerceIn(0.5, PITCH_W - 0.5)
            ty = ty.coerceIn(0.5, PITCH_H - 0.5)
            stepTowards(sp, tx, ty, if (sp === o) sp.speed * 0.75 else sp.speed)
        }
        owner?.let { ballX = it.x; ballY = it.y; ballZ = 0.0 }
    }

    private fun stepTowards(sp: SimPlayer, tx: Double, ty: Double, step: Double) {
        val dx = tx - sp.x
        val dy = ty - sp.y
        val dist = hypot(dx, dy)
        if (dist > 0.3) {
            val k = minOf(1.0, step / dist)
            sp.x += dx * k
            sp.y += dy * k
        }
    }

    // -------------------------------------------------------------- ball ----

    private fun startFlight(tx: Double, ty: Double, speed: Double, receiver: SimPlayer?, height: Double = 0.0, header: Boolean = false) {
        val dist = hypot(tx - ballX, ty - ballY)
        val t = maxOf(1, ceil(dist / speed).toInt())
        flightTicks = t
        flightTotal = t
        flightDX = (tx - ballX) / t
        flightDY = (ty - ballY) / t
        flightHeight = height
        pendingReceiver = receiver
        headerOnArrival = header
        owner = null
    }

    private fun stepFlight() {
        ballX = (ballX + flightDX).coerceIn(0.0, PITCH_W)
        ballY = (ballY + flightDY).coerceIn(0.0, PITCH_H)
        flightTicks--
        val progress = 1.0 - flightTicks.toDouble() / flightTotal
        ballZ = 4.0 * flightHeight * progress * (1 - progress)
        if (flightTicks <= 0) {
            ballZ = 0.0
            val recv = pendingReceiver
            val header = headerOnArrival
            pendingReceiver = null
            headerOnArrival = false
            if (recv != null) {
                owner = recv
                recv.x = ballX; recv.y = ballY
                if (header && inAttackedBox(ballX, ballY, recv.home)) {
                    attemptShot(recv, headed = true)
                }
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
                if (d < 20.0) stepTowards(sp, ballX, ballY, sp.speed)
            }
        }
    }

    // ---------------------------------------------------------- decisions ----

    private fun carrierDecision() {
        val c = owner ?: return
        val goalX = goalXFor(c.home)
        val distGoal = hypot(goalX - c.x, PITCH_H / 2 - c.y)
        val pressure = players.count { it.home != c.home && hypot(it.x - c.x, it.y - c.y) < 3.5 }
        val mentality = teamOf(c.home).tactics.mentality
        val mFactor = 0.75 + mentality / 200.0

        if (c.isGK) {
            if (rng.nextDouble() < 0.5) passBall(c)
            return
        }

        // Tackle attempt against the carrier -> may win the ball or give away a foul
        val tackler = players.filter { it.home != c.home && !it.isGK }
            .minByOrNull { hypot(it.x - c.x, it.y - c.y) }
        if (tackler != null && hypot(tackler.x - c.x, tackler.y - c.y) < 2.2) {
            val def = tackler.player.attr.defending.toDouble()
            val dri = c.player.attr.dribbling.toDouble()
            if (rng.nextDouble() < def / (def + dri) * 0.22) {
                if (rng.nextDouble() < 0.12) {
                    // Foul on the carrier: free kick or penalty to the carrier's team
                    if (rng.nextDouble() < 0.30) {
                        addEvent(EventType.CARD, tackler, "${tackler.player.name} is booked for the foul")
                    }
                    awardFreeKickOrPenalty(c)
                    return
                } else {
                    owner = tackler
                    return
                }
            }
        }

        // Wide players near the byline whip in a cross
        val wide = abs(c.y - PITCH_H / 2) > 18.0
        val nearByline = if (c.home) c.x > PITCH_W - 24.0 else c.x < 24.0
        if (wide && nearByline && (c.role == Role.WG || c.role == Role.FB || rng.nextDouble() < 0.3)) {
            if (rng.nextDouble() < 0.30) {
                deliverCross(c)
                return
            }
        }

        // Shooting (central positions mostly)
        if (distGoal < 28.0 && !wide) {
            val homeBoost = if (c.home) 1.08 else 1.0
            val shootProb = ((30.0 - distGoal) / 30.0) * 0.045 * mFactor * homeBoost *
                (0.6 + c.player.attr.shooting / 200.0) / (1.0 + pressure * 0.6)
            if (rng.nextDouble() < shootProb) {
                attemptShot(c)
                return
            }
        }

        // Passing
        val basePassProb = 0.22 + pressure * 0.15
        if (rng.nextDouble() < basePassProb) passBall(c)
    }

    private fun passBall(c: SimPlayer) {
        val mates = players.filter { it.home == c.home && it !== c && !it.isGK }
        if (mates.isEmpty()) return
        val goalX = goalXFor(c.home)
        val candidates = mates.filter { hypot(it.x - c.x, it.y - c.y) < 42.0 }
        if (candidates.isEmpty()) return
        // Sometimes deliberately spread play to a wide runner
        val target = if (rng.nextDouble() < 0.25) {
            candidates.filter { it.role == Role.WG || it.role == Role.FB }.randomOrNull(rng)
                ?: candidates.random(rng)
        } else {
            candidates.maxByOrNull { -abs(it.x - goalX) + rng.nextDouble(0.0, 30.0) } ?: return
        }

        val dist = hypot(target.x - c.x, target.y - c.y)
        val nearOpp = players.count {
            it.home != c.home && hypot(it.x - (c.x + target.x) / 2, it.y - (c.y + target.y) / 2) < 6.0
        }
        val pas = c.player.attr.passing.toDouble()
        val success = (0.62 + pas / 350.0 - nearOpp * 0.09 - dist / 260.0).coerceIn(0.30, 0.96)
        val height = if (dist > 28.0) 1.5 else 0.0
        if (rng.nextDouble() < success) {
            startFlight(target.x, target.y, 14.0, target, height)
        } else {
            val interceptor = players.filter { it.home != c.home }
                .minByOrNull { hypot(it.x - target.x, it.y - target.y) }
            startFlight(
                target.x + rng.nextDouble(-6.0, 6.0),
                target.y + rng.nextDouble(-6.0, 6.0),
                14.0, interceptor, height
            )
        }
    }

    private fun deliverCross(c: SimPlayer) {
        val boxX = if (c.home) PITCH_W - rng.nextDouble(4.0, 12.0) else rng.nextDouble(4.0, 12.0)
        val boxY = PITCH_H / 2 + rng.nextDouble(-9.0, 9.0)
        val attackers = players.filter {
            it.home == c.home && it !== c && (it.role == Role.ST || it.role == Role.CM)
        }
        val defenders = players.filter { it.home != c.home && !it.isGK }
        val targetMate = attackers.minByOrNull { hypot(it.x - boxX, it.y - boxY) }
        val nearestDef = defenders.minByOrNull { hypot(it.x - boxX, it.y - boxY) }
        val pas = c.player.attr.passing.toDouble()
        val winChance = (0.30 + pas / 600.0).coerceIn(0.25, 0.42)
        val receiver = if (targetMate != null && rng.nextDouble() < winChance) targetMate else nearestDef
        val header = receiver === targetMate
        startFlight(boxX, boxY, 16.0, receiver, height = rng.nextDouble(3.0, 5.0), header = header)
    }

    // -------------------------------------------------------------- shots ----

    private fun attemptShot(c: SimPlayer, headed: Boolean = false, freeKick: Boolean = false, penalty: Boolean = false) {
        val keeper = keeperOf(!c.home)
        val goalX = goalXFor(c.home)
        val distGoal = hypot(goalX - c.x, PITCH_H / 2 - c.y)
        val pressure = players.count { it.home != c.home && hypot(it.x - c.x, it.y - c.y) < 3.5 }
        if (c.home) result.stats.homeShots++ else result.stats.awayShots++

        val shootSkill = if (headed) (c.player.attr.shooting + c.player.attr.physical) / 2.0 * 0.8
        else c.player.attr.shooting.toDouble()
        val kp = keeper.player.attr.keeping.toDouble()

        var goalProb: Double
        var onTarget: Boolean
        when {
            penalty -> {
                goalProb = (0.70 + (shootSkill - kp) / 400.0).coerceIn(0.55, 0.90)
                onTarget = rng.nextDouble() < 0.92
            }
            freeKick -> {
                goalProb = ((shootSkill / (shootSkill + kp * 1.35)) * (1.05 - distGoal / 34.0) * 0.55)
                    .coerceIn(0.02, 0.30)
                onTarget = rng.nextDouble() < 0.45 + shootSkill / 400.0
            }
            else -> {
                goalProb = (shootSkill / (shootSkill + kp * 1.35)) * (1.05 - distGoal / 34.0)
                if (pressure > 0) goalProb *= 0.55
                if (headed) goalProb *= 0.50
                goalProb = goalProb.coerceIn(0.02, 0.42)
                onTarget = rng.nextDouble() < 0.55 + shootSkill / 400.0
            }
        }

        if (onTarget) {
            if (c.home) result.stats.homeOnTarget++ else result.stats.awayOnTarget++
            if (rng.nextDouble() < goalProb) {
                if (c.home) result.homeGoals++ else result.awayGoals++
                c.player.seasonGoals++
                val how = when {
                    penalty -> " from the penalty spot"
                    freeKick -> " direct from the free kick"
                    headed -> " with a header"
                    else -> ""
                }
                addEvent(EventType.GOAL, c, "GOAL! ${c.player.name} scores$how for ${teamOf(c.home).name}!")
                startFlight(goalX, PITCH_H / 2, 20.0, null, height = 0.5)
                pendingKickoffToHome = !c.home
                return
            }
            // Saved -> chance of a corner
            addEvent(EventType.SAVE, c, "${keeper.player.name} saves from ${c.player.name}")
            if (!penalty && rng.nextDouble() < 0.28) {
                awardCorner(c.home)
            } else {
                giveGoalKick(keeper)
            }
        } else {
            addEvent(EventType.CHANCE, c, if (headed) "${c.player.name} heads it over" else "${c.player.name} shoots wide")
            if (!penalty && rng.nextDouble() < 0.12) awardCorner(c.home) else giveGoalKick(keeper)
        }
    }

    private var pendingKickoffToHome: Boolean? = null

    private fun giveGoalKick(keeper: SimPlayer) {
        owner = keeper
        keeper.x = keeper.anchorX; keeper.y = keeper.anchorY
        ballX = keeper.x; ballY = keeper.y; ballZ = 0.0
    }

    // --------------------------------------------------------- set pieces ----

    private fun awardFreeKickOrPenalty(fouled: SimPlayer) {
        if (inAttackedBox(fouled.x, fouled.y, fouled.home) && rng.nextDouble() < 0.35) {
            setPiece = SetPiece.PENALTY
            spX = if (fouled.home) PITCH_W - 11.0 else 11.0
            spY = PITCH_H / 2
            addEvent(EventType.PENALTY, fouled, "PENALTY to ${teamOf(fouled.home).name}! ${fouled.player.name} is brought down in the box")
        } else {
            setPiece = SetPiece.FREEKICK
            spX = fouled.x
            spY = fouled.y
            addEvent(EventType.FREEKICK, fouled, "Free kick to ${teamOf(fouled.home).name}")
        }
        spHome = fouled.home
        beginSetup()
    }

    private fun awardCorner(attackingHome: Boolean) {
        setPiece = SetPiece.CORNER
        spHome = attackingHome
        spX = if (attackingHome) PITCH_W - 0.5 else 0.5
        spY = if (rng.nextBoolean()) 0.5 else PITCH_H - 0.5
        if (attackingHome) result.stats.homeCorners++ else result.stats.awayCorners++
        addEvent(EventType.CORNER, null, "Corner to ${teamOf(attackingHome).name}", forHome = attackingHome)
        beginSetup()
    }

    private fun beginSetup() {
        phase = Phase.SETUP
        spTicks = 10
        flightTicks = 0; pendingReceiver = null; headerOnArrival = false
        owner = null
        ballX = spX; ballY = spY; ballZ = 0.0
        // Best available taker: shooter for pens/FKs, crosser for corners
        val side = players.filter { it.home == spHome && !it.isGK }
        spKicker = when (setPiece) {
            SetPiece.CORNER -> side.maxByOrNull { it.player.attr.passing }
            else -> side.maxByOrNull { it.player.attr.shooting }
        }
    }

    private fun moveToSetPiecePositions() {
        val kicker = spKicker
        val goalX = goalXFor(spHome)
        val boxCentreX = if (spHome) PITCH_W - 9.0 else 9.0
        var atkSlot = 0
        var defSlot = 0
        for (sp in players) {
            val (tx, ty) = when {
                sp === kicker -> spX to spY
                sp.isGK -> sp.anchorX to PITCH_H / 2
                setPiece == SetPiece.PENALTY ->
                    // Everyone else waits around the edge of the box
                    (if (sp.home == spHome) boxCentreX + (if (spHome) -14.0 else 14.0)
                    else boxCentreX + (if (spHome) -17.0 else 17.0)) to (10.0 + (sp.globalIdx % 10) * 5.0)
                sp.home == spHome && (sp.role == Role.ST || sp.role == Role.CB || sp.role == Role.CM) -> {
                    // Attackers crowd the box
                    atkSlot++
                    (boxCentreX + rngStable(sp, 6.0)) to (PITCH_H / 2 - 12.0 + atkSlot * 5.0)
                }
                sp.home != spHome && !sp.isGK && (sp.role == Role.CB || sp.role == Role.CM || sp.role == Role.FB) -> {
                    // Defenders mark inside the box
                    defSlot++
                    (boxCentreX + (if (spHome) 2.0 else -2.0) + rngStable(sp, 4.0)) to (PITCH_H / 2 - 12.0 + defSlot * 4.5)
                }
                else -> (sp.anchorX + (goalX - PITCH_W / 2) * 0.3) to sp.anchorY
            }
            stepTowards(sp, tx.coerceIn(0.5, PITCH_W - 0.5), ty.coerceIn(0.5, PITCH_H - 0.5), sp.speed * 1.2)
        }
    }

    /** Deterministic per-player offset so set-piece positions don't jitter every tick. */
    private fun rngStable(sp: SimPlayer, range: Double): Double =
        ((sp.wobblePhase / 6.28) - 0.5) * 2 * range

    private fun executeSetPiece() {
        phase = Phase.OPEN
        val kicker = spKicker
        val type = setPiece
        setPiece = SetPiece.NONE
        if (kicker == null) { owner = null; return }
        kicker.x = spX; kicker.y = spY
        owner = kicker

        when (type) {
            SetPiece.PENALTY -> {
                addEvent(EventType.INFO, kicker, "${kicker.player.name} steps up...")
                attemptShot(kicker, penalty = true)
            }
            SetPiece.FREEKICK -> {
                val distGoal = hypot(goalXFor(spHome) - spX, PITCH_H / 2 - spY)
                if (distGoal < 26.0 && rng.nextDouble() < 0.6) {
                    attemptShot(kicker, freeKick = true)
                } else {
                    deliverCross(kicker)
                }
            }
            SetPiece.CORNER -> deliverCross(kicker)
            else -> {}
        }
    }

    // Restart after goals once the celebration flight lands
    private fun maybeKickoff() {
        val toHome = pendingKickoffToHome ?: return
        if (flightTicks <= 0) {
            pendingKickoffToHome = null
            kickoff(toHome)
        }
    }
}
