# Climb up - ground-truth reference (regression oracle, NOT a build request)

Derived only from the owner's raw interview answers in `Climb_up_spec_v1.zip` (`game-designer/project.json`, messages and `rawAnswer`s), their later corrections, and implications that do not contradict them. Bob's recommendations and routine defaults were ignored unless the owner accepted them AND they do not conflict with something the owner said later.

`ClimbUpRegressionTest` replays those raw answers through the Director and asserts the resulting state and export against this document. It must never be used to build the game.

## The master build prompt Game Designer should have generated

You are the lead engineer for **Climb up**. Read the repository `CLAUDE.md` first; it is authoritative. Build the complete, genuinely playable first version described below.

**Concept.** A skill-based 2.5D climbing platformer for Android phones (landscape, touch), in the spirit of "Up" where you simply keep climbing. The world is made to *look* as if the player climbs around the outside of a giant cylinder or spiral tower, but no such cylinder exists in the simulation. As the player climbs, the traversable spiral/world rotates so the player stays approximately centred on screen: **the player is the camera anchor**. This is a binding constraint. Do not reinterpret it as a conventional side-scrolling camera, and do not move the camera focus away from the player.

**Feeling.** The owner's word is *lucky*, and they said why: "this is a skill based game". Success is never decided by randomness. The player should repeatedly survive situations that *feel* improbable - barely reaching a platform, catching a moving platform or a rope at the last moment, crossing a crumbling platform just before it goes, grabbing a ledge after a jump that looked too long - and think "I can't believe I made that".

**Core loop (minute to minute).** See the next traversal obstacle; work out how to get across; commit; barely make it; recover; keep climbing upward. There is always something impeding travel and always a place to get to.

**Traversal and obstacle set.** Normal jumps and deliberately difficult long jumps; trampoline/bounce pads; ropes to climb (cables or similar where they fit); platforms that move vertically and horizontally; swinging platforms; crumbling platforms; whole platform sections that move; mid-air catches; environmental timing challenges. One deliberate set piece the owner described: a jump whose gap is slightly too big for a clean landing, where the character catches the edge with its fingertips and pulls itself up. Keep this a traversal-recovery mechanic, not a climbing-simulation subsystem. On-screen touch controls are required.

**World.** Procedurally generated climbing stages that combine the obstacle types into a coherent upward route. Generation must keep every route completable, destinations readable, jumps fair (hard jumps are deliberate, never accidental), obstacle combinations varied, difficulty rising, and the spiral/cylindrical illusion intact. It must not feel like random platforms in space. There is no hand-built level set and no level select.

**Win, failure, recovery.** The player wins by getting to the top. The owner's words: "They don't really ever fail. They just don't win." A missed jump is a setback handled by **checkpoints and quick retry**: fast recovery so the player keeps attempting the climb. No lives, health, game-over, combat death or roguelike reset.

**No combat.** There are no enemies, bosses, weapons, health or damage model. Challenge is purely environmental.

**No player power progression.** The player never gets stronger: no XP, levels, stats, skill tree, in-run upgrades, permanent unlocks, power-ups or meta-upgrades. The owner said so repeatedly ("He doesn't get stronger", "It's a climbing game", "Either you can make the jumps or you can't"). Progression is the player's own skill plus physical progress up the course.

**First playable.** About 20 minutes of climbing with multiple obstacle types in combination, from start to the top, as one successful gameplay loop start to finish.

**Look.** Voxel-look 2.5D: the world and assets are 3D, play is presented as 2.5D with real depth layering. Pick an engine that handles 3D assets on Android.

**Assets.** The owner supplies 3D asset packs together with the master prompt. **Inspect them first and use their real contents.** Create missing animations for the supplied player character where technically reasonable. Fill genuine gaps with CC0/public-domain assets (verify each license), then original/procedural assets. Log everything in `ASSETS.md`. Do not replace supplied assets with external or generated ones.

**Branding.** Preserve the owner's `Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png` untouched as the studio splash master (derive sizes from it). Create an original game icon and an original game splash. Title: **Climb up**; package id `com.hotatticgames.climbup` is a Bob default, not an owner requirement.

**Over-the-air updates.** Required, least intrusive: quiet background check, applied on the next launch only, signed, automatic rollback to bundled content. The owner-level decision is what updates may change: content and tuning only (the recommended answer) unless they choose to include sandboxed game logic. How, when and rollback are engineering defaults.

**Must not change.** Do not reinterpret where the camera focus is; how the terrain rotates to match the camera focus; and that this is purely a climbing game with environmental obstacles.

**Done when.** A successful gameplay loop throughout the entire game from start to finish.

**Validation.** Cold-start on Android and reach gameplay; jump, catch ledge, climb ropes, ride/catch moving and swinging platforms, use trampolines; the camera keeps the player centred while the world rotates; generate many stages with an automated solver proving every one has a completable route; fail on purpose and confirm checkpoint recovery cannot soft-lock; reach the top and restart cleanly; confirm nothing earned makes climbs easier; confirm the supplied asset packs are used; exercise the update pipeline (valid applies on next launch only, tampered and corrupt rejected, plays offline). Capture and inspect real screenshots.

## Deliberately NOT in this document (and what the old export wrongly contained)

| Old export | Why it was wrong |
|---|---|
| Progression: "Permanent unlocks between runs" | Bob's canned recommendation applied by "choose for me" after the owner said, three times, that the player does not get stronger |
| "Earn at least one progression step" verification, "progression that changes only a hidden number" | Followed from the stale progression value |
| Content counts: 28 levels, 4 worlds, 12 enemy types, 4 bosses, 6 power-ups | Genre heuristics for a hand-built, combat, power-up platformer; none apply |
| "Hand-built level set", "Level select and progress save", "Hazards and enemies ... damage rules" | Genre checklist items contradicting procedural stages and no combat |
| "Typical session: 1-5 minutes", "View: Side view" | Bob defaults presented as design facts; the owner described a 20-minute slice and a rotating, player-anchored camera |
| "How it should feel: lucky" | The owner's qualifier ("because this is a skill based game") was dropped, inviting an RNG reading |
| Asset policy "CC0 / public domain, else original/procedural"; assets as 2D "sprites" | Lost "use the asset packs that I give it"; the packs are 3D |
| "Implementing only one of the described characters or modes ..." | Triggered by "Either you're able to ... or you're not" |
| libGDX as engine | A 2D-biased default for a game whose assets are 3D voxel models |
