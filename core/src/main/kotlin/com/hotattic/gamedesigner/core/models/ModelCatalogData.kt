package com.hotattic.gamedesigner.core.models

/**
 * Catalog compiled into the app. Every size and SHA-256 below was read from the host's LFS metadata by CI
 * (tools/models/discover.py -> release `model-catalog`, generated 2026-10-05T12:54:50Z); none was typed by hand. Only ungated models under
 * permissive licenses are listed, using the generic CPU build of each (no vendor-specific or web/GPU-only variants).
 * Speed/quality are relative guidance for the LiteRT-LM CPU backend, memory figures come from the publishers' benchmarks where they exist.
 */
object ModelCatalogData {
    val entries: List<ModelEntry> = listOf(
        ModelEntry("qwen3-0.6b-fast", "Qwen3", "Qwen3 0.6B (fast, basic)", "0.6B instruct, no-think", "litertlm", "int4 (block32)", "litert-community/Qwen3-0.6B-int4", "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm", 347251840L, "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139", "apache-2.0", "https://huggingface.co/litert-community/Qwen3-0.6B-int4", gated = false,
            minTotalRamMb = 3072, runtimeRamMb = 1100, contextTokens = 1280, speed = 5, quality = 1,
            note = "Very fast and tiny. Understands plain answers; the rule-based safety net does more of the work. Pick this only if nothing bigger fits."),
        ModelEntry("qwen3-1.7b", "Qwen3", "Qwen3 1.7B", "1.7B hybrid reasoning", "litertlm", "int4 weights, dynamic (wi4b32)", "litert-community/Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977184032L, "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b", "apache-2.0", "https://huggingface.co/litert-community/Qwen3-1.7B", gated = false,
            minTotalRamMb = 4096, runtimeRamMb = 2300, contextTokens = 4096, speed = 4, quality = 3,
            note = "A good small all-rounder for phones with 4 to 6 GB of RAM."),
        ModelEntry("gemma-4-e2b", "Gemma 4", "Gemma 4 E2B", "E2B instruct", "litertlm", "mixed 2/4/8-bit (QAT)", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", "apache-2.0", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm", gated = false,
            minTotalRamMb = 6144, runtimeRamMb = 1900, contextTokens = 32768, speed = 4, quality = 4,
            note = "Recommended for most modern phones: strong instruction following at about 1.7 GB of working memory on CPU (Google's published benchmark)."),
        ModelEntry("gemma-4-e4b", "Gemma 4", "Gemma 4 E4B", "E4B instruct", "litertlm", "mixed 2/4/8-bit (QAT)", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", 3659530240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", "apache-2.0", "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm", gated = false,
            minTotalRamMb = 8192, runtimeRamMb = 3200, contextTokens = 32768, speed = 3, quality = 5,
            note = "The best understanding this catalog offers for phones with 8 GB of RAM or more; slower to answer."),
        ModelEntry("qwen3-4b-instruct", "Qwen3", "Qwen3 4B Instruct (2507)", "4B instruct", "litertlm", "mixed int4", "litert-community/Qwen3-4B-Instruct-2507", "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm", 2659057664L, "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd", "apache-2.0", "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507", gated = false,
            minTotalRamMb = 10240, runtimeRamMb = 5200, contextTokens = 32768, speed = 2, quality = 4,
            note = "Strong, but heavy on the CPU backend (5 GB or more of memory); offered only on phones with 10 GB+ RAM."),
    )
}
