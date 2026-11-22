package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel

// Phoenix-like update banner (v5.8.7): a slim strip at the top of the
// tunnels list, non-modal — nothing is blocked while it shows. There is
// no dismiss: the banner stays until the update is installed.
@Composable
fun UpdateBanner(sharedViewModel: SharedAppViewModel) {
    val update by sharedViewModel.updateUiState.collectAsStateWithLifecycle()
    if (update.phase == SharedAppViewModel.UpdatePhase.IDLE) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            R.string.update_notification_message,
                            update.version ?: "",
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                when (update.phase) {
                    SharedAppViewModel.UpdatePhase.DOWNLOADING -> {
                        Text(
                            text =
                                if (update.progress == 0) "Downloading… Connecting…"
                                else "Downloading… ${update.progress}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SharedAppViewModel.UpdatePhase.ERROR -> {
                        Text(
                            text = update.message ?: "Download failed",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SharedAppViewModel.UpdatePhase.READY -> {
                        Text(
                            text = stringResource(R.string.update_ready_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        Text(
                            text = "App keeps working — update when ready",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            when (update.phase) {
                SharedAppViewModel.UpdatePhase.AVAILABLE -> {
                    TextButton(onClick = { sharedViewModel.startUpdate() }) {
                        Text(
                            stringResource(R.string.update).uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SharedAppViewModel.UpdatePhase.DOWNLOADING -> {
                    TextButton(onClick = { sharedViewModel.cancelUpdate() }) {
                        Text(
                            stringResource(R.string.cancel).uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SharedAppViewModel.UpdatePhase.READY -> {
                    TextButton(onClick = { sharedViewModel.installUpdate() }) {
                        Text(
                            stringResource(R.string.install).uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SharedAppViewModel.UpdatePhase.ERROR -> {
                    TextButton(onClick = { sharedViewModel.closeUpdate() }) {
                        Text(
                            stringResource(R.string.cancel).uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = { sharedViewModel.startUpdate() }) {
                        Text(
                            stringResource(R.string.update).uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SharedAppViewModel.UpdatePhase.IDLE -> Unit
            }
        }
        if (update.phase == SharedAppViewModel.UpdatePhase.DOWNLOADING) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(update.progress / 100f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
