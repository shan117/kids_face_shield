package com.shantanu.shield.webfilter

/**
 * Seed blocklists, by category.
 *
 * Deliberately a SEED, not a product-grade list. Real filtering services carry hundreds of thousands
 * of domains and update them daily; this covers the best-known sites in each category so the feature
 * is genuinely useful on day one, and the parent's own allow/block lists cover the rest.
 *
 * The honest limitation, stated in the UI too: a determined child will find a site that isn't here.
 * The next step for this file is a Remote Config override (`web_blocklist_json`) so categories can be
 * patched without an app update — the same mechanism already used for feature tiers. Until then,
 * additions ship with a release.
 *
 * Entries are bare registrable domains. Subdomains are covered automatically by
 * [DomainBlocklist.hostMatches], so `example.com` also blocks `www.example.com` and `cdn.example.com`.
 */
object WebFilterLists {

    private val ADULT = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "spankbang.com", "onlyfans.com", "stripchat.com", "chaturbate.com",
        "brazzers.com", "tnaflix.com", "eporner.com", "hentaihaven.xxx", "rule34.xxx",
        "nhentai.net", "e-hentai.org", "adultfriendfinder.com", "fetlife.com",
    )

    private val GAMBLING = setOf(
        "bet365.com", "888casino.com", "pokerstars.com", "williamhill.com", "betway.com",
        "unibet.com", "bovada.lv", "stake.com", "roobet.com", "draftkings.com",
        "fanduel.com", "dream11.com", "my11circle.com", "rummycircle.com", "parimatch.com",
    )

    private val DRUGS = setOf(
        "leafly.com", "weedmaps.com", "hightimes.com", "erowid.org", "drugs-forum.com",
        "zamnesia.com", "sensiseeds.com", "elementvape.com", "vapewild.com",
    )

    private val VIOLENCE = setOf(
        "bestgore.fun", "kaotic.com", "theync.com", "documentingreality.com",
        "watchpeopledie.tv", "seegore.com", "goregrish.com",
    )

    /**
     * Sites whose whole purpose is getting around a filter. On by default, because leaving them open
     * lets a child undo every other category in one visit.
     */
    private val PROXY_VPN = setOf(
        "proxysite.com", "hide.me", "hidemyass.com", "kproxy.com", "croxyproxy.com",
        "croxyproxy.rocks", "4everproxy.com", "whoer.net", "vpnbook.com", "psiphon.ca",
        "torproject.org", "tunnelbear.com", "windscribe.com", "protonvpn.com", "nordvpn.com",
        "expressvpn.com", "surfshark.com", "ultrasurf.us", "zalmos.com", "blockaway.net",
        "unblocksite.net", "plainproxies.com",
    )

    private val DATING = setOf(
        "tinder.com", "bumble.com", "badoo.com", "okcupid.com", "match.com",
        "hinge.co", "grindr.com", "plentyoffish.com", "ashleymadison.com", "adultfriendfinder.com",
    )

    /** Off by default — a judgement call, and over-blocking gets the whole filter switched off. */
    private val SOCIAL = setOf(
        "facebook.com", "instagram.com", "twitter.com", "x.com", "tiktok.com",
        "snapchat.com", "reddit.com", "tumblr.com", "discord.com", "telegram.org",
        "threads.net", "vk.com",
    )

    private val GAMING = setOf(
        "roblox.com", "crazygames.com", "poki.com", "miniclip.com", "y8.com",
        "friv.com", "addictinggames.com", "kongregate.com", "coolmathgames.com",
        "now.gg", "itch.io",
    )

    /** Category → domains. The single source the filter reads. */
    val BY_CATEGORY: Map<WebFilterCategory, Set<String>> = mapOf(
        WebFilterCategory.ADULT to ADULT,
        WebFilterCategory.GAMBLING to GAMBLING,
        WebFilterCategory.DRUGS to DRUGS,
        WebFilterCategory.VIOLENCE to VIOLENCE,
        WebFilterCategory.PROXY_VPN to PROXY_VPN,
        WebFilterCategory.DATING to DATING,
        WebFilterCategory.SOCIAL to SOCIAL,
        WebFilterCategory.GAMING to GAMING,
    )

    /** How many domains a category currently covers, for the parent UI. */
    fun sizeOf(category: WebFilterCategory): Int = BY_CATEGORY[category]?.size ?: 0
}
