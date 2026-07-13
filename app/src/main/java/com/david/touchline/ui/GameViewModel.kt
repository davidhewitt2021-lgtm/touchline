package com.david.touchline.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.david.touchline.engine.Fixture
import com.david.touchline.engine.GameState
import com.david.touchline.engine.MatchEngine
import com.david.touchline.engine.MatchResult
import com.david.touchline.engine.Season
import com.david.touchline.engine.WorldGen
import com.david.touchline.engine.autoPickXI
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

enum class Tab { HOME, SQUAD, TACTICS, LEAGUE, TRANSFERS }

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val saveFile: File get() = File(getApplication<Application>().filesDir, "save.json")

    var state by mutableStateOf<GameState?>(null)
        private set

    var tab by mutableStateOf(Tab.HOME)
    var detailPlayerId by mutableStateOf<Int?>(null)

    /** Non-null while a user match is being watched. */
    var liveMatch by mutableStateOf<MatchResult?>(null)
        private set
    var liveFixture by mutableStateOf<Fixture?>(null)
        private set

    /** Bumped to force recomposition after in-place engine mutations. */
    var version by mutableIntStateOf(0)
        private set

    init {
        loadGame()
    }

    fun touch() { version++ }

    fun hasSave(): Boolean = saveFile.exists()

    fun loadGame() {
        try {
            if (saveFile.exists()) {
                state = json.decodeFromString(GameState.serializer(), saveFile.readText())
            }
        } catch (_: Exception) {
            state = null
        }
    }

    fun saveGame() {
        val s = state ?: return
        try {
            saveFile.writeText(json.encodeToString(GameState.serializer(), s))
        } catch (_: Exception) {
        }
    }

    fun newGame(chosenTeamId: Int, prebuilt: GameState) {
        prebuilt.userTeamId = chosenTeamId
        Season.refreshTransferList(prebuilt, Random(prebuilt.seed))
        state = prebuilt
        tab = Tab.HOME
        saveGame()
    }

    fun deleteSave() {
        saveFile.delete()
        state = null
        liveMatch = null
        liveFixture = null
    }

    /** Runs the full simulation for the user's fixture and opens the match screen. */
    fun kickOffUserMatch(watch: Boolean) {
        val s = state ?: return
        val fixture = Season.userFixture(s) ?: return
        // Make sure the user XI is valid before kickoff
        val user = s.userTeam()
        val squadIds = s.squad(user.id).map { it.id }.toSet()
        if (user.tactics.startingXI.size != 11 || !user.tactics.startingXI.all { it in squadIds }) {
            user.tactics.startingXI = autoPickXI(s, user.id)
        }
        val engine = MatchEngine(s, fixture, s.seed + s.round * 977L + fixture.homeId, recordFrames = watch)
        val result = engine.simulate()
        if (watch) {
            liveFixture = fixture
            liveMatch = result
        } else {
            Season.applyResult(s, fixture, result)
            finishRound()
        }
        touch()
    }

    /** Called when the user leaves the match screen (full-time or skip). */
    fun concludeLiveMatch() {
        val s = state ?: return
        val fixture = liveFixture
        val result = liveMatch
        if (fixture != null && result != null && !fixture.played) {
            Season.applyResult(s, fixture, result)
            finishRound()
        }
        liveMatch = null
        liveFixture = null
        touch()
    }

    private fun finishRound() {
        val s = state ?: return
        Season.completeRound(s)
        saveGame()
        touch()
    }

    fun buy(playerId: Int): String? {
        val s = state ?: return "No game."
        val err = Season.buyPlayer(s, playerId)
        if (err == null) { saveGame(); touch() }
        return err
    }

    fun sell(playerId: Int): String? {
        val s = state ?: return "No game."
        val err = Season.sellPlayer(s, playerId)
        if (err == null) { saveGame(); touch() }
        return err
    }

    fun setFormation(formation: com.david.touchline.engine.Formation) {
        val s = state ?: return
        val t = s.userTeam().tactics
        t.formation = formation
        t.startingXI = autoPickXI(s, s.userTeamId)
        saveGame()
        touch()
    }

    fun setMentality(m: Int) {
        val s = state ?: return
        s.userTeam().tactics.mentality = m.coerceIn(0, 100)
        touch()
    }

    fun swapStarter(outId: Int, inId: Int) {
        val s = state ?: return
        val xi = s.userTeam().tactics.startingXI
        val idx = xi.indexOf(outId)
        if (idx >= 0 && inId !in xi) {
            xi[idx] = inId
            saveGame()
            touch()
        }
    }

    /** Builds a fresh world so the new-game screen can show real clubs to choose from. */
    fun buildWorldPreview(): GameState = WorldGen.newGame(System.currentTimeMillis())
}
