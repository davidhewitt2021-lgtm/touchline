# Touchline

An original football management game for Android. Procedurally generated
players and clubs, tactics, transfers, multi-season careers, and a live
2D top-down match engine you can watch play out.

Built entirely without Android Studio — edit in Termux, build with
GitHub Actions, install the APK from the workflow artifact. Same
workflow as TalkBridge.

## Features (v0.1)

- 14-club league, double round-robin season, full table and top scorers
- Procedural squads: attributes, ages, potential, form, morale, values
- Tactics: five formations, mentality slider, manual starting XI swaps
- Live 2D match viewer: animated players and ball, scoreboard, event
  ticker, 1x/2x/4x/8x playback, skip, full-time stats
- Quick-sim option for match days you don't want to watch
- Transfer market: buy listed players, sell your own
- Season progression: player development, ageing, retirements, youth
  intake, prize money, AI transfer activity
- Autosave to JSON after every round; abandon save from the Home tab

## Building from Termux

```bash
pkg install git gh
cd touchline
git init -b main
git add .
git commit -m "Touchline v0.1"
gh auth login          # once
gh repo create touchline --private --source=. --push
```

Every push to `main` builds a debug APK. Download it from
Actions → latest run → Artifacts → `touchline-debug-apk`, or:

```bash
gh run watch
gh run download --name touchline-debug-apk
```

Then open `app-debug.apk` on the phone to install (allow installs from
unknown sources).

## Project layout

```
app/src/main/java/com/david/touchline/
  engine/   Pure Kotlin game logic (no Android deps)
    Model.kt        Data model, league table, XI picker
    WorldGen.kt     Name/club/player generation, fixture builder
    MatchEngine.kt  Tick-based 2D simulation (positions + events)
    Season.kt       Round simulation, transfers, development
  ui/       Jetpack Compose screens
    AppRoot.kt      Theme, navigation, new-game screen
    Screens.kt      Home, Squad, Tactics, League, Transfers
    MatchScreen.kt  2D pitch renderer + playback controls
    GameViewModel.kt  State, JSON persistence, match flow
```

The engine is deliberately Android-free so it can be unit-tested or
reused (e.g. a desktop or web front end later).

## Roadmap ideas

- Second division with promotion/relegation
- Injuries, suspensions, substitutions during matches
- Contract lengths, wages, and negotiations
- Scouting with masked attributes
- Cup competition
- Sound and haptics on goals
