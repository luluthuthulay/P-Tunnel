package com.zaneschepke.wireguardautotunnel.service.autotunnel

import android.content.Intent
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wgtunnel.backend.Backend
import com.wgtunnel.backend.autotunnel.AutoTunnelReconciler
import com.zaneschepke.networkmonitor.ActiveNetwork
import com.zaneschepke.networkmonitor.AndroidNetworkMonitor
import com.zaneschepke.networkmonitor.StableNetworkEngine
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.di.Dispatcher
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.enums.NotificationAction
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.AutoTunnelSettings
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.AutoTunnelSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.domain.state.AutoTunnelState
import com.zaneschepke.wireguardautotunnel.domain.state.toDomain
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelConnect
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelRestoreHandler
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelUnderlyingNetwork
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelWalkGuard
import com.zaneschepke.wireguardautotunnel.service.ServiceManager
import com.zaneschepke.wireguardautotunnel.service.tile.AutoTunnelTileRefresher
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.extensions.debounceFalling
import com.zaneschepke.wireguardautotunnel.util.extensions.to
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import timber.log.Timber

class AutoTunnelService : LifecycleService() {

    private val networkEngine: StableNetworkEngine by inject()

    private val notificationService: NotificationService by inject()

    private val ioDispatcher: CoroutineDispatcher by inject(named(Dispatcher.IO))

    private val stateHolder: AutoTunnelStateHolder by inject()

    private val autoTunnelRepository: AutoTunnelSettingsRepository by inject()
    private val settingsRepository: GeneralSettingRepository by inject()
    private val tunnelsRepository: TunnelRepository by inject()
    private val tunnelCoordinator: TunnelCoordinator by inject()
    private val backend: Backend by inject()

    private val ptunnelRestoreHandler: PTunnelRestoreHandler by inject()
    private val serviceManager: ServiceManager by inject()

    private val reconciler by lazy {
        AutoTunnelReconciler(
            scope = CoroutineScope(lifecycleScope.coroutineContext + ioDispatcher),
            status = backend.status,
            host = AndroidAutoTunnelHost(tunnelCoordinator, tunnelsRepository),
            startupSettle = STARTUP_SETTLE_MS.milliseconds,
        )
    }

    private data class PermissionWarningState(
        val detectionMethod: AndroidNetworkMonitor.WifiDetectionMethod,
        val locationServicesEnabled: Boolean,
        val locationPermissionsEnabled: Boolean,
        val ssidReadRequired: Boolean,
    )

    @OptIn(FlowPreview::class)
    private val autoTunnelStateFlow: Flow<AutoTunnelState> by lazy {
        // Add stabilization to network identity
        val networkFlow =
            networkEngine.stableState
                .mapNotNull { it?.state?.toDomain() }
                .debounce(NETWORK_IDENTITY_SETTLE_MS.milliseconds)

        val settingsFlow = combineSettings()

        // Detected captive portal state is trusted immediately, but cleared state
        // is only trusted once it's held for CAPTIVE_PORTAL_CLEAR_CONFIRM_MS without a change
        // to prevent flapping on flaky networks.
        val confirmedCaptivePortalFlow =
            networkFlow
                .map {
                    (it.activeNetwork as? ActiveNetwork.Wifi)?.requiresCaptivePortalLogin == true
                }
                .distinctUntilChanged()
                .debounceFalling(CAPTIVE_PORTAL_CLEAR_CONFIRM_MS.milliseconds)

        // Having a usable network is believed immediately, but losing it is only believed once
        // it's held false for NO_INTERNET_CONFIRM_MS without recovering as Android's connectivity
        // validation can flap for an extended period during network transitions
        val confirmedHasUsableNetworkFlow =
            networkFlow
                .map { it.hasUsableNetwork }
                .distinctUntilChanged()
                .debounceFalling(NO_INTERNET_CONFIRM_MS.milliseconds)

        combine(
                networkFlow,
                settingsFlow,
                confirmedCaptivePortalFlow,
                confirmedHasUsableNetworkFlow,
            ) { network, settings, confirmedCaptivePortal, confirmedHasUsableNetwork ->
                AutoTunnelState(
                    networkState = network,
                    settings = settings.second,
                    tunnelMode = settings.first,
                    tunnels = settings.third,
                    confirmedCaptivePortal = confirmedCaptivePortal,
                    confirmedHasUsableNetwork = confirmedHasUsableNetwork,
                )
            }
            .distinctUntilChanged()
    }

    override fun onCreate() {
        super.onCreate()
        stateHolder.setActive(true)
        launchWatcherNotification()
        observeActiveState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.d("onStartCommand executed with startId: $startId")
        start()
        return START_STICKY
    }

    fun start() {
        stateHolder.setActive(true)
        AutoTunnelTileRefresher.refresh(this)
        launchWatcherNotification()
    }

    private fun observeActiveState() =
        lifecycleScope.launch(ioDispatcher) {
            stateHolder.active.collectLatest { isActive ->
                if (!isActive) return@collectLatest
                supervisorScope {
                    launch { runReconciler() }
                    launch { runLocationPermissionsNotificationJob() }
                    launch { runPTunnelServiceAuto() }
                    launch { runPTunnelUnderlyingLostWatcher() }
                }
            }
        }

    /**
     * P-Tunnel auto while the app is closed, reusing WG Tunnel's own
     * machinery (user 2026-11-01: "source code ရှိတယ်လေ ယူသုံး").
     *
     * The WG reconciler above drives stored Room tunnels only, so it
     * cannot drive the transient P-Tunnel tunnel (-1). This observer
     * mirrors the ViewModel auto observer with the SAME inputs —
     * StableNetworkEngine.stableState, the same AutoTunnel settings,
     * TunnelCoordinator.backendStatus — but it runs in this
     * foreground service's scope (START_STICKY + ongoing
     * "monitoring" notification), so it survives the app being
     * swiped away, unlike the Activity-scoped ViewModel observer.
     * The connect itself reuses [PTunnelRestoreHandler.tryBootAuto]:
     * the same last-connected-first two-pass walk with handshake
     * waits, guarded by [PTunnelWalkGuard] so it never fights the
     * ViewModel walk. Manual override is shared with the ViewModel
     * through [PTunnelWalkGuard.manualOverride]: a manual OFF in
     * the UI blocks this service observer too, until the network
     * type changes — WG flow.
     *
     * Limit, honest: tryBootAuto only reconnects a stored
     * last-connected endpoint. If P-Tunnel was never connected, the
     * service has nothing to reconnect; the first connect still
     * needs the app opened once.
     */
    /**
     * Phoenix-style underlying-lost watcher (2026-11-02), service
     * side: same reasoning as the ViewModel watcher — the WGAT
     * monitor snapshot can keep reporting cached Wifi after Wi-Fi
     * is off, so the snapshot observer may never fire a stop while
     * the app is closed. This watcher listens to non-VPN networks
     * only and, 1s after a loss, re-reads LIVE caps; if no non-VPN
     * network has INTERNET+VALIDATED and stop-on-no-internet is on,
     * the -1 tunnel stops. Runs in this supervisorScope child, so it
     * is unregistered when the active state flips off.
     */
    private suspend fun runPTunnelUnderlyingLostWatcher() {
        PTunnelUnderlyingNetwork.watchUnderlyingLost(
            this,
            CoroutineScope(currentCoroutineContext()),
        ) {
            if (!PTunnelUnderlyingNetwork.hasUsableUnderlyingNetwork(this)) {
                val stopOnNoInternet =
                    autoTunnelRepository.flow.first().isStopOnNoInternetEnabled
                val hasPTunnel =
                    tunnelCoordinator.backendStatus.value.activeTunnels
                        .containsKey(PTunnelConnect.PTUNNEL_TUNNEL_ID)
                if (stopOnNoInternet && hasPTunnel) {
                    tunnelCoordinator.stopTunnel(
                        PTunnelConnect.PTUNNEL_TUNNEL_ID,
                        TunnelActionSource.AUTO_TUNNEL,
                    )
                }
            }
        }
        awaitCancellation()
    }

    private suspend fun runPTunnelServiceAuto() {
        combine(
                autoTunnelRepository.flow,
                networkEngine.stableState.mapNotNull { it?.state },
                tunnelCoordinator.backendStatus,
            ) { settings, connectivity, backend ->
                val networkType =
                    when (connectivity.activeNetwork) {
                        is ActiveNetwork.Wifi -> "Wifi"
                        is ActiveNetwork.Cellular -> "Cellular"
                        is ActiveNetwork.Ethernet -> "Ethernet"
                        is ActiveNetwork.Disconnected -> "Disconnected"
                    }
                PTunnelServiceAutoKey(
                    networkType = networkType,
                    hasUsableNetwork = connectivity.hasUsableNetwork(),
                    autoEnabled = settings.isAutoTunnelEnabled,
                    wifiEnabled = settings.isTunnelOnWifiEnabled,
                    mobileEnabled = settings.isTunnelOnMobileDataEnabled,
                    ethernetEnabled = settings.isTunnelOnEthernetEnabled,
                    stopOnNoInternet = settings.isStopOnNoInternetEnabled,
                    backendHasPTunnel =
                        backend.activeTunnels.containsKey(
                            PTunnelConnect.PTUNNEL_TUNNEL_ID
                        ),
                )
            }
            .distinctUntilChanged()
            .debounce(PTUNNEL_SERVICE_AUTO_DEBOUNCE_MS.milliseconds)
            .collect { key ->
                if (key.networkType != PTunnelWalkGuard.lastAutoNetworkType) {
                    PTunnelWalkGuard.manualOverride = false
                    PTunnelWalkGuard.lastAutoNetworkType = key.networkType
                }
                if (PTunnelWalkGuard.isBusy || PTunnelWalkGuard.exhausted) return@collect
                if (!serviceManager.hasVpnPermission()) return@collect
                val autoForType =
                    key.autoEnabled &&
                        when (key.networkType) {
                            "Wifi" -> key.wifiEnabled
                            "Cellular" -> key.mobileEnabled
                            "Ethernet" -> key.ethernetEnabled
                            else -> false
                        }
                if (key.backendHasPTunnel) {
                    // Same stop-signal fix as the ViewModel observer
                    // (2026-11-02): stop on NO USABLE INTERNET, not on
                    // the Disconnected type — with VPN up the WG engine
                    // keeps reporting a cached Wifi / present Cellular
                    // type with Wi-Fi and data both off, so the type
                    // never became Disconnected and the tunnel never
                    // stopped. WG's reconciler watches usable, not type.
                    if (!key.hasUsableNetwork && key.stopOnNoInternet) {
                        tunnelCoordinator.stopTunnel(
                            PTunnelConnect.PTUNNEL_TUNNEL_ID,
                            TunnelActionSource.AUTO_TUNNEL,
                        )
                    }
                    return@collect
                }
                if (!autoForType) return@collect
                if (PTunnelWalkGuard.manualOverride) return@collect
                // No-internet pre-check (2026-11-03): same stale-snapshot
                // guard as the ViewModel observer — never start a walk
                // without a LIVE usable underlying network; skip without
                // setting exhausted so network return re-triggers.
                if (!PTunnelUnderlyingNetwork.hasUsableUnderlyingNetwork(this)) return@collect
                // Walk in a child job: tryBootAuto waits for usable
                // network and walks candidates; the guard inside it
                // drops overlapping triggers.
                lifecycleScope.launch(ioDispatcher) {
                    ptunnelRestoreHandler.tryBootAuto()
                }
            }
    }

    private data class PTunnelServiceAutoKey(
        val networkType: String,
        val hasUsableNetwork: Boolean,
        val autoEnabled: Boolean,
        val wifiEnabled: Boolean,
        val mobileEnabled: Boolean,
        val ethernetEnabled: Boolean,
        val stopOnNoInternet: Boolean,
        val backendHasPTunnel: Boolean,
    )

    fun stop() {
        stateHolder.setActive(false)
        stopSelf()
    }

    override fun onDestroy() {
        reconciler.stop()
        tunnelCoordinator.userOverrideListener = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stateHolder.setActive(false)
        AutoTunnelTileRefresher.refresh(this)
        super.onDestroy()
    }

    private suspend fun runReconciler() {
        // The wait is unbounded and the reconciler's actions are not cancellable, so it goes first
        tunnelCoordinator.awaitReady()
        tunnelCoordinator.userOverrideListener = { reconciler.notifyUserOverride() }
        reconciler.start(autoTunnelStateFlow.map { it.toCore() })
        try {
            awaitCancellation()
        } finally {
            reconciler.stop()
            tunnelCoordinator.userOverrideListener = null
        }
    }

    private fun launchWatcherNotification(
        description: String = getString(R.string.monitoring_state_changes)
    ) {
        val notification =
            notificationService.createNotification(
                AndroidNotificationService.NotificationChannels.AutoTunnel,
                title = getString(R.string.auto_tunnel_title),
                description = description,
                actions =
                    listOf(
                        notificationService.createNotificationAction(
                            NotificationAction.AUTO_TUNNEL_OFF
                        )
                    ),
                onGoing = true,
                groupKey = NotificationService.AUTO_TUNNEL_GROUP_KEY,
                isGroupSummary = true,
            )
        ServiceCompat.startForeground(
            this,
            NotificationService.AUTO_TUNNEL_NOTIFICATION_ID,
            notification,
            Constants.SPECIAL_USE_SERVICE_TYPE_ID,
        )
    }

    private fun combineSettings():
        Flow<Triple<TunnelMode, AutoTunnelSettings, List<TunnelConfig>>> {
        return combine(
                settingsRepository.flow.map { it.tunnelMode }.distinctUntilChanged(),
                autoTunnelRepository.flow,
                tunnelsRepository.userTunnelsFlow,
            ) { appMode, autoTunnel, tunnels ->
                Triple(appMode, autoTunnel, tunnels)
            }
            .distinctUntilChanged()
    }

    private suspend fun runLocationPermissionsNotificationJob() {
        autoTunnelStateFlow
            .map { state ->
                PermissionWarningState(
                    detectionMethod = state.settings.wifiDetectionMethod.to(),
                    locationServicesEnabled = state.networkState.locationServicesEnabled,
                    locationPermissionsEnabled = state.networkState.locationPermissionGranted,
                    ssidReadRequired =
                        state.tunnels.any { it.tunnelNetworks.isNotEmpty() } ||
                            state.settings.trustedNetworkSSIDs.isNotEmpty(),
                )
            }
            .distinctUntilChanged()
            .collect { state ->
                val wifiMode = state.detectionMethod

                if (
                    wifiMode == AndroidNetworkMonitor.WifiDetectionMethod.DEFAULT ||
                        wifiMode == AndroidNetworkMonitor.WifiDetectionMethod.LEGACY
                ) {

                    if (!state.ssidReadRequired) {
                        notificationService.remove(
                            NotificationService.AUTO_TUNNEL_LOCATION_SERVICES_ID
                        )
                        notificationService.remove(
                            NotificationService.AUTO_TUNNEL_LOCATION_PERMISSION_ID
                        )
                        return@collect
                    }

                    if (!state.locationPermissionsEnabled) {
                        val notification =
                            notificationService.createNotification(
                                AndroidNotificationService.NotificationChannels.AutoTunnel,
                                title = getString(R.string.warning),
                                description = getString(R.string.location_permissions_missing),
                            )

                        notificationService.show(
                            NotificationService.AUTO_TUNNEL_LOCATION_PERMISSION_ID,
                            notification,
                        )
                    } else {
                        notificationService.remove(
                            NotificationService.AUTO_TUNNEL_LOCATION_PERMISSION_ID
                        )
                    }

                    if (!state.locationServicesEnabled) {
                        val notification =
                            notificationService.createNotification(
                                AndroidNotificationService.NotificationChannels.AutoTunnel,
                                title = getString(R.string.warning),
                                description = getString(R.string.location_services_not_detected),
                            )

                        notificationService.show(
                            NotificationService.AUTO_TUNNEL_LOCATION_SERVICES_ID,
                            notification,
                        )
                    } else {
                        notificationService.remove(
                            NotificationService.AUTO_TUNNEL_LOCATION_SERVICES_ID
                        )
                    }
                }
            }
    }

    companion object {
        private const val PTUNNEL_SERVICE_AUTO_DEBOUNCE_MS = 1_000L
        private const val CAPTIVE_PORTAL_CLEAR_CONFIRM_MS = 8_000L
        private const val NO_INTERNET_CONFIRM_MS = 8_000L
        private const val STARTUP_SETTLE_MS = 2_000L
        private const val NETWORK_IDENTITY_SETTLE_MS = 500L
    }
}
