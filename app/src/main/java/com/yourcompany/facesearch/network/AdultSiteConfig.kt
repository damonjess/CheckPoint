package com.yourcompany.facesearch.network

object AdultSiteConfig {
    /** Ten adult platforms searched via in-app WebView (no Termux). */
    val SITES = listOf(
        "pornhub.com",
        "xvideos.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "spankbang.com",
        "eporner.com",
        "onlyfans.com",
        "fansly.com",
        "chaturbate.com"
    )

    fun labelFor(domain: String): String = when {
        domain.contains("pornhub") -> "Pornhub"
        domain.contains("xvideos") -> "XVideos"
        domain.contains("xhamster") -> "xHamster"
        domain.contains("redtube") -> "RedTube"
        domain.contains("youporn") -> "YouPorn"
        domain.contains("spankbang") -> "SpankBang"
        domain.contains("eporner") -> "Eporner"
        domain.contains("onlyfans") -> "OnlyFans"
        domain.contains("fansly") -> "Fansly"
        domain.contains("chaturbate") -> "Chaturbate"
        else -> domain.substringBefore('.').replaceFirstChar { it.uppercase() }
    }
}
