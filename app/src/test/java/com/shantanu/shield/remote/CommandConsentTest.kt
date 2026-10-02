package com.shantanu.shield.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent boundary between the two child-side switches.
 *
 * The property worth protecting is the second test below: turning on location sharing must never, for
 * any command type, hand a parent a power the child declined. A child who switched off "Allow remote
 * control" was promised their phone cannot be locked remotely — and that promise must not quietly
 * depend on which *other* switches they touched afterwards.
 */
class CommandConsentTest {

    private val controllingCommands = CommandType.entries.filter { it != CommandType.REQUEST_LOCATION }

    @Test
    fun `controlling commands need remote control`() {
        for (type in controllingCommands) {
            assertTrue(
                "$type should be permitted with remote control on",
                CommandConsent.isPermitted(type, remoteControlEnabled = true, locationSharingEnabled = false),
            )
            assertFalse(
                "$type must be refused with remote control off",
                CommandConsent.isPermitted(type, remoteControlEnabled = false, locationSharingEnabled = false),
            )
        }
    }

    @Test
    fun `location sharing does NOT unlock any controlling command`() {
        // The safety property. If this ever fails, a child who consented only to location sharing can
        // have their phone locked, time-limited or reconfigured by the parent.
        for (type in controllingCommands) {
            assertFalse(
                "$type must stay refused when only location sharing is on",
                CommandConsent.isPermitted(type, remoteControlEnabled = false, locationSharingEnabled = true),
            )
        }
    }

    @Test
    fun `a location request is permitted by location sharing alone`() {
        // The fix: location consent stands on its own, so the child need not also enable remote
        // control — an unrelated switch — for the feature they actually agreed to.
        assertTrue(
            CommandConsent.isPermitted(
                CommandType.REQUEST_LOCATION,
                remoteControlEnabled = false,
                locationSharingEnabled = true,
            )
        )
    }

    @Test
    fun `a location request is permitted by remote control alone, so the child can answer NO`() {
        // Deliberate: the command is delivered even when location sharing is OFF, so LocationResponder
        // can reply NOT_CONSENTED. Refusing it here instead would leave the parent with an unexplained
        // timeout and no way to tell "declined" from "no signal".
        assertTrue(
            CommandConsent.isPermitted(
                CommandType.REQUEST_LOCATION,
                remoteControlEnabled = true,
                locationSharingEnabled = false,
            )
        )
    }

    @Test
    fun `nothing at all is permitted when the child has consented to nothing`() {
        for (type in CommandType.entries) {
            assertFalse(
                "$type must be refused with both switches off",
                CommandConsent.isPermitted(type, remoteControlEnabled = false, locationSharingEnabled = false),
            )
        }
    }

    @Test
    fun `every command type is covered by an explicit rule`() {
        // Guards the `else` branch: a future command type silently inheriting "needs remote control" is
        // the safe default, but this asserts the set is knowingly partitioned rather than accidentally.
        val locationTypes = CommandType.entries.filter {
            CommandConsent.isPermitted(it, remoteControlEnabled = false, locationSharingEnabled = true)
        }
        assertTrue(
            "only REQUEST_LOCATION may be unlocked by location sharing, found: $locationTypes",
            locationTypes == listOf(CommandType.REQUEST_LOCATION),
        )
    }
}
