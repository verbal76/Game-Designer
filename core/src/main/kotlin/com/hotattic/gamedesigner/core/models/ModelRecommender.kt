package com.hotattic.gamedesigner.core.models

enum class ChoiceLabel(val title: String) { FAST("Fast"), BALANCED("Balanced"), BEST("Best this phone can comfortably handle") }

data class ModelChoice(
    val label: ChoiceLabel,
    val entry: ModelEntry,
    /** Why this phone can run it, in plain words. */
    val fits: String,
    /** Something the owner should know before choosing (needs a token, tight on RAM...). Empty if nothing. */
    val caution: String,
)

/**
 * Device-aware model choices. A model the phone cannot realistically run is never listed. The rest are presented as at most three
 * understandable options (fast, balanced, best) so the owner does not have to compare parameter counts.
 */
object ModelRecommender {

    /** Why a model is not viable on this device, or null if it is. */
    fun blocker(p: DeviceProfile, m: ModelEntry): String? = when {
        !p.arm64 -> "this phone is not 64-bit ARM"
        p.sdk < 28 -> "needs Android 9 or newer"
        p.totalRamMb < m.minTotalRamMb -> "needs about ${m.minTotalRamMb / 1024.0} GB of RAM"
        p.freeStorageMb < m.sizeMb * 1.15 -> "needs ${(m.sizeMb * 1.15).toInt()} MB of free storage"
        else -> null
    }

    fun viable(p: DeviceProfile, catalog: List<ModelEntry>): List<ModelEntry> = catalog.filter { blocker(p, it) == null }

    fun choices(p: DeviceProfile, catalog: List<ModelEntry>): List<ModelChoice> {
        val ok = viable(p, catalog).sortedWith(compareBy<ModelEntry> { it.quality }.thenBy { it.speed })
        if (ok.isEmpty()) return emptyList()
        val fastest = ok.maxWith(compareBy<ModelEntry> { it.speed }.thenBy { it.quality })
        val best = ok.last()
        val picks = mutableListOf<Pair<ChoiceLabel, ModelEntry>>()
        picks += ChoiceLabel.FAST to fastest
        val middle = ok.filter { it.id != fastest.id && it.id != best.id }.maxByOrNull { it.quality * 2 + it.speed }
        if (middle != null) picks += ChoiceLabel.BALANCED to middle
        if (best.id != fastest.id) picks += ChoiceLabel.BEST to best
        return picks.distinctBy { it.second.id }.map { (l, m) ->
            val roomy = p.totalRamMb >= m.minTotalRamMb * 1.4
            ModelChoice(l, m,
                fits = "Needs about ${m.runtimeRamMb / 1024.0}".let { "Uses about %.1f GB of memory and %.1f GB of storage; this phone has %.1f GB RAM and %.1f GB free.".format(m.runtimeRamMb / 1024.0, m.sizeMb / 1024.0, p.totalRamMb / 1024.0, p.freeStorageMb / 1024.0) },
                caution = buildList {
                    if (m.gated) add("The host requires accepting the model's license and a (free) Hugging Face token.")
                    if (!roomy) add("This one will be a tight fit while other apps are open.")
                }.joinToString(" "))
        }
    }
}
