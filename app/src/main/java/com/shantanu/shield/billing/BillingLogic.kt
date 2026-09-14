package com.shantanu.shield.billing

/**
 * Pure, Android-free entitlement decision from a set of purchases. Extracted so the "does the user
 * own premium?" rule can be unit-tested without the Billing SDK. [BillingManager] maps real
 * `Purchase` objects to [PurchaseInfo] and delegates here.
 */
object BillingLogic {

    /** The minimal shape we need from a Play purchase to decide entitlement. */
    data class PurchaseInfo(
        val productIds: Set<String>,
        val purchased: Boolean,        // PurchaseState == PURCHASED (not PENDING/UNSPECIFIED)
        val signatureValid: Boolean,   // local signature check passed (or verification disabled)
    )

    /**
     * Premium is active when at least one PURCHASED, signature-valid entry contains [productId].
     * PENDING or unverified purchases do NOT grant premium.
     */
    fun isPremium(purchases: List<PurchaseInfo>, productId: String): Boolean =
        purchases.any { it.purchased && it.signatureValid && productId in it.productIds }
}
