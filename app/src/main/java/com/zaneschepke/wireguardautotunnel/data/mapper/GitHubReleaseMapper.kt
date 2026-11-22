package com.zaneschepke.wireguardautotunnel.data.mapper

import com.zaneschepke.wireguardautotunnel.data.entity.Asset
import com.zaneschepke.wireguardautotunnel.data.entity.GitHubRelease
import com.zaneschepke.wireguardautotunnel.domain.model.AppUpdate

object GitHubReleaseMapper {
    fun toAppUpdate(
        gitHubRelease: GitHubRelease,
        apkAsset: Asset,
        newVersion: String,
    ): AppUpdate =
        AppUpdate(
            version = newVersion,
            releaseUrl = gitHubRelease.htmlUrl,
            // Lovable proxy serves the release APK (Phoenix pattern): the
            // download works without VPN, unlike a direct GitHub asset URL.
            apkUrl = "https://ipmh.lovable.app/api/public/apk/ptunnel",
            // Versioned GitHub asset URL: the fallback when the Lovable
            // route serves a stale APK in the publish window. Derived
            // from the release page URL, so it always names this tag.
            githubApkUrl =
                gitHubRelease.htmlUrl
                    ?.replace("/releases/tag/", "/releases/download/")
                    ?.let { "$it/${apkAsset.name}" },
            apkFileName = apkAsset.name,
            apkSize = apkAsset.size.takeIf { it > 0 },
        )
}
