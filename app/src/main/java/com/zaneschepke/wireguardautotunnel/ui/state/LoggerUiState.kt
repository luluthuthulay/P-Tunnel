package com.zaneschepke.wireguardautotunnel.ui.state

import com.zaneschepke.logcatter.model.LogMessage
import com.zaneschepke.wireguardautotunnel.domain.model.MonitoringSettings

data class IdentifiedLogMessage(val id: Long, val message: LogMessage)

data class LoggerUiState(
    val messages: List<IdentifiedLogMessage> = emptyList(),
    val monitoringSettings: MonitoringSettings = MonitoringSettings(),
    val isLoading: Boolean = true,
)
