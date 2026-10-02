package com.shantanu.shield.webfilter

/**
 * Web filtering, Phase 0 — see WEB_FILTER_PLAN.md.
 *
 * Android's own **Private DNS** setting points the whole device at a DNS resolver of your choosing.
 * Some resolvers refuse to answer for adult/malware domains, so setting one filters every app on the
 * phone with no filtering code, no VPN and no battery cost.
 *
 * The app cannot set it: `Settings.Global.private_dns_*` needs `WRITE_SECURE_SETTINGS`, and the
 * programmatic route (`DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost`) is Device Owner only,
 * which requires a factory-reset provisioning this app can never perform. So the parent types the
 * hostname once, and our job is to guide it, verify it, and notice if it stops.
 */
enum class DnsFilterProvider(
    val label: String,
    val hostname: String,
    val blurb: String,
) {
    CLEANBROWSING_FAMILY(
        label = "CleanBrowsing Family",
        hostname = "family-filter-dns.cleanbrowsing.org",
        // Blocking the proxy/VPN sites a child would use to escape the filter is the distinguishing
        // feature here, and the reason this is the recommended default.
        blurb = "Adult and explicit sites, plus the proxy and VPN sites used to get around filters. " +
            "Also blocks mixed-content sites like Reddit.",
    ),
    ADGUARD_FAMILY(
        label = "AdGuard Family",
        hostname = "family.adguard-dns.com",
        blurb = "Adult sites, plus ads and trackers. Lighter than CleanBrowsing — Reddit stays open.",
    ),
    CLOUDFLARE_FAMILY(
        label = "Cloudflare for Families",
        hostname = "family.cloudflare-dns.com",
        blurb = "Malware and adult sites. The most permissive of the three, and the fastest.",
    );

    companion object {
        /** What a parent should pick unless they have a reason not to. */
        val RECOMMENDED = CLEANBROWSING_FAMILY
    }
}

/**
 * What the device's Private DNS is currently doing.
 *
 * [ActiveUnfiltered] is the state worth having a name for. Private DNS being *on* does not mean
 * filtering is on — a resolver like `dns.google` is encrypted but answers every query honestly. Telling
 * a parent "filtering is active" in that case would be a straight-up false assurance about their
 * child's safety, which is worse than saying nothing.
 */
sealed interface PrivateDnsState {
    /** Android 9 or older, or the state can't be read. Verification impossible; instructions only. */
    data object Unsupported : PrivateDnsState
    /** No Private DNS configured — the device uses whatever resolver the network hands it. */
    data object Off : PrivateDnsState
    /** Private DNS is on, but pointed at a resolver we don't recognise as filtering. */
    data class ActiveUnfiltered(val hostname: String) : PrivateDnsState
    /** Private DNS is on and pointed at a known family-filtering resolver. */
    data class ActiveFiltered(val provider: DnsFilterProvider) : PrivateDnsState

    val isFiltering: Boolean get() = this is ActiveFiltered
}

/** Pure mapping from a raw Private DNS reading to a [PrivateDnsState]. Unit-testable with no Android. */
object PrivateDnsStates {

    /** The provider matching [hostname], or null when it isn't one we know filters. */
    fun providerFor(hostname: String?): DnsFilterProvider? {
        val host = hostname?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        if (host.isEmpty()) return null
        return DnsFilterProvider.entries.firstOrNull { it.hostname.equals(host, ignoreCase = true) }
    }

    /**
     * Interpret a reading.
     *
     * @param supported false on API < 29, where [android.net.LinkProperties.isPrivateDnsActive] does
     *   not exist — we cannot verify, and must not guess.
     * @param active whether Private DNS is switched on.
     * @param hostname the configured hostname; null in "opportunistic"/automatic mode, which upgrades
     *   to encryption where available but performs no filtering.
     */
    fun of(supported: Boolean, active: Boolean, hostname: String?): PrivateDnsState = when {
        !supported -> PrivateDnsState.Unsupported
        !active -> PrivateDnsState.Off
        else -> {
            val provider = providerFor(hostname)
            when {
                provider != null -> PrivateDnsState.ActiveFiltered(provider)
                // Automatic/opportunistic mode reports active with no hostname. Encrypted, not
                // filtered — reported honestly as unfiltered rather than as a win.
                else -> PrivateDnsState.ActiveUnfiltered(hostname?.takeIf { it.isNotBlank() } ?: "automatic")
            }
        }
    }
}
