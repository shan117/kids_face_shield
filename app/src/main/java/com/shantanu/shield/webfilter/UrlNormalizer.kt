package com.shantanu.shield.webfilter

import android.net.Uri

/**
 * URL shaping for the in-app browser: turning what a child typed into a URL, and forcing SafeSearch.
 *
 * SafeSearch enforcement is the clearest advantage of owning the browser. A DNS or VPN filter sees only
 * `google.com` and cannot touch the query, so it cannot make a search safe — it can only block the
 * search engine outright. Rewriting the URL is possible only from inside.
 */
object UrlNormalizer {

    /**
     * The browser's home page: Google, with SafeSearch switched on.
     *
     * `/webhp?safe=active` rather than plain `google.com` on purpose. SafeSearch is a parameter of the
     * SEARCH url, so opening the bare homepage would leave a search typed into Google's own box
     * unfiltered — and Google often loads results without a full page navigation, so
     * [enforceSafeSearch] below cannot be relied on to catch it. `webhp` is Google's own
     * "web home page" entry point, and the parameter there applies to searches made from the box.
     */
    const val HOME_URL = "https://www.google.com/webhp?safe=active"

    /**
     * Google, with SafeSearch forced on the way in.
     *
     * `safe=active` is applied here AND re-applied by [enforceSafeSearch] on every later navigation, so
     * a child who edits it out of the address bar, or follows a link that drops it, lands back on a
     * filtered result page rather than an unfiltered one.
     */
    private const val SEARCH_PREFIX = "https://www.google.com/search?safe=active&q="

    /**
     * What the child typed → something loadable.
     *
     * Anything that looks like a hostname becomes `https://`; anything else becomes a search. Defaults
     * to https rather than http so a typo doesn't silently downgrade to an unencrypted page.
     */
    fun toUrlOrSearch(input: String): String {
        val text = input.trim()
        // Empty input means "take me home", not "search for nothing" — an empty results page is a dead
        // end.
        if (text.isEmpty()) return HOME_URL

        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) return text

        // A single token containing a dot and no space is a hostname attempt, not a search.
        val looksLikeHost = !text.contains(' ') && text.contains('.') &&
            !text.startsWith(".") && !text.endsWith(".")
        return if (looksLikeHost) "https://$text" else SEARCH_PREFIX + Uri.encode(text)
    }

    /**
     * A SafeSearch-enforced rewrite of [uri], or null when nothing needs changing.
     *
     * Returning null rather than the same URL matters: the caller loads whatever comes back, so
     * returning an unchanged URL would restart the navigation and loop forever.
     */
    fun enforceSafeSearch(uri: Uri): String? {
        val host = DomainBlocklist.normalizeHost(uri.host) ?: return null

        return when {
            isGoogleSearch(host, uri) -> uri.withParam("safe", "active")
            host.endsWith("bing.com") && uri.path?.startsWith("/search") == true ->
                uri.withParam("adlt", "strict")
            host.endsWith("duckduckgo.com") -> uri.withParam("kp", "1")
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") ->
                // YouTube's restricted mode is a cookie/header feature, not a query parameter, so URL
                // rewriting cannot enforce it. Left alone deliberately rather than pretending: the
                // honest control for YouTube is blocking or time-limiting the app itself.
                null
            else -> null
        }
    }

    private fun isGoogleSearch(host: String, uri: Uri): Boolean {
        val isGoogle = host == "google.com" || host.startsWith("google.") ||
            host.endsWith(".google.com") || Regex("""^(www\.)?google\.[a-z.]+$""").matches(host)
        return isGoogle && (uri.path?.startsWith("/search") == true)
    }

    /** Adds [key]=[value] unless already set to that value; null when no change is needed. */
    private fun Uri.withParam(key: String, value: String): String? {
        if (getQueryParameter(key) == value) return null
        val builder = buildUpon().clearQuery()
        for (name in queryParameterNames) {
            if (name == key) continue
            for (existing in getQueryParameters(name)) builder.appendQueryParameter(name, existing)
        }
        builder.appendQueryParameter(key, value)
        return builder.build().toString()
    }
}
