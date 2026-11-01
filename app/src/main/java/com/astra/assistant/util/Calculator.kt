package com.astra.assistant.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object Calculator {

    private val PREFIXES = listOf(
        "what is", "what's", "whats", "how much is", "what will be", "calculate", "compute",
        "solve", "evaluate", "find", "tell me", "calc"
    )
    private val SUFFIXES = listOf("kitna hota hai", "kitna hai", "kitne hote hain", "equals", "equal")
    private val WORDS = listOf(
        "multiplied by" to " * ",
        "divided by" to " / ",
        "to the power of" to " ^ ",
        "raised to" to " ^ ",
        "times" to " * ",
        "into" to " * ",
        "plus" to " + ",
        "minus" to " - ",
        "over" to " / ",
        "guna" to " * ",
        "bhag" to " / ",
        "jod" to " + ",
        "ghata" to " - ",
        "×" to " * ",
        "÷" to " / "
    )
    private val PERCENT_OF = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s*of\\s*(\\d+(?:\\.\\d+)?)")
    private val SQUARE_ROOT = Regex("square root of\\s*(\\d+(?:\\.\\d+)?)")
    private val TIMES_X = Regex("(?<=\\d)\\s*x\\s*(?=\\d)")
    private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3})")
    private val PLAIN_NUMBER = Regex("^[+-]?\\s*[0-9.]+$")
    private const val ALLOWED = "0123456789+-*/^(). "

    // Returns a spoken answer, or null if the text is not a maths expression.
    fun tryEvaluate(input: String): String? {
        var t = input.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!', '=', ' ')
        for (p in PREFIXES) {
            if (t.startsWith("$p ")) {
                t = t.substring(p.length).trim()
                break
            }
        }
        for (s in SUFFIXES) {
            if (t.endsWith(" $s")) {
                t = t.removeSuffix(s).trim()
                break
            }
        }
        t = t.replace(PERCENT_OF, "($1/100)*$2")
        t = t.replace(SQUARE_ROOT, "sqrt($1)")
        t = t.replace("squared", "^2").replace("cubed", "^3")
        for ((word, symbol) in WORDS) {
            t = t.replace(word, symbol)
        }
        t = t.replace(TIMES_X, " * ")
        t = t.replace(THOUSANDS, "")
        t = t.trim()

        val core = t.replace("sqrt", "")
        if (core.isEmpty() || core.any { it !in ALLOWED }) return null
        if (!core.any { it.isDigit() }) return null
        val hasOperator = core.any { it in "+-*/^" } || t.contains("sqrt")
        if (!hasOperator || PLAIN_NUMBER.matches(t)) return null

        val result = try {
            Parser(t).parse()
        } catch (e: ArithmeticException) {
            return "That is not possible. It is a division by zero or an invalid square root."
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (result.isNaN() || result.isInfinite()) {
            return "That result is too large or undefined."
        }
        return "The answer is ${format(result)}."
    }

    private fun format(value: Double): String {
        val rounded = Math.round(value)
        if (Math.abs(value - rounded) < 1e-9 && Math.abs(value) < 1e15) {
            return rounded.toString()
        }
        return BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skipSpaces()
            if (pos != s.length) throw IllegalArgumentException("Unexpected input")
            return v
        }

        private fun skipSpaces() {
            while (pos < s.length && s[pos] == ' ') pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < s.length) s[pos] else null
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= power() }
                    '/' -> {
                        pos++
                        val d = power()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            return if (peek() == '^') {
                pos++
                Math.pow(base, power())
            } else {
                base
            }
        }

        private fun unary(): Double {
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> primary()
            }
        }

        private fun primary(): Double {
            skipSpaces()
            if (s.startsWith("sqrt", pos)) {
                pos += 4
                val inner = primary()
                if (inner < 0) throw ArithmeticException("Negative square root")
                return Math.sqrt(inner)
            }
            if (peek() == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing bracket")
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected a number")
            return s.substring(start, pos).toDouble()
        }
    }
}
