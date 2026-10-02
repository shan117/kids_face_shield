package com.shantanu.shield.webfilter

/**
 * What the in-app browser can block, by kind. See WEB_FILTER_PLAN.md Phase 2.
 *
 * [defaultOn] is what a parent gets before touching anything: the categories almost every family
 * wants. The judgement-call categories (social, gaming) default off — a filter that blocks more than
 * the parent expected gets switched off wholesale, which protects nobody.
 */
enum class WebFilterCategory(
    val label: String,
    val description: String,
    val defaultOn: Boolean,
) {
    ADULT("Adult content", "Pornography and explicit material", defaultOn = true),
    GAMBLING("Gambling", "Betting, casinos and lotteries", defaultOn = true),
    DRUGS("Drugs and alcohol", "Recreational drugs, vaping and alcohol", defaultOn = true),
    VIOLENCE("Violence and gore", "Graphic violence and shock sites", defaultOn = true),
    // On by default because it protects every other category: these are the sites a child uses to
    // route around the filter itself.
    PROXY_VPN("Proxies and VPNs", "Sites used to get around filters", defaultOn = true),
    DATING("Dating", "Dating and hook-up sites", defaultOn = true),
    SOCIAL("Social networks", "Social media sites in the browser", defaultOn = false),
    GAMING("Gaming sites", "Browser game portals", defaultOn = false);

    companion object {
        val DEFAULT_ON: Set<WebFilterCategory> = entries.filter { it.defaultOn }.toSet()

        /** Tolerant lookup for the remote-command payload; unknown names are ignored, not fatal. */
        fun fromName(name: String): WebFilterCategory? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Per-category counts of refused navigations, on the wire.
 *
 * Counts, never domains. A list of the sites a child tried to reach is exactly the kind of content
 * this app promises never to send off-device, so the weekly report can carry "9 adult blocks" and
 * nothing more specific. Same delimiter convention as the other codecs; pure and testable.
 */
object WebBlockCountCodec {
    private val US = Char(31)
    private val RS = Char(30)

    fun encode(counts: Map<WebFilterCategory, Int>): String =
        counts.entries
            .filter { it.value > 0 }
            .joinToString(RS.toString()) { "${it.key.name}$US${it.value}" }

    fun decode(raw: String): Map<WebFilterCategory, Int> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(RS).mapNotNull { record ->
            val parts = record.split(US)
            if (parts.size < 2) return@mapNotNull null
            val category = WebFilterCategory.fromName(parts[0]) ?: return@mapNotNull null
            val count = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            category to count
        }.toMap()
    }

    /** Total across categories, for the one-line summary. */
    fun total(counts: Map<WebFilterCategory, Int>): Int = counts.values.sum()
}

/** Why a page was allowed or refused. Carries the reason so the block page can explain itself. */
sealed interface FilterDecision {
    data object Allowed : FilterDecision
    /** Blocked because [category] is switched on and the host is in its list. */
    data class BlockedByCategory(val category: WebFilterCategory) : FilterDecision
    /** Blocked because the parent added this site by hand. */
    data object BlockedByParent : FilterDecision
    /** Refused because the scheme isn't web traffic — see [DomainBlocklist.isWebScheme]. */
    data object BlockedScheme : FilterDecision

    val isBlocked: Boolean get() = this !is Allowed
}

/**
 * The filtering decision, as pure logic.
 *
 * Kept free of Android and of any I/O so the two rules that decide whether this feature works at all —
 * suffix matching and precedence — are unit-testable. Getting either subtly wrong produces a filter
 * that looks like it works and doesn't: `notexample.com` silently matching `example.com`, or a parent's
 * explicit allow being ignored.
 */
object DomainBlocklist {

    /** Schemes the in-app browser will load. Everything else is refused — see [isWebScheme]. */
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * Whether the browser should load this scheme at all.
     *
     * This is the single most important check in the whole feature, and it is not about content. A page
     * can hand Android `intent://…#Intent;package=com.android.chrome;end` and walk straight out of the
     * filtered browser into an unfiltered one. Same for `market://`, `tel:`, custom app links. Refusing
     * every non-web scheme closes the escape; allowing any of them makes the rest of this file theatre.
     */
    fun isWebScheme(scheme: String?): Boolean = scheme?.lowercase() in WEB_SCHEMES

    /**
     * Lowercase bare hostname, or null when there isn't one.
     *
     * Strips the port and any trailing root dot, both of which would otherwise defeat exact matching.
     * Deliberately does NOT strip `www.` — suffix matching already covers it, and stripping would make
     * an allowlist entry for `www.example.com` quietly cover the whole domain.
     */
    fun normalizeHost(raw: String?): String? {
        val host = raw?.trim()?.lowercase()?.substringBefore('/')?.substringBefore(':')
            ?.removeSuffix(".")
            ?: return null
        return host.ifBlank { null }
    }

    /**
     * True when [host] is [domain] or any subdomain of it.
     *
     * Label-aware on purpose: a naive `endsWith` would make `notexample.com` match `example.com`, and
     * `example.com.evil.net` match too. Both are the difference between a filter and a false sense of
     * one.
     */
    fun hostMatches(host: String, domain: String): Boolean {
        if (host == domain) return true
        return host.endsWith(".$domain")
    }

    /** True when [host] is covered by any entry in [domains]. */
    fun matchesAny(host: String, domains: Set<String>): Boolean =
        domains.any { hostMatches(host, it) }

    /**
     * Decide one navigation.
     *
     * Precedence, highest first:
     *  1. **Parent allowlist** — an explicit "yes, this one" always wins, so a parent can unblock a
     *     school site inside an otherwise-blocked category without turning the category off.
     *  2. **Parent blocklist** — an explicit "no, this one".
     *  3. **Enabled categories.**
     *
     * Allow-over-block is the deliberate choice: the failure mode of the other order is a parent
     * adding an exception, seeing nothing change, and concluding the app is broken.
     */
    fun decide(
        host: String?,
        enabledCategories: Set<WebFilterCategory>,
        categoryDomains: Map<WebFilterCategory, Set<String>>,
        parentAllowed: Set<String> = emptySet(),
        parentBlocked: Set<String> = emptySet(),
    ): FilterDecision {
        val normalized = normalizeHost(host) ?: return FilterDecision.Allowed

        if (matchesAny(normalized, parentAllowed)) return FilterDecision.Allowed
        if (matchesAny(normalized, parentBlocked)) return FilterDecision.BlockedByParent

        // Stable order so the block page names the same category every time for a host that appears in
        // more than one list.
        for (category in WebFilterCategory.entries) {
            if (category !in enabledCategories) continue
            val domains = categoryDomains[category] ?: continue
            if (matchesAny(normalized, domains)) return FilterDecision.BlockedByCategory(category)
        }
        return FilterDecision.Allowed
    }
}
