package com.soulon.app.x402

import java.util.concurrent.atomic.AtomicReference

object X402ChallengeStore {
    private val last = AtomicReference<X402Challenge?>(null)

    fun set(challenge: X402Challenge) {
        last.set(challenge)
    }

    fun pop(): X402Challenge? = last.getAndSet(null)
}

