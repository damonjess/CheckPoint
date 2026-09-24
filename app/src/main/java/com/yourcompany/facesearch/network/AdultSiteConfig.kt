package com.yourcompany.facesearch.network

object AdultSiteConfig {
    /** Expanded adult platforms searched via in-app WebView & Termux. */
    val SITES = listOf(
        "theadulthub.com",
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
        "chaturbate.com",
        "camsoda.com",
        "bongacams.com",
        "stripchat.com",
        "cam4.com",
        "myfreecams.com",
        "loyalfans.com",
        "manyvids.com",
        "clips4sale.com",
        "fancentro.com",
        "javlibrary.com",
        "javmost.com",
        "sankakucomplex.com",
        "rule34.xxx",
        "hentai-foundry.com",
        "nhentai.net",
        "xvideos2.com",
        "sunporno.com",
        "gotporn.com",
        "vporn.com",
        "daftsex.com",
    )

    fun labelFor(domain: String): String = when {
        domain.contains("theadulthub") -> "TheAdultHub"
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
        domain.contains("camsoda") -> "CamSoda"
        domain.contains("bongacams") -> "BongaCams"
        domain.contains("stripchat") -> "Stripchat"
        domain.contains("cam4") -> "Cam4"
        domain.contains("myfreecams") -> "MyFreeCams"
        domain.contains("loyalfans") -> "LoyalFans"
        domain.contains("manyvids") -> "ManyVids"
        domain.contains("clips4sale") -> "Clips4Sale"
        domain.contains("fancentro") -> "FanCentro"
        domain.contains("javlibrary") -> "JAVLibrary"
        domain.contains("javmost") -> "JAVMost"
        domain.contains("sankakucomplex") -> "SankakuComplex"
        domain.contains("rule34") -> "Rule34"
        domain.contains("hentai-foundry") -> "HentaiFoundry"
        domain.contains("nhentai") -> "nHentai"
        domain.contains("sunporno") -> "SunPorno"
        domain.contains("gotporn") -> "GotPorn"
        domain.contains("vporn") -> "VPorn"
        domain.contains("daftsex") -> "DaftSex"
        else -> domain.substringBefore('.').replaceFirstChar { it.uppercase() }
    }
}

