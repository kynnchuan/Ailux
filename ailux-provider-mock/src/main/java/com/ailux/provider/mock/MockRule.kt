package com.ailux.provider.mock

/**
 * A single mock rule used by [MockProvider].
 *
 * @param keyword Substring matched against the request prompt. An empty
 *                string marks this rule as the fallback used when no other
 *                keyword matches.
 * @param reply The reply text emitted token-by-token by streaming generation
 *              and returned as-is by non-streaming generation.
 * @param reasoning Optional reasoning text emitted before [reply] as
 *                  [com.ailux.core.model.LLMEvent.Reasoning] events.
 */
data class MockRule(val keyword: String,
    val reply: String,
    val reasoning: String? = null)

/**
 * Built-in default rules used when no custom list is supplied to
 * [MockProvider]. Covers a couple of common prompts plus a generic
 * fallback greeting.
 */
fun defaultRules(): List<MockRule> = listOf(
    MockRule("weather", "Based on your question, here is today's weather (June 2, 2026): mostly cloudy to sunny, hot, and a high-temperature warning is in effect.\n\n" +
            "Today's details:\n\n" +
            "\uD83C\uDF24\uFE0F Conditions: mostly cloudy to sunny during the day, with a high-temperature warning.\n\n" +
            "\uD83C\uDF21\uFE0F Temperature: roughly 27°C to 33°C throughout the day. The bureau issued a city-wide yellow high-temperature warning at 11:00 AM; central, northern and densely populated areas may reach 35°C or higher and feel very hot.\n\n" +
            "\uD83D\uDCA8 Wind: southerly to south-westerly during the day, force 2 to 3-4.\n\n" +
            "\uD83C\uDF05 Sunrise / sunset: 05:39 / 19:04.\n\n" +
            "The forecast suggests the heat will continue through June 5; expect hot weather over the next several days."),
    MockRule("model", "I'm the latest DeepSeek model, built by DeepSeek.\n" +
            "If you're asking about a specific version number or technical detail, there is no public version label (such as \"DeepSeek-V4\") at the moment. You can think of me as the current iteration in the DeepSeek model family.\n\n" +
            "Highlights:\n\n" +
            "Pure text model. I can read links but cannot directly perceive multimodal content (e.g. \"see\" image content). I can ingest images, PDF, Word, Excel, PPT files and extract text from them.\n\n" +
            "Context length up to 1M tokens — enough to handle a corpus the size of the Three-Body trilogy in one shot.\n\n" +
            "Web search is supported, but you need to enable it manually via the search button on the Web or App.\n\n" +
            "Completely free, with App and Web clients; the App also supports voice input.\n\n" +
            "Anything else you'd like me to dig into?"),
    MockRule("Hi", "Hi there! Happy to help. What can I do for you?", reasoning = "The user sent a simple greeting, which is a very common opening. They are most likely just being polite or testing whether I'm online. The underlying need is to get a friendly, natural response and start a conversation.\n\n" +
            "I'll respond in kind to keep things warm. A short self-introduction lets the user know who I am and what I can do. Closing with an open question invites them to share their actual ask.\n\n" +
            "The reply should be concise, friendly and helpful — no elaborate structure needed. Saying \"Hi there! Happy to help.\" then briefly introducing myself and asking \"What can I do for you?\" should match the user's expectations.\n\n")
)
