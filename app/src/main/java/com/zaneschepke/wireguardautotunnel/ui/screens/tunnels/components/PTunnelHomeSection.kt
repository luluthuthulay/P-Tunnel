package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wgtunnel.backend.state.ActiveTunnel
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.SwitchWithDivider
import com.zaneschepke.wireguardautotunnel.ui.state.DisplayTunnelState
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel

/**
 * P-Tunnel home card. This replaces the WG Tunnel empty state on home: no
 * Lottie animation, no "Nothing here... yet." text, no getting-started
 * guidance text. In their place is a single endpoint row:
 *
 * - status dot on the left (colour from [DisplayTunnelState]),
 * - endpoint IP:port in the middle (JSON #1 before connecting, the
 *   actually connected endpoint while connected),
 * - a switch on the right as the connect/disconnect control.
 *
 * Disconnected: gray dot, switch OFF, endpoint still shown.
 * Connecting: yellow dot, switch ON, "Establishing connection".
 * Connected: green dot, switch ON, and WG Tunnel's existing tunnel
 * statistics below (status, uptime, download/upload, last handshake,
 * endpoint, plus proxy status texts) via [TunnelStatisticsRow] — all
 * existing connected-state items are kept, none removed.
 * Handshake failure: red dot, switch OFF, error text shown.
 *
 * The credit line below the endpoint/stats area is PROVISIONAL placement
 * (not yet confirmed by the user) and can be moved later.
 */
@Composable
fun PTunnelHomeSection(
    viewModel: SharedAppViewModel,
    activeTunnel: ActiveTunnel?,
    modifier: Modifier = Modifier,
) {
    val ptunnelState by viewModel.ptunnelUiState.collectAsStateWithLifecycle()
    val isRunning = activeTunnel != null
    val displayState =
        remember(activeTunnel, ptunnelState.isConnecting, ptunnelState.lastError) {
            when {
                activeTunnel != null -> DisplayTunnelState.from(activeTunnel)
                ptunnelState.isConnecting -> DisplayTunnelState.EstablishingConnection
                ptunnelState.lastError != null -> DisplayTunnelState.HandshakeFailure
                else -> DisplayTunnelState.Disconnected
            }
        }
    val shownEndpoint =
        ptunnelState.displayedEndpoint ?: ptunnelState.connectingEndpoint

    Column(modifier = modifier.fillMaxWidth()) {
        SurfaceRow(
            // v2 #3: tapping the card (not the switch) cycles the displayed
            // candidate when disconnected. The switch's own Box consumes
            // switch taps, and cyclePTunnelEndpoint() itself is a no-op
            // while connected, connecting, or mid-failover, so a tap can
            // never disconnect a live tunnel.
            onClick = { viewModel.cyclePTunnelEndpoint() },
            leading = {
                Icon(
                    Icons.Rounded.Circle,
                    contentDescription = stringResource(R.string.tunnel_monitoring),
                    tint = remember(displayState) { displayState.asColor() },
                    modifier = Modifier.size(14.dp),
                )
            },
            title = shownEndpoint?.id ?: stringResource(R.string.ptunnel_no_endpoint),
            description = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(displayState.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ptunnelState.lastError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed,
                        )
                    }
                }
            },
            expandedContent =
                if (isRunning) {
                    { TunnelStatisticsRow(activeTunnel) }
                } else {
                    null
                },
            trailing = { rowModifier ->
                SwitchWithDivider(
                    checked = isRunning || ptunnelState.isConnecting,
                    onClick = { checked -> viewModel.togglePTunnel(checked) },
                    modifier = rowModifier,
                )
            },
        )

        // v2 #3a: manual Refresh. Re-fetches the feed immediately; when
        // disconnected it resets a manual cycle to the new JSON #1, when
        // connected it updates the cache silently only (the live tunnel
        // and the displayed connected endpoint do not change). No
        // full-screen spinner: the button is simply disabled while
        // refreshing, and a failed refresh keeps the old cache silently.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ptunnel_refresh_feed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { viewModel.refreshPTunnelFeed() },
                enabled = !ptunnelState.isFeedRefreshing,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.ptunnel_refresh_feed),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // PROVISIONAL placement (see file doc): credit line under the
        // endpoint/stats area on home.
        Text(
            text = stringResource(R.string.ptunnel_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        )
    }
}
