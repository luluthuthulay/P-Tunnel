package com.zaneschepke.wireguardautotunnel.ui.screens.settings.dns.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.BootstrapDnsProtocol
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.CustomBottomSheet
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.SheetOption
import com.zaneschepke.wireguardautotunnel.util.extensions.asIcon
import kotlin.enums.enumEntries

@Composable
fun ResolutionMethodBottomSheet(
    onProtocolChange: (BootstrapDnsProtocol) -> Unit,
    protocol: BootstrapDnsProtocol,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    CustomBottomSheet(
        enumEntries<BootstrapDnsProtocol>().map {
            SheetOption(
                leadingIcon = it.asIcon(),
                label = it.asString(context),
                onClick = {
                    onDismiss()
                    onProtocolChange(it)
                },
                description = it.asDescription(context),
                selected = protocol == it,
            )
        },
        title = stringResource(R.string.resolution_method),
        description = stringResource(R.string.resolution_method_desc),
    ) {
        onDismiss()
    }
}
