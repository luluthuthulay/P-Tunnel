package com.zaneschepke.wireguardautotunnel.ui.screens.settings.dns.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.ForeignDnsPolicy
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.CustomBottomSheet
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.SheetOption
import com.zaneschepke.wireguardautotunnel.util.extensions.asIcon
import kotlin.enums.enumEntries

@Composable
fun TransitDnsPolicyBottomSheet(
    onPolicyChange: (ForeignDnsPolicy) -> Unit,
    policy: ForeignDnsPolicy,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    CustomBottomSheet(
        enumEntries<ForeignDnsPolicy>().map {
            SheetOption(
                leadingIcon = it.asIcon(),
                label = it.asString(context),
                onClick = {
                    onDismiss()
                    onPolicyChange(it)
                },
                description = it.asDescription(context),
                selected = policy == it,
            )
        },
        title = stringResource(R.string.transit_dns_policy),
        description = stringResource(R.string.transit_dns_policy_desc),
    ) {
        onDismiss()
    }
}
