package com.prismai.llmhost.cloud.prismatix

import com.prismai.llmhost.work.DistributionConfig
import com.prismai.llmhost.work.DistributionConfigProvider
import java.net.URI

class EndpointTrustException(message: String) : SecurityException(message)

/**
 * Runtime configuration for the Prismatix Cloud Intelligence gateway with
 * strict endpoint trust boundaries to prevent credential leakage.
 *
 * Invariant P0:
 * Play Store distribution MUST strictly pin the exact approved production
 * hostnames. Wildcard domains like `*.supabase.co` are prohibited because
 * another Supabase project could otherwise receive and exfiltrate user JWTs.
 */
data class PrismatixConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.prismatix.ai/functions/v1/router"

        /**
         * Pinned exact hostnames permitted in production Play Store builds.
         * NEVER use wildcard subdomains like `*.supabase.co`.
         */
        val PROD_ALLOWED_HOSTS: Set<String> = setOf(
            "api.prismatix.ai",
            "router.prismatix.ai",
            "prismatix-router.supabase.co",
        )

        /**
         * Validates that [url] is safe to communicate with according to the active [DistributionConfig].
         */
        fun validateEndpoint(
            url: String,
            distributionConfig: DistributionConfig = DistributionConfigProvider,
        ) {
            val uri = try {
                URI.create(url)
            } catch (e: Exception) {
                throw EndpointTrustException("Invalid endpoint URI: $url")
            }

            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""

            if (!distributionConfig.developerWorkMode) {
                // Play Store Distribution Invariant (Strict Exact-Host Pinning)
                if (scheme != "https") {
                    throw EndpointTrustException("Play distribution requires secure HTTPS endpoint, got '$scheme'")
                }
                if (host !in PROD_ALLOWED_HOSTS) {
                    throw EndpointTrustException(
                        "Play distribution rejects unpinned host '$host'. Must be one of: ${PROD_ALLOWED_HOSTS.joinToString()}"
                    )
                }
            } else {
                // Dev Distribution Invariant
                if (scheme == "http") {
                    val isLocalhost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
                    if (!isLocalhost) {
                        throw EndpointTrustException("Dev distribution rejects cleartext HTTP to non-local host '$host'")
                    }
                } else if (scheme != "https") {
                    throw EndpointTrustException("Unsupported scheme '$scheme', expected https or local http")
                }
            }
        }
    }
}
