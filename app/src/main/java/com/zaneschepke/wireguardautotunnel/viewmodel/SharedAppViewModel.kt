package com.zaneschepke.wireguardautotunnel.viewmodel

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wgtunnel.backend.Tunnel
import java.util.concurrent.TimeoutException
import com.dokar.sonner.ToastType
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.ConfigParseException
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.AutoTunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelBackendCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelGroup
import com.zaneschepke.wireguardautotunnel.ptunnel.EndpointInfo
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelConnect
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelConfService
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelDiagnostics
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelFeedService
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelStore
import com.zaneschepke.wireguardautotunnel.ptunnel.PTunnelUiState
import com.zaneschepke.wireguardautotunnel.domain.repository.AppStateRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.SelectedTunnelsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelGroupRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelsSelection
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.NotificationPendingAction
import com.zaneschepke.wireguardautotunnel.service.ServiceManager
import com.zaneschepke.wireguardautotunnel.service.autotunnel.AutoTunnelStateHolder
import com.zaneschepke.wireguardautotunnel.ui.sideeffect.LocalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.state.DisplayTunnelState
import com.zaneschepke.wireguardautotunnel.ui.state.GlobalAppUiState
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelsUiState
import com.zaneschepke.wireguardautotunnel.ui.state.nextChildPosition
import com.zaneschepke.wireguardautotunnel.ui.state.nextRootPosition
import com.zaneschepke.wireguardautotunnel.ui.state.shiftForInsertAfter
import com.zaneschepke.wireguardautotunnel.ui.state.shiftRootPositionsBy
import com.zaneschepke.wireguardautotunnel.ui.state.sortGroupChildrenByName
import com.zaneschepke.wireguardautotunnel.ui.state.sortRootByName
import com.zaneschepke.wireguardautotunnel.ui.state.ungroupKeepingOrder
import com.zaneschepke.wireguardautotunnel.ui.state.uniqueDisplayName
import com.zaneschepke.wireguardautotunnel.ui.theme.Theme
import com.zaneschepke.wireguardautotunnel.util.FileUtils
import com.zaneschepke.wireguardautotunnel.util.LocaleUtil
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.QuickConfig
import com.zaneschepke.wireguardautotunnel.util.extensions.TunnelName
import com.zaneschepke.wireguardautotunnel.util.extensions.asExportFileName
import com.zaneschepke.wireguardautotunnel.util.extensions.asFileExportName
import com.zaneschepke.wireguardautotunnel.util.extensions.asStringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.isFileAccessDenied
import com.zaneschepke.wireguardautotunnel.util.extensions.saveTunnelsUniquely
import com.zaneschepke.wireguardautotunnel.util.network.NetworkUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.viewmodel.orbitContainer
import timber.log.Timber
import xyz.teamgravity.pin_lock_compose.PinManager

class SharedAppViewModel(
    private val appStateRepository: AppStateRepository,
    private val serviceManager: ServiceManager,
    private val tunnelCoordinator: TunnelCoordinator,
    private val autoTunnelCoordinator: AutoTunnelCoordinator,
    private val globalEffectRepository: GlobalEffectRepository,
    private val tunnelRepository: TunnelRepository,
    private val tunnelGroupRepository: TunnelGroupRepository,
    private val settingsRepository: GeneralSettingRepository,
    private val autoTunnelStateHolder: AutoTunnelStateHolder,
    private val selectedTunnelsRepository: SelectedTunnelsRepository,
    private val tunnelBackendCoordinator: TunnelBackendCoordinator,
    private val httpClient: HttpClient,
    private val fileUtils: FileUtils,
    private val networkUtils: NetworkUtils,
    private val ptunnelStore: PTunnelStore,
) : OrbitContainerHost<GlobalAppUiState, GlobalAppUiState, LocalSideEffect>, ViewModel() {

    val globalSideEffect = globalEffectRepository.flow

    private val _supportAutoUpdateRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val supportAutoUpdateRequests = _supportAutoUpdateRequests.asSharedFlow()

    fun requestSupportAutoUpdate(startDownload: Boolean) {
        _supportAutoUpdateRequests.tryEmit(startDownload)
    }

    // Sticky, the Support screen may not be composed yet when this is requested
    private val _showUpdateStatusRequested = MutableStateFlow(false)
    val showUpdateStatusRequested = _showUpdateStatusRequested.asStateFlow()

    fun requestShowUpdateStatus() {
        _showUpdateStatusRequested.value = true
    }

    fun consumeShowUpdateStatus() {
        _showUpdateStatusRequested.value = false
    }

    private val _showSplitSystemApps = MutableStateFlow(false)
    val showSplitSystemApps = _showSplitSystemApps.asStateFlow()
    private var splitSystemAppsTunnelId: Int? = null

    fun prepareSplitSystemApps(tunnelId: Int) {
        if (splitSystemAppsTunnelId != tunnelId) {
            splitSystemAppsTunnelId = tunnelId
            _showSplitSystemApps.value = false
        }
    }

    fun setShowSplitSystemApps(show: Boolean) {
        _showSplitSystemApps.value = show
    }

    fun clearSplitSystemApps(tunnelId: Int) {
        if (splitSystemAppsTunnelId == tunnelId) {
            splitSystemAppsTunnelId = null
            _showSplitSystemApps.value = false
        }
    }

    private val reorderDraft = MutableStateFlow<ReorderDraft?>(null)
    private val pingLatencies = MutableStateFlow<Map<Int, Double>>(emptyMap())
    private val pingInFlight = MutableStateFlow(false)

    val tunnelsUiState =
        combine(
                combine(
                    tunnelRepository.userTunnelsFlow,
                    tunnelGroupRepository.flow,
                    tunnelCoordinator.backendStatus,
                    selectedTunnelsRepository.flow,
                    reorderDraft,
                ) { tunnels, groups, backendStatus, selection, draft ->
                    val displayStates =
                        backendStatus.activeTunnels.mapValues { (_, activeTunnel) ->
                            DisplayTunnelState.from(activeTunnel)
                        }

                    TunnelsUiState(
                        tunnels = draft?.tunnels ?: tunnels,
                        groups = draft?.groups ?: groups,
                        backendStatus = backendStatus,
                        displayStates = displayStates,
                        selectedTunnels = selection.tunnels,
                        selectedGroups = selection.groups,
                        isLoading = false,
                        isReorderMode = draft != null,
                        reorderScopeGroupId = draft?.scopeGroupId,
                        reorderLatencies = draft?.latencies.orEmpty(),
                    )
                },
                pingLatencies,
            ) { ui, latencies ->
                ui.copy(
                    pingLatencies =
                        if (ui.backendStatus.activeTunnels.isEmpty()) latencies else emptyMap()
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TunnelsUiState())

    override val container =
        orbitContainer<GlobalAppUiState, LocalSideEffect>(
            GlobalAppUiState(),
            buildSettings = { repeatOnSubscribedStopTimeout = 5_000L },
        ) {
            intent {
                combine(
                        tunnelRepository.userTunnelsFlow
                            .map { tuns -> tuns.associate { it.id to it.name } }
                            .distinctUntilChanged(),
                        settingsRepository.flow,
                        autoTunnelStateHolder.active,
                        tunnelsUiState
                            .map {
                                TunnelsChrome(
                                    loading = it.isLoading,
                                    selectedTunCount = it.selectedCount,
                                    canMoveToGroup = it.canMoveToGroup,
                                    isReorderMode = it.isReorderMode,
                                    reorderScopeTitle = it.reorderScopeGroup?.name,
                                    hasGroups = it.groups.isNotEmpty(),
                                    canReorder = it.tunnels.size + it.groups.size > 1,
                                    hasActiveTunnel = it.hasActiveTunnel,
                                )
                            }
                            .distinctUntilChanged(),
                        appStateRepository.flow,
                    ) { tunNames, settings, autoTunnelActive, chrome, appState ->
                        state.copy(
                            theme = settings.theme,
                            tunnelMode = settings.tunnelMode,
                            tunnelNames = tunNames,
                            alreadyDonated = settings.alreadyDonated,
                            isAutoTunnelActive = autoTunnelActive,
                            isLocationDisclosureShown = appState.isLocationDisclosureShown,
                            isBatteryOptimizationShown = appState.isBatteryOptimizationDisableShown,
                            shouldShowDonationSnackbar = appState.shouldShowDonationSnackbar,
                            isDesktopPromptShown = appState.isDesktopPromptShown,
                            selectedTunnelCount = chrome.selectedTunCount,
                            canMoveToGroup = chrome.canMoveToGroup,
                            isReorderMode = chrome.isReorderMode,
                            reorderScopeTitle = chrome.reorderScopeTitle,
                            hasGroups = chrome.hasGroups,
                            canReorder = chrome.canReorder,
                            hasActiveTunnel = chrome.hasActiveTunnel,
                            pinLockEnabled = settings.isPinLockEnabled,
                            isScreenRecordingProtectionEnabled =
                                settings.screenRecordingSecurityEnabled,
                            isAppLoaded = !chrome.loading,
                        )
                    }
                    .collect { newState -> reduce { newState } }
            }
            intent {
                // Clear ping results when tunnel is active
                tunnelCoordinator.backendStatus
                    .map { it.activeTunnels.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { hasActiveTunnel ->
                        if (hasActiveTunnel) pingLatencies.value = emptyMap()
                    }
            }
        }

    fun startTunnel(tunnelConfig: TunnelConfig) = intent {
        // P-Tunnel resume path: MainActivity calls startTunnel() again after
        // a VPN/notification permission grant, passing the transient
        // P-Tunnel config. Route it into the failover loop instead of a
        // direct tunnel start, so failover (and not just candidate #1) runs.
        if (tunnelConfig.id == PTunnelConnect.PTUNNEL_TUNNEL_ID) {
            resumePTunnelAfterPermission()
            return@intent
        }
        if (state.tunnelMode == TunnelMode.VPN) {
            if (!serviceManager.hasVpnPermission())
                return@intent postSideEffect(
                    GlobalSideEffect.RequestVpnPermission(TunnelMode.VPN, tunnelConfig)
                )
        }
        if (
            !serviceManager.hasNotificationPermission() &&
                !appStateRepository.isNotificationPermissionRequested()
        ) {
            appStateRepository.setNotificationPermissionRequested(true)
            return@intent postSideEffect(
                GlobalSideEffect.RequestNotificationPermission(
                    NotificationPendingAction.StartTunnel(tunnelConfig)
                )
            )
        }
        tunnelCoordinator.startTunnel(tunnelConfig)
    }

    fun toggleAutoTunnel() = intent { autoTunnelCoordinator.toggle() }

    fun postSideEffect(localSideEffect: LocalSideEffect) = intent {
        postSideEffect(localSideEffect)
    }

    fun setLocationDisclosureShown() = intent {
        appStateRepository.setLocationDisclosureShown(true)
    }

    fun setTheme(theme: Theme) = intent { settingsRepository.updateTheme(theme) }

    fun setLocale(locale: String) = intent {
        // pre-T the stored value is reapplied on startup, on T+ the system persists it
        if (!LocaleUtil.isSystemManaged) settingsRepository.updateLocale(locale)
        withContext(Dispatchers.Main) { LocaleUtil.changeLocale(locale) }
    }

    fun syncLocale() = intent {
        val stored = settingsRepository.flow.firstOrNull()?.locale ?: return@intent
        if (stored == LocaleUtil.OPTION_PHONE_LANGUAGE) return@intent
        if (LocaleUtil.isSystemManaged) {
            // one-time handoff to the system, without overriding a locale set in system settings
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                withContext(Dispatchers.Main) { LocaleUtil.changeLocale(stored) }
            }
            settingsRepository.updateLocale(LocaleUtil.OPTION_PHONE_LANGUAGE)
        } else {
            withContext(Dispatchers.Main) { LocaleUtil.changeLocale(stored) }
        }
    }

    fun setPinLockEnabled(enabled: Boolean) = intent {
        if (!enabled) PinManager.clearPin()
        settingsRepository.updatePinLockEnabled(enabled)
    }

    fun stopTunnel(tunnelConfig: TunnelConfig) = intent {
        if (tunnelConfig.id == PTunnelConnect.PTUNNEL_TUNNEL_ID) {
            stopPTunnel()
            return@intent
        }
        tunnelCoordinator.stopTunnel(tunnelConfig.id)
    }

    // ------------------------------------------------------------------
    // P-Tunnel (single endpoint card, transient tunnel, feed + conf first)
    // ------------------------------------------------------------------

    private val _ptunnelUiState = MutableStateFlow(PTunnelUiState())
    val ptunnelUiState = _ptunnelUiState.asStateFlow()

    private var ptunnelJob: Job? = null
    private var ptunnelReadyJob: Job? = null
    private var ptunnelPollJob: Job? = null

    /** Candidate the permission flow was asked for, or null for "from #1". */
    private var pendingPTunnelCandidate: EndpointInfo? = null

    /**
     * Candidate list snapshot for the connect currently in flight (v2).
     * Polling updates the StateFlow list, but must never change the order
     * of an in-flight failover: the run (including a permission-resume)
     * keeps using this snapshot until it succeeds, fails, or is stopped.
     */
    private var pendingPTunnelCandidates: List<EndpointInfo>? = null

    private fun ptunnelContext() = ptunnelStore.appContext

    private fun updatePTunnel(transform: (PTunnelUiState) -> PTunnelUiState) {
        _ptunnelUiState.update(transform)
    }

    /**
     * Called on app open / returning to foreground (from TunnelsScreen).
     * No VPN permission is requested here: feed and conf fetches run over
     * the physical network.
     *
     * - Conf: fetched ONLY if none is stored (fresh install, first time only).
     *   Later opens reuse the stored conf and never re-fetch it.
     * - Feed: fetched on every open/foreground return. A new feed only
     *   changes the stored list; a live tunnel is never switched
     *   mid-session (see [startPTunnel]: candidates are fixed at connect).
     */
    fun ensurePTunnelReady() {
        if (ptunnelReadyJob?.isActive == true) return
        ptunnelReadyJob =
            viewModelScope.launch {
                val context = ptunnelContext()
                if (ptunnelStore.getBaseConf() == null) {
                    PTunnelConfService.fetch(context)
                        .onSuccess { conf -> ptunnelStore.saveBaseConf(conf) }
                        .onFailure { error ->
                            PTunnelDiagnostics.report(context, "conf", null, error)
                        }
                }
                PTunnelFeedService.fetch(context)
                    .onSuccess { fetched ->
                        ptunnelStore.saveEndpointsJson(
                            PTunnelFeedService.serialize(fetched.endpoints)
                        )
                        applyPTunnelEndpoints(fetched.endpoints)
                    }
                    .onFailure { error ->
                        PTunnelDiagnostics.report(context, "feed", null, error)
                        // Keep the cached list: a failed refresh must not
                        // blank the card.
                        val cached =
                            ptunnelStore.getEndpointsJson()?.let { raw ->
                                runCatching { PTunnelFeedService.parse(raw).endpoints }
                                    .getOrNull()
                            }.orEmpty()
                        if (cached.isNotEmpty()) applyPTunnelEndpoints(cached)
                    }
            }
    }

    /**
     * Applies a new endpoint list to the card, with the v2 guards:
     * - Connected or connecting: the list is updated, but the displayed
     *   endpoint stays the actually connected / connecting endpoint. No
     *   UI flicker, no stats reset, no live-tunnel change.
     * - Disconnected and [resetManual] (manual Refresh only): the manual
     *   choice is cleared and the displayed candidate becomes JSON #1.
     * - Disconnected otherwise (open, foreground return, automatic poll):
     *   a manual choice that is still in the new list is kept until the
     *   next connect; otherwise the displayed candidate is JSON #1.
     * A manual choice is in-memory only, so app reopen (new ViewModel)
     * naturally resets it to JSON #1.
     */
    private suspend fun applyPTunnelEndpoints(
        endpoints: List<EndpointInfo>,
        resetManual: Boolean = false,
    ) {
        val storedConnectedId = ptunnelStore.getConnectedEndpoint()
        val current = _ptunnelUiState.value
        val connected =
            current.connectedEndpoint
                ?: endpoints.firstOrNull { it.id == storedConnectedId }
        val displayed =
            when {
                connected != null -> connected
                current.isConnecting -> current.connectingEndpoint ?: endpoints.firstOrNull()
                resetManual -> endpoints.firstOrNull()
                current.manualEndpoint != null &&
                    endpoints.any { it.id == current.manualEndpoint.id } ->
                    current.manualEndpoint
                else -> endpoints.firstOrNull()
            }
        updatePTunnel { state ->
            state.copy(
                endpoints = endpoints,
                connectedEndpoint = connected,
                displayedEndpoint = displayed,
                manualEndpoint = if (resetManual) null else state.manualEndpoint,
            )
        }
    }

    // ------------------------------------------------------------------
    // P-Tunnel v2: manual control (#3) + 1-minute feed poll
    // ------------------------------------------------------------------

    /**
     * Manual Refresh (v2 #3a): re-fetch the feed immediately. When
     * disconnected this resets any manual cycle and shows the new JSON #1;
     * when connected it updates the cache silently only — the live tunnel
     * and the displayed actually-connected endpoint do not change.
     * Failures are silent apart from re-enabling the button: the old cache
     * stays, no error toast is shown.
     */
    fun refreshPTunnelFeed() {
        if (_ptunnelUiState.value.isFeedRefreshing) return
        viewModelScope.launch {
            updatePTunnel { it.copy(isFeedRefreshing = true) }
            try {
                val context = ptunnelContext()
                PTunnelFeedService.fetch(context)
                    .onSuccess { fetched ->
                        ptunnelStore.saveEndpointsJson(
                            PTunnelFeedService.serialize(fetched.endpoints)
                        )
                        applyPTunnelEndpoints(fetched.endpoints, resetManual = true)
                    }
                    .onFailure { error ->
                        // User-initiated, so one non-fatal diagnostic report
                        // (no UI toast) is acceptable; the cache is kept.
                        PTunnelDiagnostics.report(context, "feed-refresh", null, error)
                    }
            } finally {
                updatePTunnel { it.copy(isFeedRefreshing = false) }
            }
        }
    }

    /**
     * Tap the endpoint card (v2 #3b): cycle the displayed candidate
     * #1 -> #2 -> #3 -> #1, ONLY when disconnected and not connecting.
     * When connected or connecting this is a no-op, so a tap can never
     * disconnect a live tunnel or interrupt a failover. The choice is
     * one-time: it is consumed when the next connect starts (see
     * [startPTunnel]) and cleared by a manual Refresh.
     */
    fun cyclePTunnelEndpoint() {
        val current = _ptunnelUiState.value
        if (current.connectedEndpoint != null || current.isConnecting) return
        if (ptunnelJob?.isActive == true) return
        val candidates = current.endpoints.take(3)
        if (candidates.size < 2) return
        val currentIndex =
            current.displayedEndpoint?.let { shown ->
                candidates.indexOfFirst { it.id == shown.id }
            }?.takeIf { it >= 0 } ?: 0
        val next = candidates[(currentIndex + 1) % candidates.size]
        updatePTunnel { it.copy(manualEndpoint = next, displayedEndpoint = next) }
    }

    /**
     * Starts the 1-minute feed poll (v2). The first poll runs 60 s after
     * starting, because the caller (foreground return) already fetched
     * immediately; polling only runs while the home screen is in the
     * foreground — [stopPTunnelPolling] cancels it on pause/dispose.
     */
    fun startPTunnelPolling() {
        if (ptunnelPollJob?.isActive == true) return
        ptunnelPollJob =
            viewModelScope.launch {
                while (true) {
                    delay(PTUNNEL_POLL_INTERVAL_MS)
                    pollPTunnelFeed()
                }
            }
    }

    fun stopPTunnelPolling() {
        ptunnelPollJob?.cancel()
        ptunnelPollJob = null
    }

    /**
     * One automatic poll tick. Deliberately silent: on success the cache
     * is updated via [applyPTunnelEndpoints] (which keeps a manual choice
     * and never touches a live tunnel); on failure nothing changes at all
     * — no toast, no card error, and no Crashlytics report, so an offline
     * hour does not spam diagnostics with one report per minute.
     */
    private suspend fun pollPTunnelFeed() {
        val context = ptunnelContext()
        PTunnelFeedService.fetch(context)
            .onSuccess { fetched ->
                ptunnelStore.saveEndpointsJson(
                    PTunnelFeedService.serialize(fetched.endpoints)
                )
                applyPTunnelEndpoints(fetched.endpoints)
            }
            .onFailure {
                // Silent by design (see above); the old cache stays.
            }
    }

    fun togglePTunnel(checked: Boolean) {
        if (checked) startPTunnel() else stopPTunnel()
    }

    /**
     * Switch-ON: failover over a SNAPSHOT of the top 3 candidates, one at
     * a time (v2). A manual cycle, if present, is the first candidate and
     * is consumed here (cleared) — it is one-time for this connect only.
     * Polling may update the StateFlow list afterwards, but the local
     * snapshot in [runPTunnelConnect] never changes mid-failover.
     */
    fun startPTunnel() {
        if (ptunnelJob?.isActive == true) return
        if (_ptunnelUiState.value.connectedEndpoint != null) return
        pendingPTunnelCandidate = null
        pendingPTunnelCandidates = null
        val manual = _ptunnelUiState.value.manualEndpoint
        updatePTunnel { it.copy(manualEndpoint = null) }
        ptunnelJob =
            viewModelScope.launch { runPTunnelConnect(fromCandidate = manual) }
    }

    private fun resumePTunnelAfterPermission() {
        if (ptunnelJob?.isActive == true) return
        ptunnelJob =
            viewModelScope.launch { runPTunnelConnect(fromCandidate = pendingPTunnelCandidate) }
    }

    fun stopPTunnel() {
        ptunnelJob?.cancel()
        ptunnelJob = null
        pendingPTunnelCandidate = null
        pendingPTunnelCandidates = null
        viewModelScope.launch {
            tunnelCoordinator.stopTunnel(PTunnelConnect.PTUNNEL_TUNNEL_ID)
            ptunnelStore.clearConnectedEndpoint()
            updatePTunnel { current ->
                current.copy(
                    connectedEndpoint = null,
                    isConnecting = false,
                    connectingEndpoint = null,
                    manualEndpoint = null,
                    displayedEndpoint = current.endpoints.firstOrNull(),
                )
            }
        }
    }

    private suspend fun runPTunnelConnect(fromCandidate: EndpointInfo?) {
        val context = ptunnelContext()
        updatePTunnel { it.copy(isConnecting = true, lastError = null) }
        try {
            var baseConf = ptunnelStore.getBaseConf()
            if (baseConf == null) {
                // Open-time prefetch failed or never ran: try once now.
                // Still no VPN permission is requested for this fetch.
                baseConf =
                    PTunnelConfService.fetch(context)
                        .onSuccess { conf -> ptunnelStore.saveBaseConf(conf) }
                        .onFailure { error ->
                            PTunnelDiagnostics.report(context, "conf", null, error)
                        }
                        .getOrNull()
            }
            if (baseConf == null) {
                updatePTunnel {
                    it.copy(
                        isConnecting = false,
                        connectingEndpoint = null,
                        lastError = "Could not load the connection config",
                    )
                }
                return
            }

            // Failover candidates are a SNAPSHOT (v2): either the snapshot
            // saved when this connect first started (so a permission-resume
            // keeps the same order), or the top 3 in JSON order right now.
            // Polling updates the StateFlow list, never this local list.
            var endpoints = pendingPTunnelCandidates ?: _ptunnelUiState.value.endpoints
            if (endpoints.isEmpty()) {
                endpoints =
                    PTunnelFeedService.fetch(context)
                        .onSuccess { fetched ->
                            ptunnelStore.saveEndpointsJson(
                                PTunnelFeedService.serialize(fetched.endpoints)
                            )
                            applyPTunnelEndpoints(fetched.endpoints)
                        }
                        .getOrNull()?.endpoints
                        ?: ptunnelStore.getEndpointsJson()?.let { raw ->
                            runCatching { PTunnelFeedService.parse(raw).endpoints }.getOrNull()
                        }.orEmpty()
            }
            if (endpoints.isEmpty()) {
                pendingPTunnelCandidates = null
                updatePTunnel {
                    it.copy(
                        isConnecting = false,
                        connectingEndpoint = null,
                        lastError = "No endpoint available",
                    )
                }
                return
            }

            // Failover candidates are the top 3 in JSON order, per spec.
            val candidates = endpoints.take(3)
            pendingPTunnelCandidates = candidates
            val startIndex =
                fromCandidate?.let { wanted -> candidates.indexOfFirst { it.id == wanted.id } }
                    ?.takeIf { it >= 0 } ?: 0

            for (index in startIndex until candidates.size) {
                val candidate = candidates[index]
                updatePTunnel { it.copy(connectingEndpoint = candidate) }
                val config =
                    try {
                        PTunnelConnect.buildConfig(baseConf, candidate)
                    } catch (e: Exception) {
                        PTunnelDiagnostics.report(context, "connect", candidate, e)
                        continue
                    }

                // VPN permission is only requested on connect, never on open.
                // (Orbit `state` is not visible here, so tunnelMode is read
                // from the settings repository instead of the Orbit state.)
                val tunnelMode = settingsRepository.getGeneralSettings().tunnelMode
                if (tunnelMode == TunnelMode.VPN && !serviceManager.hasVpnPermission()) {
                    pendingPTunnelCandidate = candidate
                    updatePTunnel { it.copy(isConnecting = true) }
                    postGlobalSideEffect(
                        GlobalSideEffect.RequestVpnPermission(TunnelMode.VPN, config)
                    )
                    return
                }
                if (
                    !serviceManager.hasNotificationPermission() &&
                        !appStateRepository.isNotificationPermissionRequested()
                ) {
                    appStateRepository.setNotificationPermissionRequested(true)
                    pendingPTunnelCandidate = candidate
                    postGlobalSideEffect(
                        GlobalSideEffect.RequestNotificationPermission(
                            NotificationPendingAction.StartTunnel(config)
                        )
                    )
                    return
                }

                tunnelCoordinator.startTunnel(config)
                if (awaitPTunnelHandshake(candidate)) {
                    pendingPTunnelCandidate = null
                    pendingPTunnelCandidates = null
                    ptunnelStore.saveConnectedEndpoint(candidate.id)
                    updatePTunnel {
                        it.copy(
                            connectedEndpoint = candidate,
                            displayedEndpoint = candidate,
                            isConnecting = false,
                            connectingEndpoint = null,
                            lastError = null,
                        )
                    }
                    return
                }
                // Handshake failed: bring this attempt fully DOWN, then try next.
                tunnelCoordinator.stopTunnel(PTunnelConnect.PTUNNEL_TUNNEL_ID)
                PTunnelDiagnostics.report(
                    context,
                    "connect",
                    candidate,
                    TimeoutException("handshake timeout"),
                    handshakeElapsedMs = PTUNNEL_HANDSHAKE_TIMEOUT_MS,
                )
            }

            pendingPTunnelCandidates = null
            updatePTunnel {
                it.copy(
                    isConnecting = false,
                    connectingEndpoint = null,
                    lastError = "No working endpoint found",
                )
            }
        } finally {
            // Always clear the transient connecting state here. The pending
            // candidate (pendingPTunnelCandidate) survives separately, so a
            // permission grant can still resume from it, while a permission
            // denial leaves the card clean instead of stuck on "connecting".
            // This does not clear connectedEndpoint/displayedEndpoint.
            updatePTunnel { it.copy(isConnecting = false, connectingEndpoint = null) }
        }
    }

    /**
     * Waits up to [PTUNNEL_HANDSHAKE_TIMEOUT_MS] for a handshake on the
     * P-Tunnel tunnel: backend Healthy, or a peer handshake timestamp at or
     * after this attempt started. Polling the backend status flow (not a
     * raw UDP probe) is used because a WireGuard initiation packet is only
     * sent while the tunnel is UP.
     */
    private suspend fun awaitPTunnelHandshake(candidate: EndpointInfo): Boolean {
        val attemptStartSec = System.currentTimeMillis() / 1000
        val deadline = System.currentTimeMillis() + PTUNNEL_HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val active =
                tunnelCoordinator.backendStatus.value
                    .activeTunnels[PTunnelConnect.PTUNNEL_TUNNEL_ID]
            if (active != null) {
                val healthy = active.transportState is Tunnel.State.Up.Healthy
                val handshakeLanded =
                    active.activeConfig?.peers?.any { peer ->
                        val last = peer.lastHandshakeSeconds
                        last != null && last >= attemptStartSec - 2
                    } == true
                if (healthy || handshakeLanded) return true
            }
            delay(250)
        }
        return false
    }

    companion object {
        /** Per-candidate handshake wait (provisional: spec says "check", no window given). */
        const val PTUNNEL_HANDSHAKE_TIMEOUT_MS = 5_000L

        /** Feed poll interval while the P-Tunnel home screen is in the foreground (v2). */
        const val PTUNNEL_POLL_INTERVAL_MS = 60_000L
    }

    fun setAppMode(mode: TunnelMode) = intent {
        if (mode == TunnelMode.VPN || mode == TunnelMode.LOCK_DOWN) {
            if (!serviceManager.hasVpnPermission()) {
                return@intent postSideEffect(GlobalSideEffect.RequestVpnPermission(mode, null))
            }
        }

        tunnelBackendCoordinator.changeMode(mode)
    }

    fun setShouldShowDonationSnackbar(to: Boolean) = intent {
        appStateRepository.setShouldShowDonationSnackbar(to)
    }

    fun setDesktopPromptShown(shown: Boolean) = intent {
        appStateRepository.setDesktopPromptShown(shown)
    }

    fun showSnackMessage(message: StringValue, type: ToastType) = intent {
        postGlobalSideEffect(GlobalSideEffect.Snackbar(message, type))
    }

    suspend fun postSideEffect(globalSideEffect: GlobalSideEffect) {
        globalEffectRepository.post(globalSideEffect)
    }

    fun authenticated() = intent { reduce { state.copy(isPinVerified = true) } }

    suspend fun postGlobalSideEffect(sideEffect: GlobalSideEffect) {
        globalEffectRepository.post(sideEffect)
    }

    fun disableBatteryOptimizationsShown() = intent {
        appStateRepository.setBatteryOptimizationDisableShown(true)
    }

    fun enterReorderMode() = intent {
        val ui = tunnelsUiState.value
        clearSelectedTunnels()
        reorderDraft.value =
            ReorderDraft(
                tunnels = ui.tunnels,
                groups = ui.groups,
                scopeGroupId = null,
                originalTunnels = ui.tunnels,
                originalGroups = ui.groups,
            )
    }

    fun cancelReorder() = intent { reorderDraft.value = null }

    fun exitReorderScope() = intent {
        reorderDraft.update { draft -> draft?.copy(scopeGroupId = null, sortAscending = null) }
    }

    fun enterReorderScope(groupId: Int) = intent {
        reorderDraft.update { draft -> draft?.copy(scopeGroupId = groupId, sortAscending = null) }
    }

    fun applyReorderDraft(groups: List<TunnelGroup>, tunnels: List<TunnelConfig>) {
        reorderDraft.update { draft -> draft?.copy(groups = groups, tunnels = tunnels) }
    }

    fun saveReorder() = intent {
        val draft = reorderDraft.value ?: return@intent
        tunnelGroupRepository.saveAll(draft.groups)
        val changedTunnels = changedFrom(draft.originalTunnels, draft.tunnels)
        if (changedTunnels.isNotEmpty()) tunnelRepository.updateAll(changedTunnels)
        reorderDraft.value = null
        postSideEffect(
            GlobalSideEffect.Snackbar(
                StringValue.StringResource(R.string.config_changes_saved),
                ToastType.Success,
            )
        )
    }

    fun sortReorderByName() = intent {
        val draft = reorderDraft.value ?: return@intent
        val nextAscending =
            when (draft.sortAscending) {
                null -> true
                true -> false
                false -> null
            }
        if (nextAscending == null) {
            reorderDraft.value =
                draft.copy(
                    tunnels = draft.originalTunnels,
                    groups = draft.originalGroups,
                    sortAscending = null,
                )
            return@intent
        }
        val (groups, tunnels) =
            if (draft.scopeGroupId != null) {
                draft.groups to
                    sortGroupChildrenByName(draft.tunnels, draft.scopeGroupId, nextAscending)
            } else {
                sortRootByName(draft.groups, draft.tunnels, nextAscending)
            }
        reorderDraft.value =
            draft.copy(tunnels = tunnels, groups = groups, sortAscending = nextAscending)
    }

    fun sortReorderByLatency() = intent {
        val draft = reorderDraft.value ?: return@intent
        if (pingInFlight.value) return@intent
        pingInFlight.value = true
        postSideEffect(
            GlobalSideEffect.Snackbar(
                StringValue.StringResource(R.string.pinging_servers),
                ToastType.Info,
            )
        )
        try {
            val scopeId = draft.scopeGroupId
            val targets =
                if (scopeId != null) draft.tunnels.filter { it.groupId == scopeId }
                else draft.tunnels.filter { it.groupId == null }
            val results = pingSorted(targets)
            val ordered = results.map { it.first }
            val tunnels =
                if (scopeId != null) {
                    val others = draft.tunnels.filter { it.groupId != scopeId }
                    others + ordered.mapIndexed { index, tunnel -> tunnel.copy(position = index) }
                } else {
                    val grouped = draft.tunnels.filter { it.groupId != null }
                    val slots =
                        draft.tunnels
                            .filter { it.groupId == null }
                            .sortedBy { it.position }
                            .map { it.position }
                    val rewritten = ordered.mapIndexed { index, tunnel ->
                        tunnel.copy(position = slots.getOrElse(index) { tunnel.position })
                    }
                    grouped + rewritten
                }
            val measured = results.associate { (tunnel, ms) -> tunnel.id to ms }
            reorderDraft.value =
                draft.copy(
                    tunnels = tunnels,
                    sortAscending = null,
                    latencies = draft.latencies + measured,
                )
            if (tunnelCoordinator.backendStatus.first().activeTunnels.isEmpty()) {
                pingLatencies.value = pingLatencies.value + measured
            }
        } finally {
            pingInFlight.value = false
        }
    }

    fun pingTunnels() = intent {
        if (pingInFlight.value) return@intent
        if (tunnelCoordinator.backendStatus.first().activeTunnels.isNotEmpty()) return@intent
        pingInFlight.value = true
        postSideEffect(
            GlobalSideEffect.Snackbar(
                StringValue.StringResource(R.string.pinging_servers),
                ToastType.Info,
            )
        )
        try {
            val results = pingSorted(tunnelRepository.userTunnelsFlow.first())
            if (tunnelCoordinator.backendStatus.first().activeTunnels.isEmpty()) {
                pingLatencies.value = results.associate { (tunnel, ms) -> tunnel.id to ms }
            }
        } finally {
            pingInFlight.value = false
        }
    }

    fun createGroupAndMoveSelected(name: String) = intent {
        val ui = tunnelsUiState.value
        val selected = ui.selectedTunnels
        if (selected.isEmpty()) return@intent
        val unique = uniqueDisplayName(name, ui.groups.map { it.name }, "Group")
        // New groups land at the top
        val (shiftedGroups, shiftedTunnels) = shiftRootPositionsBy(ui.groups, ui.tunnels, 1)
        tunnelGroupRepository.saveAll(shiftedGroups)
        if (shiftedTunnels.isNotEmpty()) tunnelRepository.updateAll(shiftedTunnels)
        val groupId =
            tunnelGroupRepository.save(TunnelGroup(name = unique, position = 0, expanded = true))
        moveToGroup(groupId, selected, ui.tunnels)
    }

    fun renameGroup(group: TunnelGroup, name: String) = intent {
        val unique =
            uniqueDisplayName(
                name,
                tunnelsUiState.value.groups.filter { it.id != group.id }.map { it.name },
                "Group",
            )
        tunnelGroupRepository.save(group.copy(name = unique))
    }

    fun toggleGroupExpanded(group: TunnelGroup) = intent {
        tunnelGroupRepository.setExpanded(group.id, !group.expanded)
    }

    fun ungroup(group: TunnelGroup) = intent {
        val ui = tunnelsUiState.value
        val (groups, tunnels) = ungroupKeepingOrder(ui.groups, ui.tunnels, group.id)
        val changedTunnels = changedFrom(ui.tunnels, tunnels)
        if (changedTunnels.isNotEmpty()) tunnelRepository.updateAll(changedTunnels)
        tunnelGroupRepository.saveAll(groups)
        tunnelGroupRepository.delete(group)
        selectedTunnelsRepository.clear()
    }

    fun deleteGroupAndTunnels(group: TunnelGroup) = intent {
        val children = tunnelsUiState.value.tunnels.filter { it.groupId == group.id }
        val activeTunIds =
            tunnelCoordinator.backendStatus.firstOrNull()?.activeTunnels?.map { it.key }
        if (children.any { activeTunIds?.contains(it.id) == true })
            return@intent postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.delete_active_message),
                    ToastType.Error,
                )
            )
        if (children.isNotEmpty()) tunnelRepository.delete(children)
        tunnelGroupRepository.delete(group)
        selectedTunnelsRepository.clear()
    }

    fun moveSelectedToGroup(groupId: Int) = intent {
        val ui = tunnelsUiState.value
        if (ui.selectedTunnels.isEmpty()) return@intent
        moveToGroup(groupId, ui.selectedTunnels, ui.tunnels)
    }

    private fun changedFrom(
        before: List<TunnelConfig>,
        after: List<TunnelConfig>,
    ): List<TunnelConfig> {
        val beforeById = before.associateBy { it.id }
        return after.filter { tunnel ->
            val prior = beforeById[tunnel.id]
            prior == null || prior.position != tunnel.position || prior.groupId != tunnel.groupId
        }
    }

    private suspend fun moveToGroup(
        groupId: Int,
        selected: List<TunnelConfig>,
        all: List<TunnelConfig>,
    ) {
        var next = nextChildPosition(all, groupId)
        val ids = selected.map { it.id }.toSet()
        tunnelRepository.updateAll(
            all.filter { it.id in ids }.map { it.copy(groupId = groupId, position = next++) }
        )
        clearSelectedTunnels()
    }

    // Releases only the selected tunnels from their groups
    fun ungroupSelected() = intent {
        val ui = tunnelsUiState.value
        if (!ui.canUngroup) return@intent
        var next = nextRootPosition(ui.groups, ui.tunnels)
        val ids = ui.selectedTunnels.map { it.id }.toSet()
        val tunnels =
            ui.tunnels.filter { it.id in ids }.map { it.copy(groupId = null, position = next++) }
        if (tunnels.isNotEmpty()) tunnelRepository.updateAll(tunnels)
        clearSelectedTunnels()
    }

    fun exportGroup(group: TunnelGroup, uri: Uri?) = intent {
        val children = tunnelsUiState.value.tunnels.filter { it.groupId == group.id }
        if (children.isEmpty()) {
            postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.no_tunnels_in_group),
                    ToastType.Warning,
                )
            )
            return@intent
        }
        val (fileName, mimeType) = group.asExportFileName()
        postSideEffect(
            GlobalSideEffect.ExportFile(
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                successMessage = StringValue.StringResource(R.string.export_success),
                prepareFile = { shareFile ->
                    // Always a zip, even for one tunnel, so the group name survives the export
                    val files = createConfFiles(children)
                    try {
                        fileUtils.zipAll(shareFile, files).getOrThrow()
                    } finally {
                        files.forEach { file -> if (file.exists()) file.delete() }
                    }
                },
                onComplete = {},
            )
        )
    }

    private suspend fun pingSorted(tunnels: List<TunnelConfig>): List<Pair<TunnelConfig, Double>> {
        return withContext(Dispatchers.IO) {
            tunnels
                .map { tunnel ->
                    async {
                        val config =
                            try {
                                tunnel.getConfig()
                            } catch (_: Exception) {
                                null
                            }
                        val endpoint = config?.peers?.firstOrNull()?.host
                        val latency =
                            if (endpoint != null) {
                                try {
                                    // 3 pings, a single dropped packet on an otherwise reachable
                                    // server would otherwise show as unreachable. Each tunnel is
                                    // pinged concurrently, so this doesn't multiply by tunnel count
                                    val stats = networkUtils.pingWithStats(endpoint, 3)
                                    if (stats.isReachable) stats.rttAvg else Double.MAX_VALUE
                                } catch (_: Exception) {
                                    Double.MAX_VALUE
                                }
                            } else {
                                Double.MAX_VALUE
                            }
                        tunnel to latency
                    }
                }
                .awaitAll()
                .sortedBy { it.second }
        }
    }

    fun importTunnelConfigs(configs: Map<QuickConfig, TunnelName>) = intent {
        try {
            val ui = tunnelsUiState.value
            val tunnelConfigs =
                configs.entries.mapIndexed { index, (quick, name) ->
                    val config = Config.parseQuickString(quick)
                    config.validate()
                    // Final positions assigned after validation below, placed at the top.
                    TunnelConfig.fromConfig(config, name).copy(position = index)
                }
            // New tunnels land at the top
            val (shiftedGroups, shiftedTunnels) =
                shiftRootPositionsBy(ui.groups, ui.tunnels, tunnelConfigs.size)
            tunnelGroupRepository.saveAll(shiftedGroups)
            if (shiftedTunnels.isNotEmpty()) tunnelRepository.updateAll(shiftedTunnels)
            tunnelRepository.saveTunnelsUniquely(tunnelConfigs, state.tunnelNames.map { it.value })
        } catch (e: Exception) {
            if (e is ConfigParseException) {
                postSideEffect(GlobalSideEffect.Snackbar(e.asStringValue(), ToastType.Error))
            } else {
                postSideEffect(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.config_error),
                        ToastType.Error,
                    )
                )
            }
        }
    }

    fun importFromClipboard(conf: String) {
        importTunnelConfigs(mapOf(conf to null))
    }

    fun importFromQr(conf: String) = intent { importFromClipboard(conf) }

    fun promptWgImport(url: String) = intent { reduce { state.copy(pendingWgImportUrl = url) } }

    fun dismissWgImport() = intent { reduce { state.copy(pendingWgImportUrl = null) } }

    fun importFromUrl(url: String) = intent {
        reduce { state.copy(pendingWgImportUrl = null) }
        try {
            httpClient.prepareGet(url).execute { response ->
                if (response.status.value !in 200..299) {
                    throw IOException("Server returned error: ${response.status.value}")
                }

                val body = response.bodyAsText()

                val contentType = response.contentType()
                val isHtml =
                    (contentType?.match(ContentType.Text.Html) == true) ||
                        body.trimStart().let {
                            it.startsWith("<!DOCTYPE", ignoreCase = true) ||
                                it.startsWith("<html", ignoreCase = true)
                        }

                if (isHtml) {
                    postSideEffect(
                        GlobalSideEffect.Snackbar(
                            StringValue.StringResource(R.string.error_invalid_config_url),
                            ToastType.Error,
                        )
                    )
                    return@execute
                }

                importFromClipboard(body)
            }
        } catch (e: Exception) {
            Timber.e(e)
            postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.error_download_failed),
                    ToastType.Error,
                )
            )
        }
    }

    fun importFromUri(uri: Uri) = intent {
        fileUtils
            .readConfigsFromUri(uri)
            .onSuccess { configs -> importTunnelConfigs(configs) }
            .onFailure { error ->
                val message =
                    when {
                        // Broken Android TV and legacy pickers can return file:// without a read
                        // grant
                        error.isFileAccessDenied() ->
                            StringValue.StringResource(R.string.error_no_file_explorer)
                        error is IOException ->
                            StringValue.StringResource(R.string.error_download_failed)
                        else -> StringValue.StringResource(R.string.error_file_extension)
                    }
                postSideEffect(GlobalSideEffect.Snackbar(message, ToastType.Error))
            }
    }

    fun toggleSelectAllTunnels() = intent {
        val ui = tunnelsUiState.value
        val allSelected =
            ui.selectedTunnels.size == ui.tunnels.size && ui.selectedGroups.size == ui.groups.size
        if (allSelected) {
            selectedTunnelsRepository.clear()
        } else {
            selectedTunnelsRepository.set(
                TunnelsSelection(tunnels = ui.tunnels, groups = ui.groups)
            )
        }
    }

    fun clearSelectedTunnels() = intent { selectedTunnelsRepository.clear() }

    fun toggleSelectedTunnel(tunnelId: Int) = intent {
        val ui = tunnelsUiState.value
        val selectedTuns =
            ui.selectedTunnels.toMutableList().apply {
                val removed = removeIf { it.id == tunnelId }
                if (!removed) addAll(ui.tunnels.filter { it.id == tunnelId })
            }
        selectedTunnelsRepository.set(
            TunnelsSelection(tunnels = selectedTuns, groups = ui.selectedGroups)
        )
    }

    fun toggleSelectedGroup(groupId: Int) = intent {
        val ui = tunnelsUiState.value
        val selectedGroups =
            ui.selectedGroups.toMutableList().apply {
                val removed = removeIf { it.id == groupId }
                if (!removed) addAll(ui.groups.filter { it.id == groupId })
            }
        selectedTunnelsRepository.set(
            TunnelsSelection(tunnels = ui.selectedTunnels, groups = selectedGroups)
        )
    }

    fun deleteSelectedTunnels() = intent {
        val ui = tunnelsUiState.value
        val activeTunIds =
            tunnelCoordinator.backendStatus.firstOrNull()?.activeTunnels?.map { it.key }.orEmpty()
        val selectedTuns = ui.selectedTunnels
        if (selectedTuns.any { activeTunIds.contains(it.id) })
            return@intent postSideEffect(
                GlobalSideEffect.Snackbar(
                    StringValue.StringResource(R.string.delete_active_message),
                    ToastType.Error,
                )
            )
        if (selectedTuns.isNotEmpty()) tunnelRepository.delete(selectedTuns)
        var groups = ui.groups
        val beforeUngroup = ui.tunnels.filter { tun -> selectedTuns.none { it.id == tun.id } }
        var tunnels = beforeUngroup
        ui.selectedGroups.forEach { group ->
            val result = ungroupKeepingOrder(groups, tunnels, group.id)
            groups = result.first
            tunnels = result.second
            tunnelGroupRepository.delete(group)
        }
        if (ui.selectedGroups.isNotEmpty()) {
            val changedTunnels = changedFrom(beforeUngroup, tunnels)
            if (changedTunnels.isNotEmpty()) tunnelRepository.updateAll(changedTunnels)
            tunnelGroupRepository.saveAll(groups)
        }
        clearSelectedTunnels()
    }

    fun copySelectedTunnel() = intent {
        val selected = tunnelsUiState.value.selectedTunnels.firstOrNull() ?: return@intent
        val config = selected.getConfig()
        val ui = tunnelsUiState.value
        // Placed right after the original, not appended to the end - shift everything after it
        // in the same scope (its group, or root) forward by one to open that slot.
        val (shiftedGroups, shiftedTunnels) =
            shiftForInsertAfter(ui.groups, ui.tunnels, selected.groupId, selected.position)
        val copy =
            TunnelConfig.fromConfig(config, selected.name)
                .copy(groupId = selected.groupId, position = selected.position + 1)
        tunnelGroupRepository.saveAll(shiftedGroups)
        if (shiftedTunnels.isNotEmpty()) tunnelRepository.updateAll(shiftedTunnels)
        tunnelRepository.saveTunnelsUniquely(listOf(copy), state.tunnelNames.map { it.value })
        clearSelectedTunnels()
    }

    fun exportSelectedTunnels(uri: Uri?) = intent {
        val ui = tunnelsUiState.value
        val selectedTunnels =
            (ui.selectedTunnels +
                    ui.selectedGroups.flatMap { group ->
                        ui.tunnels.filter { it.groupId == group.id }
                    })
                .distinctBy { it.id }
        if (selectedTunnels.isEmpty()) return@intent

        val (fileName, mimeType) = selectedTunnels.asFileExportName()
        postSideEffect(
            GlobalSideEffect.ExportFile(
                uri = uri,
                fileName = fileName,
                mimeType = mimeType,
                successMessage = StringValue.StringResource(R.string.export_success),
                prepareFile = { shareFile ->
                    if (selectedTunnels.size == 1) {
                        shareFile.writeText(selectedTunnels.first().quickConfig)
                        if (shareFile.length() == 0L) {
                            throw IOException("Failed to create config file")
                        }
                    } else {
                        val files = createConfFiles(selectedTunnels)
                        try {
                            fileUtils.zipAll(shareFile, files).getOrThrow()
                        } finally {
                            files.forEach { file -> if (file.exists()) file.delete() }
                        }
                    }
                },
                onComplete = { clearSelectedTunnels() },
            )
        )
    }

    fun setScreenRecordingSecurity(to: Boolean) = intent {
        settingsRepository.updateScreenRecordingSecurity(to)
    }

    suspend fun createConfFiles(tunnels: Collection<TunnelConfig>): List<File> =
        tunnels.mapNotNull { config ->
            if (config.quickConfig.isNotBlank()) {
                fileUtils.createFile(config.name, config.quickConfig)
            } else null
        }
}

private data class ReorderDraft(
    val tunnels: List<TunnelConfig>,
    val groups: List<TunnelGroup>,
    val scopeGroupId: Int?,
    val sortAscending: Boolean? = null,
    val originalTunnels: List<TunnelConfig>,
    val originalGroups: List<TunnelGroup>,
    val latencies: Map<Int, Double> = emptyMap(),
)

private data class TunnelsChrome(
    val loading: Boolean,
    val selectedTunCount: Int,
    val canMoveToGroup: Boolean,
    val isReorderMode: Boolean,
    val reorderScopeTitle: String?,
    val hasGroups: Boolean,
    val canReorder: Boolean,
    val hasActiveTunnel: Boolean,
)
