# Procedural content as constrained design

Source: `Designing_a_3D_Roguelike_Game_with_Proce.pdf` (Vatresia, Utama, Yulianto, 2023)

## Useful design principles
The source treats procedural content generation as generation under an authored mission structure. Its graph-grammar example defines a primary mission first, then applies controlled transformation rules to add variation such as enemies, items, alternate paths, or locks.

The paper notes an important advantage of this approach: the generated result remains controllable and can preserve gameplay/narrative intent because the primary mission is defined in advance.

It also separates functional validation from player-experience evaluation. A generated system can function correctly without that alone proving a satisfying play experience.

## Application to Bob
When a game uses procedural generation:
- first identify the invariant experience, route logic, fairness constraints, readability, completion conditions, and intended difficulty;
- then determine what may vary;
- then define generation constraints that preserve the invariants.

Never translate "procedural" into "random." Generated content must remain completable, legible, coherent, and supportive of the intended player experience.

When interviewing, Bob should ask procedural follow-ups only when they affect those meaningful constraints. Implementation details that can safely be delegated should remain delegated.
