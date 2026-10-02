package com.shantanu.shield.remote

/**
 * A parent telling a paired child device "you are covered until this moment".
 *
 * One payment on the parent's phone has to cover the whole family, but every premium feature runs on the
 * CHILD's device — which made no purchase, so its own `isPremium` is false. This is how the entitlement
 * crosses the gap. See ENTITLEMENT_SHARING_PLAN.md.
 */
data class PremiumGrant(val premiumUntilMs: Long)

/**
 * Lease arithmetic, kept pure.
 *
 * Deliberately a *lease* and not a subscription end date: the end date does not exist on the device.
 * `expiryTime` lives only in the server-side Play Developer API, and `purchaseTime` is the signup time
 * which never moves on renewal — so no client-side arithmetic can derive when a period ends. What the
 * device CAN answer, accurately and through refunds, grace periods, holds and plan changes, is "is there
 * an active subscription right now". So the parent answers that daily and pushes the expiry out again.
 *
 * Plan-agnostic by construction: nothing here looks at the billing period, so monthly, annual and trial
 * all behave identically.
 */
object PremiumGrants {

    /**
     * How long a grant stays valid without renewal.
     *
     * This single number sets BOTH how long a child's phone keeps working offline AND how long a
     * cancelled family keeps premium — they are the same window, and no value makes one short and the
     * other long.
     *
     * Seven days leans slightly generous on purpose. Over-granting costs a little revenue; wrongly
     * dropping premium leaves a child's phone unfiltered and unlimited, and the parent would never know
     * it happened.
     */
    const val LEASE_MS = 7L * 24 * 60 * 60 * 1000

    /** The grant a premium parent issues now. */
    fun renew(nowMs: Long): PremiumGrant = PremiumGrant(nowMs + LEASE_MS)

    /**
     * Whether [grant] still covers [nowMs].
     *
     * Evaluated on every read rather than cached as a boolean, so an expiry can never be "remembered"
     * as still valid after it passes.
     */
    fun isValid(grant: PremiumGrant?, nowMs: Long): Boolean =
        grant != null && nowMs < grant.premiumUntilMs

    /** Convenience for the stored millis form. */
    fun isValid(premiumUntilMs: Long, nowMs: Long): Boolean = nowMs < premiumUntilMs
}

/**
 * Wire format. A single field, but it gets a codec for the same reasons the others do: no JSON
 * dependency, pure, and tolerant of a future field being appended.
 */
object PremiumGrantCodec {
    private val US = Char(31)

    fun encode(grant: PremiumGrant): String = grant.premiumUntilMs.toString()

    /** Null for anything unparseable — a corrupt grant must read as "no grant", never as "valid". */
    fun decode(raw: String): PremiumGrant? {
        if (raw.isBlank()) return null
        val until = raw.split(US).firstOrNull()?.trim()?.toLongOrNull() ?: return null
        if (until <= 0L) return null
        return PremiumGrant(until)
    }
}
