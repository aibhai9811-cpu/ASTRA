package com.astra.assistant.ai

object PrivacyFilter {

    data class Result(val text: String, val redactions: Int)

    private val SECRET_KEYS = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("\\bsk-[A-Za-z0-9_\\-]{20,}"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{16,}"),
        Regex("\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]*")
    )
    private val NUMERIC_SECRET = Regex(
        "(?i)\\b(?:otp|cvv|cvc|upi pin|atm pin|mpin|verification code|security code|pin)\\b\\D{0,15}\\d{3,8}\\b"
    )
    private val WORD_SECRET = Regex(
        "(?i)\\b(?:password|passcode|passphrase)\\b\\s*(?:is|are|:|=|hai|h)\\s*\\S+"
    )
    private val TRAILING_OTP = Regex(
        "(?i)\\b\\d{4,8}\\b(?=[^\\n]{0,30}\\b(?:otp|verification code)\\b)"
    )
    private val LONG_NUMBER = Regex("\\b\\d(?:[ -]?\\d){11,18}\\b")
    private val PAN = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![\\d])(?:\\+91[ -]?|0)?[6-9]\\d{9}(?![\\d])")

    fun filter(input: String, redactContactInfo: Boolean = true): Result {
        var text = input
        var count = 0

        fun replaceAll(regex: Regex, label: String) {
            text = regex.replace(text) {
                count++
                label
            }
        }

        for (pattern in SECRET_KEYS) replaceAll(pattern, "[REDACTED]")
        replaceAll(NUMERIC_SECRET, "[REDACTED]")
        replaceAll(WORD_SECRET, "[REDACTED]")
        replaceAll(TRAILING_OTP, "[REDACTED]")
        replaceAll(LONG_NUMBER, "[NUMBER]")
        replaceAll(PAN, "[ID]")
        if (redactContactInfo) {
            replaceAll(EMAIL, "[EMAIL]")
            replaceAll(PHONE, "[PHONE]")
        }
        return Result(text, count)
    }
}
