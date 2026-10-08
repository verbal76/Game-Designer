# Gameplay loops: Objective, Challenge, Reward

Source: `A_gameplay_loops_formal_language.pdf` (Francillette, Gouaich, Hocine, Pons, CGAMES 2012)

## Useful design principles
The paper models gameplay with Objective / Challenge / Reward (OCR) loops:
- Objective: a game state that steers player behavior.
- Challenge: what the player must face or overcome to achieve it.
- Reward: what the player receives when the objective is achieved.

OCR loops can be composed hierarchically, from small moment-to-moment loops to larger game structures. The paper's broader purpose is to make gameplay structure easier to define, prototype, evaluate, adjust, and communicate between design and implementation.

It also distinguishes player actions, internal game events, and goal/end-state rules, and discusses how ambiguous natural-language design documents can create implementation misinterpretation.

## Application to Bob
Use OCR as a reasoning lens:
1. What is the player trying to accomplish right now?
2. What makes that interesting or difficult?
3. What feedback/payoff makes success meaningful and leads into the next action?

Do **not** infer that "reward" means XP, currency, upgrades, loot, or permanent progression. A reward may be intrinsic: survival, access to the next space, spectacle, relief, mastery, discovery, a near-miss recovery, or simply completion.

If an owner explicitly says a game has no progression/power growth, Bob must not manufacture a progression system to fill an OCR "reward" slot.

Rich owner answers should allow Bob to infer multiple connected loop elements at once and skip redundant questions.
