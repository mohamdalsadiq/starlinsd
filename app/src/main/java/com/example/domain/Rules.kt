package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Money {
    fun normalize(value: String): String = buildString {
        value.trim().forEach { c -> append(when {
            c in '٠'..'٩' -> '0' + (c - '٠')
            c in '۰'..'۹' -> '0' + (c - '۰')
            c == '٫' -> '.'
            else -> c
        }) }
    }
    fun parse(value: String): Long? = try {
        val v = normalize(value)
        if (Regex("\\d{1,9}(\\.\\d{1,2})?").matches(v)) BigDecimal(v).movePointRight(2).longValueExact() else null
    } catch (_: ArithmeticException) { null }
    fun show(minor: Long): String = BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
    fun bankToCash(amount: Long, bps: Int): Long {
        require(amount >= 0 && bps in 0..100000)
        return BigDecimal.valueOf(amount).multiply(BigDecimal(10000)).divide(BigDecimal(10000 + bps), 0, RoundingMode.HALF_UP).longValueExact()
    }
    fun cashToBank(amount: Long, bps: Int): Long {
        require(amount >= 0 && bps in 0..100000)
        return BigDecimal.valueOf(amount).multiply(BigDecimal(10000 + bps)).divide(BigDecimal(10000), 0, RoundingMode.HALF_UP).longValueExact()
    }
    fun bill(usdCents: Long, rateMinor: Long): Long = BigDecimal.valueOf(usdCents).multiply(BigDecimal.valueOf(rateMinor)).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).longValueExact()
}

data class Clock(val duration: Long, val served: Long, val resumed: Long, val running: Boolean)
object Rules {
    const val MINUTE = 60000L
    const val RECOGNITION_MINUTES = 5
    fun served(c: Clock, now: Long): Long = (c.served + if (c.running) (now - c.resumed).coerceAtLeast(0) else 0).coerceIn(0, c.duration)
    fun remaining(c: Clock, now: Long): Long = c.duration - served(c, now)
    fun qualifies(c: Clock, now: Long, grace: Long, home: Boolean): Boolean = !home && served(c, now) >= grace.coerceIn(0, c.duration)
    fun recognitionAt(c: Clock, grace: Long): Long = c.resumed + (grace.coerceIn(0, c.duration) - c.served).coerceAtLeast(0)
}

data class Expansion(val keyword: String, val client: String, val from: Int, val to: Int)
object TextRules {
    fun householdTemplate(template: String): String {
        if (template == "%client% — الاشتراك %duration% دقيقة، ينتهي %end%، السعر %price% جنيه.") return "✅ أهل البيت"
        return template.replace(Regex("%time(?:[+][0-9.]+h)?%|%end%|%duration%|%price%|%code%"), "").trim().ifBlank { "✅ أهل البيت" }
    }

    fun withReference(text: String, reference: String): String {
        val suffix = "[$reference]"
        return text.trimEnd().let { if (it.endsWith(suffix)) it else it + suffix }
    }

    fun match(text: String, start: Int, end: Int, keywords: Set<String>): Expansion? {
        if (start != end || start !in 1..text.length || !text[start - 1].isWhitespace()) return null
        val wordEnd = start - 1
        var from = wordEnd
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        val token = text.substring(from, wordEnd)
        val keyword = token.substringBefore('/')
        if (keyword !in keywords) return null
        val client = token.substringAfter('/', "").replace('_', ' ').take(80)
        return Expansion(keyword, client, from, wordEnd)
    }
    fun validKeyword(value: String): Boolean = value.isNotBlank() && value.length <= 40 && value.none { it.isWhitespace() || it == '/' }
    fun render(template: String, now: Long, client: String = "", end: Long = now, price: String = "", duration: String = "", code: String = ""): String {
        val locale = Locale.forLanguageTag("ar")
        fun format(pattern: String, at: Long) = SimpleDateFormat(pattern, locale).format(Date(at))
        var text = template.replace("%date%", format("yyyy/MM/dd", now)).replace("%day%", format("EEEE", now))
            .replace("%client%", client).replace("%end%", format("hh:mm a", end)).replace("%price%", price).replace("%duration%", duration).replace("%code%", code)
        text = Regex("%time(?:\\+(\\d+(?:\\.\\d{1,2})?)h)?%").replace(text) { m ->
            val hours = m.groupValues[1].toDoubleOrNull() ?: 0.0
            if (hours > 8760) m.value else format("hh:mm a", now + (hours * 3600000).toLong())
        }
        return text
    }
}
