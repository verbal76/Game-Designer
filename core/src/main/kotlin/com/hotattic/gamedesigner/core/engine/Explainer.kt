package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Plain-English teaching cards. Every meaningful recommendation answers the beginner's five questions: what am I choosing, what does it do,
 * why is Bob recommending it, what are the disadvantages, and what happens if I choose something else. The correct term is always kept and
 * defined; nothing is dumbed down. Cards are generated from the actual project (platforms, genre, multiplayer, budget, update needs), not canned.
 */
object Explainer {

    enum class Depth { BEGINNER, ADVANCED, MORE }

    class Topic(
        val id: String,
        val term: String,
        /** WHAT IT IS: the correct term, defined in everyday language. */
        val what: String,
        /** HOW IT WORKS. */
        val how: String,
        /** WHY IT MATTERS for THIS game. */
        val why: (Traits) -> String,
        val pros: (Traits) -> List<String>,
        val cons: (Traits) -> List<String>,
        /** BOB'S RECOMMENDATION and the reason it fits. */
        val recommend: (Traits) -> String,
        /** What happens if the owner picks something else. */
        val ifElse: (Traits) -> String,
        /** LONG-TERM CONSEQUENCES. */
        val longTerm: String,
        /** True when the choice is expensive to change later; stated outright. */
        val hardToReverse: Boolean = false,
        val more: String = "",
    )

    private fun game(t: Traits) = t.genres.firstOrNull()?.label?.substringBefore(" (")?.substringBefore(" /")?.lowercase()?.let { "your $it game" } ?: "your game"
    private fun plat(t: Traits) = t.platforms.map { Platforms.labels[it] ?: it }.let { l -> when (l.size) { 0 -> "your chosen platforms"; 1 -> l[0]; else -> l.dropLast(1).joinToString(", ") + " and " + l.last() } }

    val topics: Map<String, Topic> = listOf(
        Topic("dimension", "2D, 2.5D and 3D",
            "These describe how the world is built. 2D is flat, like a drawing. 3D has real depth, like a world you could walk around. 2.5D is a 3D world played on a flat plane or with a fixed camera.",
            "In 2D everything is a flat picture moving on a plane. In 3D the game keeps real positions in space and a camera decides what you see. In 2.5D the world is 3D but play happens along two axes.",
            { t -> "For ${game(t)}, this decides how much art, camera work and testing the game needs." },
            { listOf("2D is the fastest to build and the easiest to polish.", "3D gives depth, freedom of camera and a richer look.", "2.5D mixes a 3D look with simpler 2D play.") },
            { listOf("3D needs more art, a camera that never fails and far more testing.", "2D can feel flat if the game wants depth.", "2.5D still needs 3D art and tools.") },
            { t -> if (t.mobileOnly) "On a phone I lean toward the simplest dimension the idea allows, because phones have less power and a small screen." else "I recommend the simplest dimension that still delivers the feeling you described." },
            { "Choosing a different dimension changes the engine options, the art you need and how the camera and controls work." },
            "The dimension shapes the whole project: art, engine, camera, controls and testing. Changing it after building starts means redoing most of the work.", true),

        Topic("art_direction", "Art style",
            "The art style is the overall look of the game: pixel art, voxel (a world built from small cubes), low-poly (simple 3D shapes with flat faces), or smooth realistic or stylized 3D.",
            "Each style needs different tools and assets. They are not interchangeable: a voxel world is not the same as smooth low-poly 3D, and the game's feel depends on the style.",
            { t -> "The look is a big part of what ${game(t)} will feel like, so Bob treats the chosen style as a requirement, not a suggestion." },
            { listOf("A clear, consistent style makes a small team's game look intentional.", "Free asset packs usually come in one style, so picking a style helps you find matching assets.") },
            { listOf("Some styles need special engine support or tools.", "Mixing styles looks messy.") },
            { "I recommend the style that best matches the feeling you described and that suits free assets you can legally use. If you have a picture of the look you want, show it to me." },
            { "If you pick a different style later, the assets, models and some systems have to be redone." },
            "The style is hard to change after assets exist. Bob will not swap styles for technical convenience without asking you.", true),

        Topic("camera", "Camera perspective",
            "The camera perspective is where the player's view sits: side-on, top-down, first person, third person behind the character, or isometric (a fixed angled view).",
            "The game moves a virtual camera and shows you what it sees. Different perspectives suit different games and controls.",
            { t -> "The camera decides what ${game(t)} feels like to control and what the player can see." },
            { listOf("A good fit makes the game instantly readable.") }, { listOf("Cameras that follow fast action need careful tuning.", "Switching perspective later changes controls and levels.") },
            { "I recommend the perspective that most games like yours use unless you want something different." },
            { "A different perspective changes the controls, the levels and often the art." },
            "Camera and controls are tuned together; once they feel right, they are protected from accidental change.", true),

        Topic("procedural", "Procedural generation",
            "Procedural generation means the game creates some of its content automatically using rules we design, instead of every level being built by hand.",
            "For example, instead of building 100 castle rooms individually, we teach the game how to arrange rooms into new combinations. The rules decide what is allowed, so results stay playable.",
            { t -> "For ${game(t)}, generated content gives variety and replay value without hand-building everything." },
            { listOf("Endless variety from a small amount of authored content.", "Smaller downloads and less manual level work.") },
            { listOf("It needs extra testing to prove every generated level is playable and fair.", "Pure randomness feels unfair; the rules need careful design.", "Competitive or speedrun play needs repeatable seeds so everyone can run the same course.") },
            { "I recommend handcrafted important areas with automatically generated sections between them, plus an automated check that every generated route can be completed." },
            { "Fully handmade levels are more predictable and need no generation tests, but take much longer to build and give less variety." },
            "Generation rules become a core system; changing them later changes how every level feels.", false),

        Topic("engine", "Game engine",
            "A game engine is the software foundation used to build your game. It handles things like graphics, movement, physics, sound, and controls.",
            "You build your game inside the engine's tools and it provides the common machinery, then packages the finished game for each platform.",
            { t -> "For ${game(t)} on ${plat(t)}, the engine decides which platforms are practical, how good the visuals can be, and whether updates after release are possible." },
            { t -> listOfNotNull((t.engine ?: StackAdvisor.ranked(t.project).firstOrNull()?.engine)?.strengths) },
            { t -> listOfNotNull((t.engine ?: StackAdvisor.ranked(t.project).firstOrNull()?.engine)?.caveats) },
            { t ->
                val c = StackAdvisor.comparison(t.project)
                val top = (t.engine ?: StackAdvisor.ranked(t.project).firstOrNull()?.engine)
                if (top == null) "I will recommend an engine once I know your platforms and whether the game is 2D or 3D." else "I recommend ${top.name}. ${c.getOrNull(1)?.let { "The closest alternative: ${it.substringAfter("Closest viable alternative: ")}" } ?: ""}".trim()
            },
            { "A different engine is possible. I will check it against your platforms, 3D needs and update needs, and tell you plainly if it cannot do something you asked for." },
            "The engine is the hardest decision to reverse. Switching after development starts usually means rebuilding the game.", true,
            more = "Engines differ in cost and license, language, how well they package for each platform, how easily an AI coding agent can build and test them without a graphical editor, and whether gameplay code can be updated after install."),

        Topic("platforms", "Target platforms",
            "A platform is the kind of device the game runs on: an Android phone, an iPhone or iPad, a Windows PC, a Mac, Linux, a web browser or the Steam Deck handheld.",
            "The game is built once and packaged separately for each platform; each package needs its own testing.",
            { t -> "More platforms mean more testing and more places where controls and performance can go wrong. ${if (Platforms.STEAM_DECK in t.platforms) "The Steam Deck needs full controller navigation and a 1280x800 screen. 'Steam Deck compatible' is not the same as Valve's official 'Verified' badge." else ""}".trim() },
            { listOf("Each platform you add reaches more players.") }, { listOf("Each platform adds testing, packaging and sometimes paid accounts (Apple needs a Mac and a developer account).", "Doing three platforms halfway is worse than one done well.") },
            { "I recommend choosing every platform you really want, then naming one to build and perfect first. The others stay as targets." },
            { "Dropping a platform later is easy. Adding one late can force changes to controls and performance." },
            "The platform list affects the engine choice, controls, screen layouts and update options.", false),

        Topic("primary_platform", "Primary platform",
            "The primary platform is the one device we build, test and perfect first.",
            "Early builds, controls, performance targets and screen layouts are designed around it. The other platforms stay supported by keeping one shared game with a thin layer per platform.",
            { t -> "You selected ${plat(t)}. Choosing one to lead keeps early builds focused and the game feel right where you will actually play." },
            { listOf("Faster first playable build.", "Controls tuned for the device you really use.") }, { listOf("Secondary platforms get less attention early.") },
            { t -> "I recommend ${Platforms.labels[t.platforms.firstOrNull { it == Platforms.ANDROID } ?: t.platforms.firstOrNull() ?: ""] ?: "the platform you will test on most"} because it is the one you can test on quickly." },
            { "Any platform can be primary. Gameplay feel is never sacrificed on the primary platform just to suit another." },
            "Other platforms remain targets. Nothing is dropped.", false),

        Topic("multiplayer", "Multiplayer",
            "Multiplayer means more than one person plays together. Local multiplayer is people in the same room; online multiplayer connects people over the internet.",
            "Local play can share one screen or use several devices on the same network. Online play sends each player's actions across the internet and keeps every copy of the game in agreement.",
            { t -> "Multiplayer changes how ${game(t)} is built, so it is decided before the engine, even if it arrives later." },
            { listOf("Playing with friends is often the best part of a game.") }, { listOf("Online multiplayer is much more work: networking, fairness, disconnects and testing.", "Servers can cost money.") },
            { "I recommend single-player for the first build unless multiplayer is the point of the game, and a design that keeps multiplayer possible if you want it later." },
            { "Adding multiplayer to a game built without it is one of the most expensive changes there is." },
            "Even if multiplayer is planned for later, the game must be built so it can be added without a rewrite.", true),

        Topic("netcode", "Hosting an online game",
            "There are two common ways players connect. In client-server multiplayer, one computer or online server coordinates the game. In peer-to-peer multiplayer, players connect more directly to each other, and often one player acts as the host.",
            "With a server, every player sends their actions to the server, which decides what really happened and tells everyone. With peer-to-peer, the players' devices share that job.",
            { t -> "For ${game(t)} with ${when (t.value(Keys.MP_PLAYERS)) { "two" -> "two players"; "three_four" -> "three or four players"; "five_eight" -> "five to eight players"; "nine_plus" -> "a large group"; else -> "several players" }}, the choice affects cost, fairness and how easily friends can connect." },
            { listOf("Client-server: consistent, fair and harder to cheat.", "Peer-to-peer or player-hosted: no server bill.") },
            { listOf("Client-server: hosting costs money and needs upkeep.", "Peer-to-peer: connections and synchronization are harder, and cheating is easier.") },
            { t ->
                val small = t.value(Keys.MP_PLAYERS) in setOf("two", "three_four") && t.value(Keys.MULTIPLAYER_MODE) != "competitive"
                if (small && t.value(Keys.BUDGET) != "flexible") "I recommend a player-hosted session for your small cooperative game, because it avoids server costs and a friend can host while the others join."
                else "I recommend dedicated servers (client-server), because a competitive or larger game needs a neutral referee to stay fair."
            },
            { "If you choose differently, I will redo the networking plan and tell you the cost." },
            "Networking is built into the foundations of the game and is very hard to change later.", true),

        Topic("ota", "Over-the-air updates",
            "Over-the-air (OTA) updates let the game change on players' devices without a new install.",
            "There are two kinds of change. Content updates replace data the game loads, like text, levels, models, textures, audio and menus. Executable-code updates change software that runs, like scripts and gameplay logic. Stores restrict the second kind, and compiled native code and engine behavior can never be changed this way; they always need a new APK (the Android install file), a new installer or a store update.",
            { t -> "For ${game(t)} on ${plat(t)}, what you want to update decides the engine and how the game is built, so it is decided before development starts." },
            { listOf("Fix problems and add content without asking players to reinstall.") },
            { listOf("A content-only updater is not a complete OTA solution if you also want to change gameplay code.", "Apple and Google restrict downloading executable code.", "It needs signing, version checks and a way to roll back a bad update.") },
            { t -> if (t.wantsCodeOta) "Because you want gameplay logic updated too, I will pick an engine and a game structure where logic lives in scripts or data the game can load, and I will tell you exactly where a store prevents it." else "I recommend a content update system with signed, checked, reversible updates, and a new install for code changes." },
            { "Without OTA, every change ships as a new install, which is simplest. If you pick it later than now, the game may need to be restructured." },
            "This must be decided before the engine and game structure. Adding it late is expensive and sometimes impossible.", true,
            more = "Publishing is manual by default and kept separate from ordinary builds and from store releases. Native code and engine changes always need a new package."),

        Topic("save_system", "Saving progress",
            "A save system stores the player's progress so it is there when they close and reopen the game.",
            "The game writes its state to a file in a safe way and reads it back on start. Each save has a version number so later updates can upgrade old saves.",
            { t -> "If ${game(t)} has progress worth keeping, losing a save feels terrible." },
            { listOf("Players can stop and continue.") }, { listOf("Bad saves can corrupt or lose progress if not written safely.") },
            { "I recommend versioned saves written safely, with a backup copy and recovery if a file is damaged." },
            { "No saving is fine for very short games, but players expect to keep progress." },
            "Save formats must stay readable after updates, so they are designed early.", true),

        Topic("store_plan", "Distribution",
            "Distribution is how players get the game: a private file, a GitHub release, Google Play, the Apple App Store or Steam.",
            "Private files and GitHub releases need no approval. Stores have rules, review times and fees, and some restrict updating code after install.",
            { t -> "How you distribute ${game(t)} decides which update options are allowed and what accounts you need." },
            { listOf("Stores reach many players.") }, { listOf("Stores add fees, review and policy rules.") },
            { "I recommend private testing builds until you decide to release, then choosing the store." },
            { "You can change the plan later, but update options may change." },
            "Store choice can limit OTA code updates.", false),

        Topic("test_delivery", "Test builds",
            "A test build is a version of the game you install to try it before any release.",
            "A private artifact is a zipped download from the build system that only you can open. A GitHub pre-release attaches the file directly on the Releases page, which is public if the repository is public. Neither is publishing to a store.",
            { t -> "This is how you will actually get ${game(t)} onto your device." },
            { listOf("Private artifacts stay private.", "A pre-release gives a direct download link.") }, { listOf("Private artifacts arrive zipped and are harder to share.", "A pre-release on a public repository is public.") },
            { "I recommend private downloads until you ask for a pre-release." },
            { "You can switch any time; nothing is published without your say-so." },
            "None; it is a delivery preference.", false),

        Topic("ci_build", "Build pipeline (CI)",
            "CI stands for continuous integration: an automatic system that builds and tests the game every time the code changes.",
            "The build system runs on a server, builds the game for each platform and reports what failed.",
            { t -> "It is how a playable file for ${plat(t)} appears without you installing developer tools." },
            { listOf("Reproducible builds and an installable file.") }, { listOf("Running builds costs build minutes, so unnecessary builds are avoided.") },
            { "I recommend GitHub Actions, with builds only when needed." }, { "Building on your own computer works but depends on that machine." },
            "Builds are tied to an exact source version so you always know what you are playing.", false),

        Topic("asset_policy", "Asset licensing",
            "Assets are the art, sounds and models in a game. A license is the permission that says how you may use them.",
            "CC0 means the creator gave up all rights, so you may use, change and redistribute it. Attribution licenses require credit. Free for personal use is not the same as free for a commercial game. Free commercial use does not necessarily allow redistributing the raw files.",
            { t -> "The license decides whether ${game(t)} can legally be released." },
            { listOf("CC0 assets are the safest.") }, { listOf("Free assets may not match your style.", "Attribution and redistribution rules differ.") },
            { "I recommend CC0 first, then original or procedural assets for gaps. I will record the source and license of every asset." },
            { "Other licenses are fine if their terms are respected; I will list the obligations." },
            "Assets are logged in a provenance ledger so licensing can be proved later.", false),

        Topic("budget", "Budget",
            "Budget is what you are willing to spend on tools, assets or services.",
            "Free engines, free assets and free hosting can produce a complete game. Servers, paid assets and store fees cost money.",
            { t -> "It decides whether ${game(t)} can use dedicated servers or paid assets." },
            { listOf("A zero budget forces simple, finishable choices.") }, { listOf("Some features, like dedicated servers, may not fit a zero budget.") },
            { "I recommend starting free and spending only where it clearly helps." }, { "A bigger budget opens more options but does not make the game finish itself." },
            "Ongoing costs, like servers, continue after release.", false),

        Topic("game_length", "Game length",
            "Game length is how long the finished game takes to play.",
            "Longer games need more levels, content and testing.",
            { t -> "Length is the biggest driver of the work needed to finish ${game(t)}." },
            { listOf("Short games are far more likely to be finished and polished.") }, { listOf("Long games need much more content and testing.") },
            { "I recommend a complete short game first, with room to grow." }, { "You can always add content later if the structure allows it." },
            "Content volume drives schedule and cost.", false),

        Topic("audience", "Audience",
            "The audience is who the game is for.",
            "It shapes difficulty, tone and how much the game explains itself.",
            { t -> "It tells me how forgiving ${game(t)} should be." },
            { listOf("A clear audience gives clear decisions.") }, { listOf("A wide audience is harder to please.") },
            { "I recommend a general audience unless you have a specific group in mind." }, { "You can tune difficulty later." },
            "Audience affects ratings and store listings later.", false),

        Topic("network_policy", "Online or offline",
            "Online/offline describes whether the game needs an internet connection.",
            "Offline games work anywhere and need no servers or accounts. Online features need connections and may cost money.",
            { t -> "It decides what ${game(t)} needs to work." },
            { listOf("Offline means no servers, no privacy worries and no bills.") }, { listOf("Online features need servers and testing.") },
            { t -> if (t.onlineMultiplayer && t.value(Keys.MULTIPLAYER_TIMING) == "prototype") "Because online play is part of your first build, the game needs a connection for that mode." else "I recommend fully offline play for the first build." },
            { "Online can be added later if the architecture allows it." },
            "Connectivity affects privacy, store rules and costs.", false),
    ).associateBy { it.id }

    /** Which topic explains which interview question. */
    private val byField: Map<String, String> = mapOf(
        Keys.DIMENSION to "dimension", Keys.ART_DIRECTION to "art_direction", Keys.PERSPECTIVE to "camera", Keys.WORLD_STRUCTURE to "procedural",
        Keys.ENGINE to "engine", Keys.PLATFORMS to "platforms", Keys.PRIMARY_PLATFORM to "primary_platform",
        Keys.MULTIPLAYER_INTENT to "multiplayer", Keys.MULTIPLAYER_TIMING to "multiplayer", Keys.MULTIPLAYER_MODE to "multiplayer",
        Keys.MP_PLAYERS to "multiplayer", Keys.MP_DEVICES to "multiplayer", Keys.MP_HOSTING to "netcode", Keys.MP_SESSION to "netcode", Keys.MP_CONNECT to "netcode", Keys.MP_CROSSPLAY to "netcode", Keys.MP_OFFLINE to "network_policy",
        Keys.OTA_UPDATES to "ota", Keys.OTA_CONTENT to "ota", Keys.OTA_BEHAVIOR to "ota",
        Keys.SAVE_SYSTEM to "save_system", Keys.STORE_PLAN to "store_plan", Keys.TEST_DELIVERY to "test_delivery", Keys.CI_BUILD to "ci_build",
        Keys.ASSET_POLICY to "asset_policy", Keys.BUDGET to "budget", Keys.GAME_LENGTH to "game_length", Keys.AUDIENCE to "audience", Keys.NETWORK_POLICY to "network_policy",
    )

    /** Only these fields are decisions where the lesson is new; the follow-ups of one topic reuse its card once. */
    fun topicFor(fieldKey: String): Topic? = byField[fieldKey]?.let { topics[it] }

    const val MARKER = "Say \"explain more\""

    fun card(topic: Topic, t: Traits, depth: Depth): String = buildString {
        val adv = depth == Depth.ADVANCED
        append("**${topic.term}.** ${topic.what}\n")
        if (!adv) append("How it works: ${topic.how}\n")
        append("Why it matters here: ${topic.why(t)}\n")
        if (!adv) topic.pros(t).takeIf { it.isNotEmpty() }?.let { append("Advantages: ${it.joinToString(" ")}\n") }
        topic.cons(t).takeIf { it.isNotEmpty() }?.let { append("Disadvantages: ${it.joinToString(" ")}\n") }
        append("Bob recommends: ${topic.recommend(t)}\n")
        append("If you choose something else: ${topic.ifElse(t)}\n")
        append("Long-term: ${topic.longTerm}${if (topic.hardToReverse && "hard" !in topic.longTerm) " This is hard to change later." else ""}\n")
        if (depth == Depth.MORE && topic.more.isNotBlank()) append("More detail: ${topic.more}\n")
        if (depth != Depth.MORE) append("($MARKER for more detail on any of this.)")
    }.trimEnd()
}
