package com.example.myna_mimicyourinteractionsautomate.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Bad
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Beak
import com.example.myna_mimicyourinteractionsautomate.ui.theme.BeakSoft
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Card
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Ink
import com.example.myna_mimicyourinteractionsautomate.ui.theme.InkSoft
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Line
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Ok
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Warn

/** The big mic from the sketch. Pulses while MYNA is listening or thinking. */
@Composable
fun MicHero(busy: Boolean, caption: String, onTap: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f, targetValue = if (busy) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.size(248.dp).scale(pulse).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Card, Color.White)))
            .border(2.dp, Ink, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.example.myna_mimicyourinteractionsautomate.R.drawable.mic),
                    "Speak", Modifier.size(40.dp), tint = Color.White)
            }
            Spacer(Modifier.height(14.dp))
            Text(caption, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp,
                color = Ink, modifier = Modifier.padding(horizontal = 36.dp))
        }
    }
}

/** A chat line: MYNA on the left in pale yellow, the user on the right in grey. */
@Composable
fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
            .background(if (mine) Card else BeakSoft).padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

/** App name → a small coloured badge, so Zomato and Amazon cards read at a glance. */
@Composable
fun AppBadge(pkg: String) {
    val name = when {
        "zomato" in pkg -> "Zomato"; "amazon" in pkg -> "Amazon"; "myntra" in pkg -> "Myntra"; "swiggy" in pkg -> "Swiggy"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
    // Monochrome on purpose: the design is black and white.
    Text(name, Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

/** One pinned automation in the Home grid. */
@Composable
fun AutomationCard(title: String, pkg: String, enabled: Boolean, onRun: () -> Unit, onSteps: () -> Unit, onChange: (() -> Unit)?,
                   modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppBadge(pkg)
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(62.dp))
        // Half-screen cards: compact buttons that share the row (full-size ones squash "Steps" into a column).
        val compact = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp)
        // Black text and outline on the secondary buttons (the default tint read as grey).
        val outlined = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
        val edge = androidx.compose.foundation.BorderStroke(1.dp, Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onRun, Modifier.weight(1f), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = compact) { Text("Run", maxLines = 1) }
            OutlinedButton(onSteps, Modifier.weight(1f), shape = RoundedCornerShape(12.dp), contentPadding = compact, colors = outlined, border = edge) {
                Text("Steps", maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
        }
        // Same width as Run + Steps together: change the blanks (item, restaurant…) before running.
        if (onChange != null) OutlinedButton(onChange, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = compact,
            colors = outlined, border = edge) {
            Text("Make slight changes", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Outcome of a run as a small coloured pill. */
@Composable
fun StatusPill(outcome: Outcome) {
    val (label, color) = when (outcome) {
        Outcome.HANDED_OFF -> "✓ Handed to you" to Ok
        Outcome.DONE -> "✓ Done" to Ok
        Outcome.STUCK -> "⚠ Stuck" to Warn
        Outcome.FAILED -> "✕ Failed" to Bad
        Outcome.STOPPED -> "■ Stopped" to InkSoft
        Outcome.RUNNING -> "… Running" to InkSoft
    }
    Text(label, Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

/** A step line in a run or recipe: status dot, what, and a grey note under it. */
@Composable
fun StepRow(index: Int, what: String, status: String? = null, note: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val dot = when (status) { "ok" -> Ok; "stuck", "blocked" -> Warn; "failed" -> Bad; "skipped" -> InkSoft; else -> Line }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(22.dp).clip(CircleShape).background(dot.copy(alpha = if (status == null) 1f else 0.18f)),
            contentAlignment = Alignment.Center) { Text("$index", fontSize = 11.sp, color = if (status == null) InkSoft else dot, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(what, style = MaterialTheme.typography.bodyMedium)
            note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = InkSoft) }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        action?.invoke()
    }
}
