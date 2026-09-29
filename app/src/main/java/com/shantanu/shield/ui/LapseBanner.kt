package com.shantanu.shield.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Says out loud that a protection has stopped working.
 *
 * Most premium features fail *visibly*: [PremiumGate] replaces the section with a locked card, so the
 * parent can see the feature is gone. The enforcement-only features — app lock, the daily budget, the
 * night lock, tamper protection — have no such surface. They are enforced by flags inside the
 * foreground service, so when they lock, the service quietly stops acting while every switch in
 * Settings still reads "on".
 *
 * That failure mode is unacceptable in a child-safety app: the parent believes their child is
 * protected, the child is not, and nothing anywhere says otherwise. This banner is the missing signal.
 *
 * Deliberately *not* styled as an error. An error implies something broke and might be retried; this
 * is a capability that is switched off and can be switched back on. It reads as a paused state with a
 * way forward — a status stripe, a PAUSED pill, the consequence in plain words, and one action.
 *
 * Renders nothing when [locked] is false, so it costs nothing on the normal path.
 */
@Composable
fun LapseBanner(
    locked: Boolean,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val openPaywall = LocalRequestPaywall.current
    val cs = MaterialTheme.colorScheme

    // Animate in: appearing silently is how the old banner read as chrome rather than news.
    AnimatedVisibility(
        visible = locked,
        enter = fadeIn() + expandVertically(),
    ) {
        Card(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
        ) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
                // Status stripe — carries the severity so the body can stay calm.
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(cs.error)
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = cs.error,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = cs.onError,
                                modifier = Modifier.padding(5.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            color = cs.error.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                "PAUSED",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.9.sp,
                                color = cs.onErrorContainer,
                            )
                        }
                    }

                    Spacer(Modifier.height(11.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = cs.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onErrorContainer.copy(alpha = 0.88f),
                        lineHeight = 19.sp,
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = openPaywall,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.error,
                                contentColor = cs.onError,
                            ),
                        ) {
                            Text(
                                "See what's included",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
