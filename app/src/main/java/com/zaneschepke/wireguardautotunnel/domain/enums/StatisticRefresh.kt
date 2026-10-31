package com.zaneschepke.wireguardautotunnel.domain.enums

import android.content.Context
import com.zaneschepke.wireguardautotunnel.R

enum class StatisticRefresh(val value: Int) {
    LIVE(1),
    BALANCED(3),
    BATTERY_SAVER(10);

    fun asString(context: Context): String {
        return when (this) {
            LIVE -> context.getString(R.string.live)
            BALANCED -> context.getString(R.string.balanced)
            BATTERY_SAVER -> context.getString(R.string.balance_saver)
        }
    }

    fun asDescription(context: Context): String {
        return when (this) {
            LIVE -> context.getString(R.string.statistic_refresh_live_desc)
            BALANCED -> context.getString(R.string.statistic_refresh_balanced_desc)
            BATTERY_SAVER -> context.getString(R.string.statistic_refresh_battery_saver_desc)
        }
    }

    companion object {
        fun fromValue(value: Int): StatisticRefresh = entries.find { it.value == value } ?: BALANCED
    }
}
