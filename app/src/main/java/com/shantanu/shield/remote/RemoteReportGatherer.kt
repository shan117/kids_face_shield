package com.shantanu.shield.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.ui.stats.StatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Builds the aggregate [RemoteReportPayload] from on-device stats — the ONLY place app usage becomes a
 * shareable summary. By construction it can emit nothing but daily totals + top-app names (the payload
 * type has no field for raw events, content, or timestamps of activity), which is what keeps the feature
 * private rather than spyware. See PARENT_REMOTE_REPORT_PLAN.md §1.
 *
 * Tier-2 adds the child's installed-app *list* (package + label only — never usage tied to it beyond the
 * existing top-apps) so the parent can pick allowed apps / per-app caps remotely. The user opted into this.
 */
class RemoteReportGatherer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val dataStoreManager: DataStoreManager,
) {
    suspend fun build(): RemoteReportPayload {
        val kids = if (dataStoreManager.multiKidEnabled.first()) {
            dataStoreManager.kidProfiles.first().map { profile ->
                val sessions = statsRepository.profileSessionsFor(profile.id)
                val week = statsRepository.profileDailyTotals(sessions, WEEK_DAYS).map { it.totalMs }
                val buckets = statsRepository.profileUsageForDays(sessions, WEEK_DAYS)
                RemoteReportBuilder.kidReport(
                    name = profile.name,
                    limitMin = profile.dailyLimitMinutes,
                    week = week,
                    topApps = buckets.take(TOP_APPS).map { AppStat(it.name, it.foregroundMs) },
                    weeks = statsRepository.profileWeeklyDailyAverages(sessions, TREND_WEEKS),
                    categories = statsRepository.categoryTotals(buckets).map { AppStat(it.first, it.second) },
                )
            }
        } else {
            val week = statsRepository.dailyTotals(WEEK_DAYS).map { it.totalMs }
            val buckets = statsRepository.usageForDays(WEEK_DAYS)
            val sessionStats = statsRepository.sessionStatsForDays(WEEK_DAYS)
            listOf(
                RemoteReportBuilder.kidReport(
                    name = "Child",
                    limitMin = dataStoreManager.dailyLimitMinutes.first(),
                    week = week,
                    topApps = buckets.take(TOP_APPS).map { AppStat(it.name, it.foregroundMs) },
                    weeks = statsRepository.weeklyDailyAverages(TREND_WEEKS),
                    categories = statsRepository.categoryTotals(buckets).map { AppStat(it.first, it.second) },
                    hourly = statsRepository.hourlyControlledMs(WEEK_DAYS).toList(),
                    sessions = sessionStats.count,
                    longestMs = sessionStats.longestMs,
                    // Current config mirror so the parent edits real values (matches the global remote commands).
                    allowedPreset = dataStoreManager.alwaysAllowedPreset.first(),
                    autoBlockNewApps = if (dataStoreManager.autoBlockNewApps.first()) 1 else 0,
                    // Tier-2: app list + current custom-allow + per-app caps for the parent's remote picker.
                    installedApps = pickableApps(),
                    customAllowed = dataStoreManager.customAlwaysAllowed.first().toList(),
                    perAppLimits = dataStoreManager.perAppLimits.first().map { AppStat(it.key, it.value.toLong()) },
                    extensionsMin = (dataStoreManager.extensionsTodayMs.first() / 60_000L).toInt(),
                )
            )
        }
        return RemoteReportPayload(System.currentTimeMillis(), kids)
    }

    /** User-pickable apps on the child (package + label) — same filter as the on-device app list: user apps
     *  (or updated system apps), excluding our own. Label only; no usage data is attached here. */
    private fun pickableApps(): List<AppEntry> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    (isUpdatedSystem || !isSystem) && app.packageName != context.packageName
                }
                .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val WEEK_DAYS = 7
        private const val TOP_APPS = 5
        private const val TREND_WEEKS = 4
    }
}
