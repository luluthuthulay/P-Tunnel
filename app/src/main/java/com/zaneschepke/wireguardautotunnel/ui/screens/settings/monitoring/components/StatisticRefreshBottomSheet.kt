package com.zaneschepke.wireguardautotunnel.ui.screens.settings.monitoring.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.StatisticRefresh
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.CustomBottomSheet
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.SheetOption
import com.zaneschepke.wireguardautotunnel.util.extensions.asIcon
import kotlin.enums.enumEntries

@Composable
fun StatisticRefreshBottomSheet(
    onRefreshChange: (StatisticRefresh) -> Unit,
    refresh: StatisticRefresh,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    CustomBottomSheet(
        enumEntries<StatisticRefresh>().map {
            SheetOption(
                leadingIcon = it.asIcon(),
                label = it.asString(context),
                onClick = {
                    onDismiss()
                    onRefreshChange(it)
                },
                description = it.asDescription(context),
                selected = refresh == it,
            )
        },
        title = stringResource(R.string.refresh_rate),
        description = stringResource(R.string.refresh_rate_desc),
    ) {
        onDismiss()
    }
}
