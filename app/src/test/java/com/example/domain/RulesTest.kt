package com.example.domain

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun fullPriceQualifiesOnlyAfterThirtyActiveMinutes() {
        val start = 1700000000000L
        for (minutes in listOf(60, 180)) {
            val c = Clock(minutes * Rules.MINUTE, 0, start, true)
            assertFalse(Rules.qualifies(c, start + 30 * Rules.MINUTE - 1, 30 * Rules.MINUTE, false))
            assertTrue(Rules.qualifies(c, start + 30 * Rules.MINUTE, 30 * Rules.MINUTE, false))
            assertFalse(Rules.qualifies(c, start + minutes * Rules.MINUTE, 30 * Rules.MINUTE, true))
        }
    }
    @Test fun pauseAndClockRollbackNeverAddTimeUsed() {
        val paused = Clock(180 * Rules.MINUTE, 20 * Rules.MINUTE, 1000000, false)
        assertEquals(20 * Rules.MINUTE, Rules.served(paused, 9999999999))
        val resumed = paused.copy(running = true, resumed = 10000000)
        assertFalse(Rules.qualifies(resumed, 10000000 + 9 * Rules.MINUTE, 30 * Rules.MINUTE, false))
        assertTrue(Rules.qualifies(resumed, 10000000 + 10 * Rules.MINUTE, 30 * Rules.MINUTE, false))
        assertEquals(20 * Rules.MINUTE, Rules.served(resumed, 1))
    }
    @Test fun shortPlanRecognizesAtEndAndCannotRunBelowZero() {
        val c = Clock(10 * Rules.MINUTE, 0, 100, true)
        assertTrue(Rules.qualifies(c, 100 + c.duration, 30 * Rules.MINUTE, false))
        assertEquals(0L, Rules.remaining(c, Long.MAX_VALUE / 2))
    }
    @Test fun bankPremiumIsValueConversionNotProfit() {
        assertEquals(12500000L, Money.cashToBank(10000000, 2500))
        assertEquals(10000000L, Money.bankToCash(12500000, 2500))
        assertEquals(100000L, Money.bankToCash(125000, 2500))
        assertEquals(62500L, Money.cashToBank(50000, 2500))
        assertEquals(25000000L, Money.bill(10000, 250000)) // $100 x 2500 SDG
    }
    @Test fun moneyAcceptsArabicAndPersianDecimalsWithoutFloatingPoint() {
        assertEquals(125025L, Money.parse("١٢٥٠٫٢٥"))
        assertEquals(50000L, Money.parse("۵۰۰"))
        assertEquals("0.01", Money.show(1))
        listOf("-1", "1.234", "1e3", "NaN", "", "1000000000").forEach { assertNull(Money.parse(it)) }
    }
    @Test fun expansionNeedsDelimiterAndCollapsedCursor() {
        val keys = setOf("س3", "mn")
        assertNull(TextRules.match("mn", 2, 2, keys))
        assertNull(TextRules.match("mn ", 0, 3, keys))
        assertNull(TextRules.match("xmn ", 4, 4, keys))
        val text = "قبل س3/محمد_أحمد بعد"
        val cursor = text.indexOf(" بعد") + 1
        val hit = TextRules.match(text, cursor, cursor, keys)!!
        assertEquals("محمد أحمد", hit.client)
        assertEquals("قبل مرحبًا بعد", text.replaceRange(hit.from, hit.to, "مرحبًا"))
    }
    @Test fun fractionalTimeAndVariablesUseSameInstant() {
        val now = 1700000000000L
        assertEquals(TextRules.render("%time%", now + 5400000), TextRules.render("%time+1.5h%", now))
        assertEquals("محمد 1000 180", TextRules.render("%client% %price% %duration%", now, "محمد", price = "1000", duration = "180"))
        assertFalse(TextRules.validKeyword("a b")); assertFalse(TextRules.validKeyword("a/b"))
    }
}
