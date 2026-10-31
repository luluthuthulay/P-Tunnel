package com.zaneschepke.wireguardautotunnel.ui.screens.settings.dns.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.SplitDnsSuffixTarget
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.CustomBottomSheet
import com.zaneschepke.wireguardautotunnel.ui.common.sheet.SheetOption
import com.zaneschepke.wireguardautotunnel.util.extensions.asIcon
import kotlin.enums.enumEntries

@Composable
fun SplitSuffixTargetBottomSheet(
    onTargetChange: (SplitDnsSuffixTarget) -> Unit,
    target: SplitDnsSuffixTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    CustomBottomSheet(
        enumEntries<SplitDnsSuffixTarget>().map {
            SheetOption(
                leadingIcon = it.asIcon(),
                label = it.asString(context),
                onClick = {
                    onDismiss()
                    onTargetChange(it)
                },
                description = it.asDescription(context),
                selected = target == it,
            )
        },
        title = stringResource(R.string.split_suffix_target),
        description = stringResource(R.string.split_suffix_target_sheet_desc),
    ) {
        onDismiss()
    }
}
