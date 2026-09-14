package com.shantanu.shield.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the parent has proven their identity with a face scan during this app session.
 *
 * On a kid-owned device the parent is the only person allowed to change what counts as screen time —
 * otherwise a child could simply hide their own YouTube time. [AppLockGate] already performs exactly
 * the right check when "Lock this app" is armed (the enrolled face is the PARENT's), so when that
 * gate passes we record it here and parent-only controls open without a second scan.
 *
 * When app-lock is NOT armed, nothing has been proven — so a parent-only control must run its own
 * scan on demand. That is what [ParentGate] does.
 *
 * Deliberately process-scoped and reset on every ON_STOP (see [AppLockGate]): leaving the app drops
 * the proof, so a child picking the phone up afterwards starts locked out again.
 */
object ParentAuthSession {

    private val _authenticated = MutableStateFlow(false)

    /** True once a parent face has matched in this session; cleared when the app is backgrounded. */
    val authenticated: StateFlow<Boolean> = _authenticated.asStateFlow()

    fun markAuthenticated() { _authenticated.value = true }

    fun clear() { _authenticated.value = false }
}
