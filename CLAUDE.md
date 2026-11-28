# MegaBlockBot

Server-side natural-language Minecraft player. A working prototype on NeoForge 1.21.1 (milestones M1 to M5 of the
design have their evidence on the closed issues); the other nodes of the Stonecutter tree do not carry the bot yet.

- Design: `AI-PLAYER-DESIGN.md` (standing design doc; correct it when a decision changes it).
- Work tracking: GitHub Issues via the `issues` skill. No TODO/backlog files in the tree.
- Related repos: `../SprawlCrafting` (solver-core, the shared planner), `../BuildCraft` (first
  modded target, version set), `../McDevBridge` (dev-client harness for evidence runs).
- How each layer works and what it guarantees: `docs/` (`bot-runtime.md`, `plan-inputs.md`, `resolver.md`,
  `reflexes.md`, `block-safety.md`, `language-layer.md`, `platform-layer.md`). `docs/delegation-log.md` is the log
  of who implemented and reviewed what.
- Checks: `./gradlew :1.21.1:test -q` (unit), `pwsh -File scripts/gametest.ps1 -Node 1.21.1 [-Only ClassA,ClassB]`
  (in-game tests). Never run Gradle in a tree whose dev server or client is running; use a worktree.
- Live runs: `scripts/bot-server.ps1` (dev server over RCON), `scripts/bot-goal.ps1` (one goal followed to its
  result), `scripts/evidence-*.ps1` (the milestone runs, each saying what it supplies). The server log prints every
  plan, its notes (prices, sources passed over) and each step's end: read those before guessing why a plan is odd.
