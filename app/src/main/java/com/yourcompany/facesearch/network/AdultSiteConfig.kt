package com.yourcompany.facesearch.network

object AdultSiteConfig {
    /** Expanded adult platforms searched via in-app WebView (no Termux). */
    val SITES = listOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "spankbang.com",
        "eporner.com",
        "beeg.com",
        "tnaflix.com",
        "thumbzilla.com",
        "motherless.com",
        "tube8.com",
        "cumlouder.com",
        "hqporner.com",
        "porntrex.com",
        "txxx.com",
        "onlyfans.com",
        "fansly.com",
        "chaturbate.com"
    )

    fun labelFor(domain: String): String = when {
        domain.contains("pornhub") -> "Pornhub"
        domain.contains("xvideos") -> "XVideos"
        domain.contains("xnxx") -> "XNXX"
        domain.contains("xhamster") -> "xHamster"
        domain.contains("redtube") -> "RedTube"
        domain.contains("youporn") -> "YouPorn"
        domain.contains("spankbang") -> "SpankBang"
        domain.contains("eporner") -> "Eporner"
        domain.contains("beeg") -> "Beeg"
        domain.contains("tnaflix") -> "TNAFlix"
        domain.contains("thumbzilla") -> "Thumbzilla"
        domain.contains("motherless") -> "Motherless"
        domain.contains("tube8") -> "Tube8"
        domain.contains("cumlouder") -> "CumLouder"
        domain.contains("hqporner") -> "HQPornEr"
        domain.contains("porntrex") -> "PornTrex"
        domain.contains("txxx") -> "TXXX"
        domain.contains("onlyfans") -> "OnlyFans"
        domain.contains("fansly") -> "Fansly"
        domain.contains("chaturbate") -> "Chaturbate"
        else -> domain.substringBefore('.').replaceFirstChar { it.uppercase() }
    }
}
