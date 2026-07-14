package com.david.touchline.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tick-based 2D match simulation, v3.
 *
 * Pitch coordinates: x in [0, 105], y in [0, 68] (metres).
 * Home attacks towards x = 105; away attacks towards x = 0.
 * One tick = one second of match time; 5400 ticks = 90 minutes.
 *
 * v3: set pieces are properly choreographed. When one is awarded, every
 * player is assigned an explicit target (computed once in beginSetup) and
 * jogs there during a setup phase: defensive walls for free kicks, the
 * penalty-arc line-up for penalties, man-marking in the box for corners,
 * throw-ins when the ball goes out wide, and full kick-off line-ups after
 * goals and at each half.
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
    val pullVar: Double,
    val wobblePhase: Double
) {
    val speed: Double get() = 2.2 + player.attr.pace / 38.0
    val isGK: Boolean get() = role == Role.GK
    val laneY: Double get() = if (anchorY < PITCH_H / 2) 7.0 else PITCH_H - 7.0
}

private enum class Phase { OPEN, SETUP }
private enum class SetPiece { NONE, KICKOFF, FREEKICK, PENALTY, CORNER, THROWIN }

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
    private var spHome = true
    private var spKicker: SimPlayer? = null
    private val spTargets = HashMap<Int, Pair<Double, Double>>()
    private var pendingKickoffToHome: Boolean? = null

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
        val invalid = ids.size != 11 || !ids.all { it in squadIds } ||
            ids.any { state.player(it)?.available == false }
        if (invalid) {
            ids = autoPickXI(state, team.id)
            team.tactics.startingXI = ids
        }
        return ids.mapNotNull { state.player(it) }
    }

    private fun setupPlayers() {
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

    private fun inAttackedBox(x: Double, y: Double, attackingHome: Boolean): Boolean {
        val inY = y > 13.85 && y < 54.15
        return if (attackingHome) x > PITCH_W - 16.5 && inY else x < 16.5 && inY
    }

    // ---------------------------------------------------------- main loop ----

    fun simulate(): MatchResult {
        setupPlayers()
        beginKickoff(toHome = true)
        var secondHalfStarted = false

        while (tick < TICKS) {
            if (!secondHalfStarted && tick >= TICKS / 2) {
                secondHalfStarted = true
                addEvent(EventType.INFO, null, "Half-time: ${homeTeam.short} ${result.homeGoals} - ${result.awayGoals} ${awayTeam.short}", forHome = true)
                beginKickoff(toHome = false)
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
        // Restart with a proper kick-off once the post-goal flight has landed
        pendingKickoffToHome?.let { toHome ->
            if (flightTicks <= 0) {
                pendingKickoffToHome = null
                beginKickoff(toHome)
            }
        }
        if (phase == Phase.SETUP) {
            for (sp in players) {
                val (tx, ty) = spTargets[sp.globalIdx] ?: (sp.anchorX to sp.anchorY)
                stepTowards(sp, tx, ty, sp.speed * 1.15)
            }
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
                    val pull = when (sp.role) {
                        Role.CB -> if (attacking) 0.22 else 0.30
                        Role.FB -> if (attacking) 0.45 else 0.34
                        Role.CM -> if (attacking) 0.38 else 0.32
                        Role.WG -> if (attacking) 0.52 else 0.28
                        Role.ST -> if (attacking) 0.50 else 0.18
                        Role.GK -> 0.0
                    } * sp.pullVar
                    tx = sp.anchorX + (ballX - PITCH_W / 2) * pull
                    ty = if (sp.role == Role.WG || sp.role == Role.FB) {
                        sp.laneY + (ballY - sp.laneY) * 0.10
                    } else {
                        sp.anchorY + (ballY - sp.anchorY) * 0.28
                    }
                    if (attacking && sp.role == Role.ST) {
                        val lastDefX = players.filter { it.home != sp.home && !it.isGK }
                            .map { it.x }
                            .let { if (sp.home) it.maxOrNull() else it.minOrNull() } ?: tx
                        tx = if (sp.home) maxOf(tx, lastDefX - 1.0) else minOf(tx, lastDefX + 1.0)
                    }
                    ty += sin((tick + sp.wobblePhase * 60) * 0.045) * 1.6 * sp.pullVar
                    tx += sin((tick + sp.wobblePhase * 47) * 0.032) * 1.1 * sp.pullVar
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

        val tackler = players.filter { it.home != c.home && !it.isGK }
            .minByOrNull { hypot(it.x - c.x, it.y - c.y) }
        if (tackler != null && hypot(tackler.x - c.x, tackler.y - c.y) < 2.2) {
            val def = tackler.player.attr.defending.toDouble()
            val dri = c.player.attr.dribbling.toDouble()
            if (rng.nextDouble() < def / (def + dri) * 0.22) {
                if (rng.nextDouble() < 0.12) {
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

        val wide = abs(c.y - PITCH_H / 2) > 18.0
        val nearByline = if (c.home) c.x > PITCH_W - 24.0 else c.x < 24.0
        if (wide && nearByline && (c.role == Role.WG || c.role == Role.FB || rng.nextDouble() < 0.3)) {
            if (rng.nextDouble() < 0.30) {
                deliverCross(c)
                return
            }
        }

        if (distGoal < 28.0 && !wide) {
            val homeBoost = if (c.home) 1.08 else 1.0
            val shootProb = ((30.0 - distGoal) / 30.0) * 0.045 * mFactor * homeBoost *
                (0.6 + c.player.attr.shooting / 200.0) / (1.0 + pressure * 0.6)
            if (rng.nextDouble() < shootProb) {
                attemptShot(c)
                return
            }
        }

        val basePassProb = 0.22 + pressure * 0.15
        if (rng.nextDouble() < basePassProb) passBall(c)
    }

    private fun passBall(c: SimPlayer) {
        val mates = players.filter { it.home == c.home && it !== c && !it.isGK }
        if (mates.isEmpty()) return
        val goalX = goalXFor(c.home)
        val candidates = mates.filter { hypot(it.x - c.x, it.y - c.y) < 42.0 }
        if (candidates.isEmpty()) return
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
            // Misplaced: out for a throw-in sometimes, otherwise intercepted
            val outY = target.y + rng.nextDouble(-8.0, 8.0)
            if ((outY < 1.5 || outY > PITCH_H - 1.5) && rng.nextDouble() < 0.6) {
                val tx = (c.x + target.x) / 2 + rng.nextDouble(-5.0, 5.0)
                awardThrowIn(tx.coerceIn(4.0, PITCH_W - 4.0), if (outY < 1.5) 0.5 else PITCH_H - 0.5, toHome = !c.home)
            } else {
                val interceptor = players.filter { it.home != c.home }
                    .minByOrNull { hypot(it.x - target.x, it.y - target.y) }
                startFlight(
                    target.x + rng.nextDouble(-6.0, 6.0),
                    (target.y + rng.nextDouble(-6.0, 6.0)).coerceIn(1.0, PITCH_H - 1.0),
                    14.0, interceptor, height
                )
            }
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

    private fun giveGoalKick(keeper: SimPlayer) {
        owner = keeper
        keeper.x = keeper.anchorX; keeper.y = keeper.anchorY
        ballX = keeper.x; ballY = keeper.y; ballZ = 0.0
    }

    // --------------------------------------------------------- set pieces ----

    private fun awardFreeKickOrPenalty(fouled: SimPlayer) {
        spHome = fouled.home
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

    private fun awardThrowIn(x: Double, y: Double, toHome: Boolean) {
        setPiece = SetPiece.THROWIN
        spHome = toHome
        spX = x
        spY = y
        beginSetup()
    }

    private fun beginKickoff(toHome: Boolean) {
        setPiece = SetPiece.KICKOFF
        spHome = toHome
        spX = PITCH_W / 2
        spY = PITCH_H / 2
        beginSetup()
    }

    /**
     * Computes an explicit target position for all 22 players and enters the
     * setup phase. Players jog to their spots; the delivery happens when the
     * countdown ends.
     */
    private fun beginSetup() {
        phase = Phase.SETUP
        flightTicks = 0; pendingReceiver = null; headerOnArrival = false
        owner = null
        ballX = spX; ballY = spY; ballZ = 0.0
        spTargets.clear()

        val atkGoalX = goalXFor(spHome)           // goal being attacked
        val dir = if (spHome) 1.0 else -1.0        // attacking direction along x
        val side = players.filter { it.home == spHome }
        val opps = players.filter { it.home != spHome }

        spKicker = when (setPiece) {
            SetPiece.CORNER, SetPiece.THROWIN -> side.filter { !it.isGK }.maxByOrNull { it.player.attr.passing }
            SetPiece.KICKOFF -> side.firstOrNull { it.role == Role.ST } ?: side.last()
            else -> side.filter { !it.isGK }.maxByOrNull { it.player.attr.shooting }
        }
        val kicker = spKicker

        fun place(sp: SimPlayer, x: Double, y: Double) {
            spTargets[sp.globalIdx] = x.coerceIn(0.5, PITCH_W - 0.5) to y.coerceIn(0.5, PITCH_H - 0.5)
        }

        // Deterministic small offset per player (stable across ticks)
        fun jig(sp: SimPlayer, range: Double) = ((sp.wobblePhase / 6.28) - 0.5) * 2 * range

        when (setPiece) {
            SetPiece.KICKOFF -> {
                spTicks = 12
                for (sp in players) {
                    if (sp === kicker) place(sp, spX - dir * 0.5, spY)
                    else {
                        // Everyone in their own half, at their anchors
                        val ownHalfX = if (sp.home) sp.anchorX.coerceAtMost(PITCH_W / 2 - 1.5)
                        else sp.anchorX.coerceAtLeast(PITCH_W / 2 + 1.5)
                        place(sp, ownHalfX, sp.anchorY)
                    }
                }
                // A second striker stands beside the kicker for the tap-off
                side.filter { it.role == Role.ST && it !== kicker }.firstOrNull()?.let {
                    place(it, spX - dir * 2.0, spY - 2.0)
                }
            }

            SetPiece.PENALTY -> {
                spTicks = 14
                val boxEdgeX = if (spHome) PITCH_W - 16.5 else 16.5
                val defGK = keeperOf(!spHome)
                place(defGK, if (spHome) PITCH_W - 0.8 else 0.8, PITCH_H / 2)
                place(keeperOf(spHome), keeperOf(spHome).anchorX, PITCH_H / 2)
                // Everyone else lines the edge of the box along the D
                val others = players.filter { it !== kicker && !it.isGK }
                others.forEachIndexed { i, sp ->
                    val t = -1.0 + 2.0 * i / (others.size - 1).coerceAtLeast(1)
                    val bulge = 2.0 + 4.5 * (1 - t * t)      // arc outside the box
                    place(sp, boxEdgeX - dir * bulge, PITCH_H / 2 + t * 21.0)
                }
                kicker?.let { place(it, spX - dir * 3.0, spY) }
            }

            SetPiece.FREEKICK -> {
                spTicks = 12
                val goalCX = atkGoalX
                val goalCY = PITCH_H / 2
                val distGoal = hypot(goalCX - spX, goalCY - spY)
                // Wall: defenders between ball and goal, 9.15m away
                val ux = (goalCX - spX) / distGoal
                val uy = (goalCY - spY) / distGoal
                val wallN = when {
                    distGoal < 20 -> 4
                    distGoal < 30 -> 3
                    else -> 2
                }
                val wallers = opps.filter { !it.isGK }
                    .sortedByDescending { it.player.attr.physical }
                    .take(wallN)
                wallers.forEachIndexed { i, sp ->
                    val off = (i - (wallN - 1) / 2.0) * 0.9
                    place(sp, spX + ux * 9.15 - uy * off, spY + uy * 9.15 + ux * off)
                }
                // Defending keeper covers the far side
                place(keeperOf(!spHome), if (spHome) PITCH_W - 0.8 else 0.8, goalCY + (if (spY < goalCY) 2.0 else -2.0))
                // In crossing range? Crowd the box on both sides
                val crossing = distGoal >= 26.0
                var atkSlot = 0
                var defSlot = 0
                for (sp in players) {
                    if (sp === kicker || sp.isGK || sp in wallers) continue
                    if (sp.home == spHome && (sp.role == Role.ST || sp.role == Role.CB || (crossing && sp.role == Role.CM))) {
                        atkSlot++
                        place(sp, atkGoalX - dir * (7.0 + jig(sp, 3.0) + 3.0), PITCH_H / 2 - 12.0 + atkSlot * 5.0)
                    } else if (sp.home != spHome && (sp.role == Role.CB || sp.role == Role.FB || sp.role == Role.CM)) {
                        defSlot++
                        place(sp, atkGoalX - dir * (6.0 + jig(sp, 2.0)), PITCH_H / 2 - 11.0 + defSlot * 4.5)
                    } else {
                        place(sp, sp.anchorX + (spX - PITCH_W / 2) * 0.3, sp.anchorY)
                    }
                }
                kicker?.let { place(it, spX - ux * 2.0, spY - uy * 2.0) }
            }

            SetPiece.CORNER -> {
                spTicks = 14
                val nearPostY = if (spY < PITCH_H / 2) PITCH_H / 2 - 3.66 else PITCH_H / 2 + 3.66
                val farPostY = if (spY < PITCH_H / 2) PITCH_H / 2 + 3.66 else PITCH_H / 2 - 3.66
                // Defending GK slightly towards the near post
                place(keeperOf(!spHome), if (spHome) PITCH_W - 0.8 else 0.8, PITCH_H / 2 + (nearPostY - PITCH_H / 2) * 0.3)
                // A defender on the near post
                val defOutfield = opps.filter { !it.isGK }.toMutableList()
                defOutfield.minByOrNull { hypot(it.x - atkGoalX, it.y - nearPostY) }?.let {
                    place(it, atkGoalX - dir * 1.2, nearPostY)
                    defOutfield.remove(it)
                }
                // Five attackers take up spots in the box; defenders man-mark them goal-side
                val attackers = side.filter { it !== kicker && !it.isGK }
                    .sortedByDescending { it.player.attr.physical }
                    .take(5)
                attackers.forEachIndexed { i, sp ->
                    val ax = atkGoalX - dir * (5.0 + (i % 3) * 3.5)
                    val ay = PITCH_H / 2 - 10.0 + i * 5.0 + jig(sp, 1.5)
                    place(sp, ax, ay)
                    val marker = defOutfield.minByOrNull { hypot(it.x - ax, it.y - ay) }
                    if (marker != null) {
                        place(marker, ax + dir * 1.2, ay + jig(marker, 0.8))
                        defOutfield.remove(marker)
                    }
                }
                // Short-corner option
                side.filter { it !== kicker && it !in attackers && !it.isGK }
                    .minByOrNull { hypot(it.x - spX, it.y - spY) }
                    ?.let { place(it, spX - dir * 9.0, spY + (if (spY < PITCH_H / 2) 6.0 else -6.0)) }
                // Everyone unassigned holds around halfway
                for (sp in players) {
                    if (sp.globalIdx !in spTargets && sp !== kicker) {
                        place(sp, PITCH_W / 2 + jig(sp, 8.0) + (if (sp.home == spHome) dir * 12.0 else -dir * 8.0), sp.anchorY)
                    }
                }
                kicker?.let { place(it, spX, spY) }
            }

            SetPiece.THROWIN -> {
                spTicks = 7
                kicker?.let { place(it, spX, spY) }
                // Two teammates offer short options; nearest opponents mark them
                val mates = side.filter { it !== kicker && !it.isGK }
                    .sortedBy { hypot(it.x - spX, it.y - spY) }
                    .take(2)
                val inField = if (spY < PITCH_H / 2) 6.0 else -6.0
                mates.forEachIndexed { i, sp ->
                    val mx = spX - dir * (3.0 - i * 8.0)
                    val my = spY + inField * (1.0 + i * 0.6)
                    place(sp, mx, my)
                    opps.filter { !it.isGK && it.globalIdx !in spTargets }
                        .minByOrNull { hypot(it.x - mx, it.y - my) }
                        ?.let { place(it, mx + dir * 1.5, my + inField * 0.3) }
                }
                for (sp in players) {
                    if (sp.globalIdx !in spTargets) {
                        place(sp, sp.anchorX + (spX - PITCH_W / 2) * 0.3, sp.anchorY + (spY - sp.anchorY) * 0.2)
                    }
                }
            }

            SetPiece.NONE -> { phase = Phase.OPEN }
        }
    }

    private fun executeSetPiece() {
        phase = Phase.OPEN
        val kicker = spKicker
        val type = setPiece
        setPiece = SetPiece.NONE
        spTargets.clear()
        if (kicker == null) { owner = null; return }
        kicker.x = spX; kicker.y = spY
        ballX = spX; ballY = spY
        owner = kicker

        when (type) {
            SetPiece.KICKOFF -> {
                // Tap it back to the nearest teammate
                val mate = players.filter { it.home == kicker.home && it !== kicker && !it.isGK }
                    .minByOrNull { hypot(it.x - spX, it.y - spY) }
                if (mate != null) startFlight(mate.x, mate.y, 12.0, mate)
            }
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
            SetPiece.THROWIN -> {
                val mate = players.filter { it.home == kicker.home && it !== kicker && !it.isGK }
                    .minByOrNull { hypot(it.x - spX, it.y - spY) }
                if (mate != null) {
                    if (rng.nextDouble() < 0.92) startFlight(mate.x, mate.y, 12.0, mate)
                    else {
                        val opp = players.filter { it.home != kicker.home }
                            .minByOrNull { hypot(it.x - mate.x, it.y - mate.y) }
                        startFlight(mate.x + 2.0, mate.y + 2.0, 12.0, opp)
                    }
                }
            }
            SetPiece.NONE -> {}
        }
    }
}
