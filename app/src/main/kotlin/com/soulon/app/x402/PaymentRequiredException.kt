package com.soulon.app.x402

class PaymentRequiredException(val challenge: X402Challenge) : RuntimeException("payment_required")

