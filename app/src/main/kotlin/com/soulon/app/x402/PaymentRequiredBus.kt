package com.soulon.app.x402

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PaymentRequiredBus {
    private val _challenge = MutableStateFlow<X402Challenge?>(null)
    val challenge: StateFlow<X402Challenge?> = _challenge

    fun publish(challenge: X402Challenge) {
        _challenge.value = challenge
    }

    fun consume(): X402Challenge? {
        val current = _challenge.value
        _challenge.value = null
        return current
    }
}

