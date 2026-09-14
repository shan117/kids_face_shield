package com.shantanu.shield.billing

import com.shantanu.shield.billing.BillingLogic.PurchaseInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingLogicTest {

    private val premium = "premium"

    @Test
    fun `purchased verified premium grants entitlement`() {
        val purchases = listOf(PurchaseInfo(setOf("premium"), purchased = true, signatureValid = true))
        assertTrue(BillingLogic.isPremium(purchases, premium))
    }

    @Test
    fun `no purchases means not premium`() {
        assertFalse(BillingLogic.isPremium(emptyList(), premium))
    }

    @Test
    fun `pending purchase does not grant premium`() {
        val purchases = listOf(PurchaseInfo(setOf("premium"), purchased = false, signatureValid = true))
        assertFalse(BillingLogic.isPremium(purchases, premium))
    }

    @Test
    fun `invalid signature does not grant premium`() {
        val purchases = listOf(PurchaseInfo(setOf("premium"), purchased = true, signatureValid = false))
        assertFalse(BillingLogic.isPremium(purchases, premium))
    }

    @Test
    fun `different product does not grant premium`() {
        val purchases = listOf(PurchaseInfo(setOf("something_else"), purchased = true, signatureValid = true))
        assertFalse(BillingLogic.isPremium(purchases, premium))
    }

    @Test
    fun `one valid purchase among several grants premium`() {
        val purchases = listOf(
            PurchaseInfo(setOf("other"), purchased = true, signatureValid = true),
            PurchaseInfo(setOf("premium"), purchased = false, signatureValid = true),   // pending
            PurchaseInfo(setOf("premium"), purchased = true, signatureValid = true),    // active
        )
        assertTrue(BillingLogic.isPremium(purchases, premium))
    }
}
