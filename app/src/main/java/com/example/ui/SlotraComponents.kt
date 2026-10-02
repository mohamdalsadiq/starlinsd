package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SlotraAmber
import com.example.ui.theme.SlotraGreen
import com.example.ui.theme.SlotraMintBright
import com.example.ui.theme.SlotraMuted
import com.example.ui.theme.SlotraRed

/** Visual tone for status chips: green ok, gold pending, mint info, grey muted, red danger. */
internal enum class StatusTone { OK, PENDING, INFO, MUTED, DANGER }

private fun toneColor(tone: StatusTone) = when (tone) {
    StatusTone.OK -> SlotraGreen
    StatusTone.PENDING -> SlotraAmber
    StatusTone.INFO -> SlotraMintBright
    StatusTone.MUTED -> SlotraMuted
    StatusTone.DANGER -> SlotraRed
}

/** Small dot + label pill used for subscription states, device connection, and readiness rows. */
@Composable
internal fun StatusPill(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

internal fun sessionTone(state: String): StatusTone = when (state) {
    "ACTIVE" -> StatusTone.OK
    "SOON" -> StatusTone.PENDING
    "PAUSED" -> StatusTone.INFO
    "ENDED" -> StatusTone.MUTED
    "CANCELLED" -> StatusTone.DANGER
    else -> StatusTone.MUTED
}

internal fun connectionTone(value: String): StatusTone = when (value) {
    "متصل" -> StatusTone.OK
    "غير متصل" -> StatusTone.DANGER
    else -> StatusTone.MUTED
}
