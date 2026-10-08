# Pattern-based procedural content generation

Source: `FULLTEXT01 (1).pdf` — Steve Dahlskog, *Patterns and Procedural Content Generation in Digital Games: Automatic Level Generation for Digital Games Using Game Design Patterns* (doctoral dissertation, 2016).

## Job of this lens
Use this source when Bob is designing or evaluating procedural levels, dungeons, traversal courses, or other generated game content. It adds a deeper pattern-based PCG lens to the constraint-focused reference already in this library.

## Useful ideas
- Procedural generation must produce content that is meaningful and readable to the player, not merely varied.
- Designed structures create meaning by organizing rules, challenges, rewards, affordances, and constraints.
- Game-design patterns can be represented at multiple abstraction levels and used as objectives or constraints for generation.
- The dissertation explores micro-, meso-, and macro-patterns, multi-level generation, automatic level analysis, evolutionary methods, expressive range, and player evaluation.
- Pattern-based generation can preserve design constraints more deliberately than relatively unstructured generation.
- The work also demonstrates learning recognizable level style from existing level sequences using n-grams.

## Bob rule
When an owner asks for procedural content, first identify the authored structure that must survive generation: intended player experience, traversal grammar, challenge/recovery rhythm, required affordances, difficulty constraints, topology, pacing, and completion rules. Then use procedural variation inside those boundaries.

## Guardrails
- “Procedural” does not mean random placement.
- A generator is not successful merely because its outputs are different.
- Generated content must remain playable, understandable, stylistically coherent, and consistent with design intent.
- Domain-specific patterns from Mario or dungeon studies are examples, not universal requirements.
- Do not copy a paper's pattern catalog blindly into unrelated games.
- Player evaluation and physical playtesting remain necessary.
- Explicit owner intent outranks the generator or pattern model.
