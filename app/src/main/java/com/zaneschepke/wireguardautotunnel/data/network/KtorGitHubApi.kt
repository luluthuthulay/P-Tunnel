package com.zaneschepke.wireguardautotunnel.data.network

import com.zaneschepke.wireguardautotunnel.data.entity.Asset
import com.zaneschepke.wireguardautotunnel.data.entity.GitHubRelease
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

class KtorGitHubApi(private val client: HttpClient) : GitHubApi {
    override suspend fun getLatestRelease(owner: String, repo: String): Result<GitHubRelease> {
        return try {
            // Phoenix pattern: github.com/<owner>/<repo>/releases/latest
            // 302-redirects to the latest release page. Read the tag from the
            // Location header instead of calling api.github.com, so the update
            // check works without VPN on networks where the API host is
            // blocked but github.com is reachable.
            val response =
                client.config { followRedirects = false }
                    .get("https://github.com/$owner/$repo/releases/latest")
            val location =
                response.headers[HttpHeaders.Location]
                    ?: return Result.failure(Exception("Latest release did not redirect"))
            val tag = location.substringAfterLast("/").substringBefore("?")
            if (tag.isBlank()) {
                return Result.failure(Exception("Latest release redirect has no tag"))
            }
            val version = tag.removePrefix("v")
            // The release ships a single universal standalone APK whose name
            // is fixed from the version; the download itself is the Lovable
            // route (see GitHubReleaseMapper), so no API asset list is needed.
            val release =
                GitHubRelease(
                    tagName = tag,
                    htmlUrl = "https://github.com/$owner/$repo/releases/tag/$tag",
                    assets =
                        listOf(
                            Asset(
                                name = "wgtunnel-standalone-v$version.apk",
                                browserDownloadUrl = "",
                                size = 0L,
                            )
                        ),
                )
            Result.success(release)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getNightlyRelease(owner: String, repo: String): Result<GitHubRelease> {
        return try {
            // Only one nightly exists at a time
            val response: GitHubRelease =
                client.get("https://api.github.com/repos/$owner/$repo/releases/tags/nightly").body()
            Result.success(response)
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                HttpStatusCode.Forbidden -> Result.failure(Exception("Rate limit exceeded"))
                HttpStatusCode.NotFound -> Result.failure(Exception("Nightly release not found"))
                else -> Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
