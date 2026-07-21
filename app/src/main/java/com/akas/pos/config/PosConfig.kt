package com.akas.pos.config

import android.content.Context

data class PosConfig(
    val startUrl: String,
    val allowedHosts: List<String>,
    val appName: String,
    val launcherLogo: String,
    val defaultPaperWidthMm: Int
) {
    fun isAllowedHost(host: String?): Boolean {
        val normalizedHost = host?.lowercase()?.trimEnd('.') ?: return false
        return allowedHosts.any { rule ->
            val normalizedRule = rule.lowercase().trim()
            when {
                normalizedRule.startsWith("*.") -> {
                    val domain = normalizedRule.removePrefix("*.")
                    normalizedHost == domain || normalizedHost.endsWith(".$domain")
                }
                else -> normalizedHost == normalizedRule
            }
        }
    }
}

object PosConfigLoader {
    private const val FILE_NAME = "pos-config.txt"

    fun load(context: Context): PosConfig {
        val values = context.assets.open(FILE_NAME).bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    val separator = line.indexOf('=')
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
        }

        return PosConfig(
            startUrl = values["START_URL"].orEmpty()
                .ifBlank { "https://akas.my.id/" },
            allowedHosts = values["ALLOWED_HOSTS"].orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .ifEmpty { listOf("akas.my.id", "*.akas.my.id") },
            appName = values["APP_NAME"].orEmpty().ifBlank { "Akas - Andal Kasir" },
            launcherLogo = values["LAUNCHER_LOGO"].orEmpty()
                .ifBlank { "drawable/akas_logo.png" },
            defaultPaperWidthMm = values["DEFAULT_PAPER_WIDTH_MM"]?.toIntOrNull()
                ?.takeIf { it == 58 || it == 80 }
                ?: 58
        )
    }
}
