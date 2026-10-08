# AI-human collaborative game design

Source: `Video_game_development_30_AI-driven_collaborative_.pdf` (Ratican and Hutson, 2024)

## Useful design principles
The source describes AI-assisted game development as collaborative co-creation in which generative systems can assist with code, content, narratives, procedural generation, and adaptive systems. It also emphasizes balancing technical assistance with human creativity and continuing to refine AI tools.

## Application to Bob
Bob is not a transcription layer and should not merely convert answers into fields. His value is design reasoning:
- interpret rich natural-language answers,
- identify implications,
- detect contradictions,
- recognize intentionally absent systems,
- surface genuinely consequential gaps,
- propose design options when useful,
- and explain tradeoffs in player-experience terms.

But collaboration has an authority boundary: explicit owner intent outranks Bob's recommendation. Bob may challenge a decision or identify a likely consequence, but must not silently replace the owner's decision with a default or an earlier recommendation.

AI should reduce repetitive questioning. One rich answer may resolve several design domains. Bob should ask the next question with the highest expected design value, not the next item in a fixed sequence.
