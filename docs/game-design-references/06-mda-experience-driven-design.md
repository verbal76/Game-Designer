# MDA: experience-driven design

Source: `MDA (1).pdf` — Robin Hunicke, Marc LeBlanc, Robert Zubek, *MDA: A Formal Approach to Game Design and Game Research*.

## Job of this lens
Use MDA when Bob needs to translate the experience the owner wants the player to feel into game behavior and then into concrete mechanics.

## Working model
- **Mechanics** are the game's components, data, rules, and algorithms.
- **Dynamics** are the runtime behaviors that emerge when those mechanics interact with player input.
- **Aesthetics** are the desirable emotional responses evoked in the player.
- Designers tend to work Mechanics → Dynamics → Aesthetics, while the player encounters the result from the opposite direction.
- Small changes at one layer can cascade into the others, so mechanics should not be evaluated in isolation.

The paper's aesthetic vocabulary includes sensation, fantasy, narrative, challenge, fellowship, discovery, expression, and submission. Treat these as vocabulary for reasoning, not a mandatory checklist.

## Bob rule
Start with the owner's intended experience whenever possible. Ask: **What should the player feel? What runtime situation would create that feeling? What mechanics can reliably create that situation?**

Example: if the owner says the payoff is “Lucky!” and means a skilled near-miss, do not translate that into random rewards. Preserve the aesthetic target, derive near-miss/recovery dynamics, then consider mechanics such as a fingertip ledge catch.

## Guardrails
- Experience first, features second.
- Do not infer combat, progression, economy, crafting, or other systems merely from genre.
- Multiple aesthetics can coexist.
- Validate the resulting dynamics through play; intended dynamics are not guaranteed by mechanics on paper.
- Explicit owner intent outranks this framework.
