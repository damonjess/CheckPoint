package com.yourcompany.facesearch.network

object FreeSocialFinder {

    fun generateProfileUrls(hint: String): List<Pair<String, String>> {
        val clean = hint.lowercase()
            .replace(Regex("[^a-z0-9.\\s_-]"), "")
            .trim()

        if (clean.isBlank() || clean.length < 2) return emptyList()

        val noSpaces = clean.replace(" ", "")
        val dotFmt = clean.replace(" ", ".")
        val underFmt = clean.replace(" ", "_")
        val hypFmt = clean.replace(" ", "-")

        val urls = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun add(platform: String, url: String) {
            if (url !in seen) {
                seen.add(url)
                urls.add(platform to url)
            }
        }

        add("Instagram",  "https://instagram.com/$noSpaces")
        add("Instagram",  "https://instagram.com/$dotFmt")
        add("Instagram",  "https://instagram.com/$underFmt")
        add("Twitter/X",  "https://x.com/$noSpaces")
        add("Twitter/X",  "https://twitter.com/$noSpaces")
        add("Facebook",   "https://facebook.com/$dotFmt")
        add("Facebook",   "https://facebook.com/$noSpaces")
        add("LinkedIn",   "https://linkedin.com/in/$hypFmt")
        add("LinkedIn",   "https://linkedin.com/in/$dotFmt")
        add("GitHub",     "https://github.com/$noSpaces")
        add("GitHub",     "https://github.com/$hypFmt")
        add("TikTok",     "https://tiktok.com/@$noSpaces")
        add("TikTok",     "https://tiktok.com/@$dotFmt")
        add("Reddit",     "https://reddit.com/user/$noSpaces")
        add("YouTube",    "https://youtube.com/@$noSpaces")
        add("YouTube",    "https://youtube.com/@$dotFmt")
        add("Pinterest",  "https://pinterest.com/$noSpaces")
        add("Telegram",   "https://t.me/$noSpaces")
        add("Telegram",   "https://t.me/$underFmt")
        add("Twitch",     "https://twitch.tv/$noSpaces")

        return urls
    }
}
