package com.zaneschepke.wireguardautotunnel.ui.state

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.state.ActiveTunnel
import com.wgtunnel.backend.state.BootstrapState
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.CoolGray
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw

sealed class DisplayTunnelState {
    data object Disconnected : DisplayTunnelState()

    data object ResolvingDns : DisplayTunnelState()

    data object EstablishingConnection : DisplayTunnelState()

    data object Connected : DisplayTunnelState()

    data object HandshakeFailure : DisplayTunnelState()

    @StringRes
    fun labelRes(): Int =
        when (this) {
            Disconnected -> R.string.tunnel_state_disconnected
            EstablishingConnection -> R.string.tunnel_state_establishing_connection
            ResolvingDns -> R.string.tunnel_state_resolving_dns
            Connected -> R.string.tunnel_state_connected
            HandshakeFailure -> R.string.tunnel_state_handshake_failure
        }

    fun asColor(): Color =
        when (this) {
            Disconnected -> CoolGray
            ResolvingDns,
            EstablishingConnection -> Straw
            Connected -> SilverTree
            HandshakeFailure -> AlertRed
        }

    fun asLocalizedString(context: Context): String {
        return context.getString(labelRes())
    }

    companion object {
        fun from(activeTunnel: ActiveTunnel): DisplayTunnelState {
            val transport = activeTunnel.transportState
            val bootstrap = activeTunnel.bootstrapState

            return when {
                transport is Tunnel.State.Down -> Disconnected
                bootstrap is BootstrapState.Failed -> HandshakeFailure

                bootstrap is BootstrapState.ResolvingDns ||
                    bootstrap is BootstrapState.UpdatingPeers -> ResolvingDns

                transport is Tunnel.State.Up.Healthy -> Connected

                transport is Tunnel.State.Up.HandshakeFailure -> HandshakeFailure

                else -> EstablishingConnection
            }
        }
    }
}
