package com.shantanu.shield.ui.paywall

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionFormatTest {

    @Test
    fun `monthly period`() {
        assertEquals("Monthly", SubscriptionFormat.planTitle("P1M"))
        assertEquals("/month", SubscriptionFormat.periodSuffix("P1M"))
    }

    @Test
    fun `annual period as P1Y`() {
        assertEquals("Annual", SubscriptionFormat.planTitle("P1Y"))
        assertEquals("/year", SubscriptionFormat.periodSuffix("P1Y"))
    }

    @Test
    fun `annual period expressed as P12M is still annual`() {
        assertEquals("Annual", SubscriptionFormat.planTitle("P12M"))
        assertEquals("/year", SubscriptionFormat.periodSuffix("P12M"))
    }

    @Test
    fun `trial labels`() {
        assertEquals("30-day free trial", SubscriptionFormat.trialLabel("P30D"))
        assertEquals("1-week free trial", SubscriptionFormat.trialLabel("P1W"))
        assertEquals("1-month free trial", SubscriptionFormat.trialLabel("P1M"))
    }

    @Test
    fun `unparseable trial falls back`() {
        assertEquals("Free trial", SubscriptionFormat.trialLabel("P"))
    }
}
