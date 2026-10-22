# Synthetic climbing-platformer oracle ("Lantern Tower")

An invented project used as a regression oracle for `TowerClimbRegressionTest`. It replaces an earlier oracle that was derived from a real owner project; no owner data is used here. It describes what the interview and the export must produce, not a game to build.

## The interview (see the test for the exact synthetic answers)
A skill-based 2.5D climbing platformer for Android phones about climbing a lighthouse whose stair ring rotates to keep the climber centred. Mechanics named: jumps, trampoline pads, moving and swinging platforms, ropes. No combat. The climber never gets stronger. Procedural stages, checkpoints with quick retry, voxel-look art, supplied 3D asset packs, over-the-air updates through Mote, an uploaded studio logo.

## What must be true afterwards
| Area | Expected |
|---|---|
| Decisions | genre platformer, 2.5D, Android, procedural stages, checkpoint retry, voxel art, win = reach the top |
| Feeling | The owner's words are kept ("a little lucky, but skill based") and read as a skilled near-miss, never randomness |
| Facts and constraints | The camera-anchor and rotating-ring idea survives as owner facts and as the binding "must not change" constraint |
| Gates | `has_combat` = no and `has_progression` = no; the combat and progression follow-up questions are never asked; no stale progression decision |
| Assets | Supplied packs first, then free, then original; asset policy is not asked again because the owner already said it; the strategy text appears in the spec, the prompt and ASSETS.md |
| OTA | "yes" opens no scope question (Mote decides what is OTA-safe); "no" removes all OTA text and decisions |
| Design model | Combat and character power ABSENT; player mastery counts as progression; procedural generation, supplied assets and OTA PRESENT; no unresolved material decisions |
| Export | No stale combat, enemy, unlock, level-select or sprite text; contains "NONE by design", the traversal grammar (trampoline, rope), the camera constraint, API 36, the studio logo name; passes the consistency review |
| Branches | Yes to combat opens combat follow-ups; yes to progression opens progression follow-ups and exports them; an explicit change of mind is an OWNER_CORRECTION and reopens the branch; a later owner statement supersedes an earlier recommendation; Back after a gate answer reconsiders the branch |
