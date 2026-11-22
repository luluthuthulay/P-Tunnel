package com.zaneschepke.wireguardautotunnel.domain.model

data class AppUpdate(
    val version: String,
    val releaseUrl: String?,
    val apkUrl: String?,
    val apkFileName: String?,
    val apkSize: Long? = null,
    // Versioned GitHub asset URL — the fallback when the Lovable route
    // serves a stale/wrong APK (Phoenix pattern: verify the downloaded
    // APK's version, fall back to GitHub on mismatch).
    val githubApkUrl: String? = null,
)
