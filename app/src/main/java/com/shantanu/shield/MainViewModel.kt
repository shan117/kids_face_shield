package com.shantanu.shield

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.data.KidProfile
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.util.AppCategorizer
import com.shantanu.shield.util.AppCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dataStoreManager: DataStoreManager,
    private val entitlements: EntitlementRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val protectedApps = dataStoreManager.protectedApps
    val faceEmbedding = dataStoreManager.faceEmbedding
    val lockMessageType = dataStoreManager.lockMessageType
    val lockDeviceSettings = dataStoreManager.lockDeviceSettings
    val lockOwnApp = dataStoreManager.lockOwnApp

    // ---- Kid Mode + Screen Time flows (Phase 2) ----
    val ownerType = dataStoreManager.ownerType
    val dailyLimitMinutes = dataStoreManager.dailyLimitMinutes
    val alwaysAllowedPreset = dataStoreManager.alwaysAllowedPreset
    val customAlwaysAllowed = dataStoreManager.customAlwaysAllowed
    val screenTimeUsedMs = dataStoreManager.screenTimeUsedMs
    val extensionsTodayMs = dataStoreManager.extensionsTodayMs
    val kidSessionEndAt = dataStoreManager.kidSessionEndAt
    val firstRunCompleted = dataStoreManager.firstRunCompleted
    val coachMarksEnabled = dataStoreManager.coachMarksEnabled
    val seenTours = dataStoreManager.seenTours

    // ---- Multiple-kids profiles (Phase 4) ----
    val multiKidEnabled = dataStoreManager.multiKidEnabled
    val kidProfiles = dataStoreManager.kidProfiles
    val kidFaceEmbeddings = dataStoreManager.kidFaceEmbeddings
    val multiKidUnlocked = entitlements.isUnlocked(Feature.MULTI_KID_PROFILES)
    /** profileId -> (package -> daily-limit minutes), for Multiple-kids per-app caps. */
    val kidPerAppLimits = dataStoreManager.kidPerAppLimits

    // ---- Earn screen-time (Phase 7) ----
    val earnedTasks = dataStoreManager.earnedTasks
    val earnedUnlocked = entitlements.isUnlocked(Feature.EARNED_TIME)

    // ---- Schedules (Phase 7) ----
    val schedules = dataStoreManager.schedules
    val schedulesUnlocked = entitlements.isUnlocked(Feature.SCHEDULES)

    // ---- Auto-lock new apps (Phase 7) ----
    val autoBlockNewApps = dataStoreManager.autoBlockNewApps
    val autoBlockUnlocked = entitlements.isUnlocked(Feature.NEW_APP_AUTO_BLOCK)
    fun setAutoBlockNewApps(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setAutoBlockNewApps(enabled) }
    }

    // ---- Per-app limits (Phase 7) ----
    val perAppLimits = dataStoreManager.perAppLimits
    val perAppLimitsUnlocked = entitlements.isUnlocked(Feature.PER_APP_LIMITS)
    fun setPerAppLimit(pkg: String, minutes: Int) {
        viewModelScope.launch { dataStoreManager.setPerAppLimit(pkg, minutes) }
    }

    // ---- Theme accent (Phase 7) ----
    val themeAccent = dataStoreManager.themeAccent
    val themesUnlocked = entitlements.isUnlocked(Feature.THEMES)
    fun setThemeAccent(key: String) {
        viewModelScope.launch { dataStoreManager.setThemeAccent(key) }
    }

    val filteredApps: StateFlow<List<AppInfo>> = combine(_installedApps, _searchQuery, protectedApps) { apps, query, protected ->
        val list = if (query.isBlank()) apps else apps.filter { it.name.contains(query, ignoreCase = true) }
        // Sort so that protected apps come first, then sort by name
        list.sortedWith(compareByDescending<AppInfo> { protected.contains(it.packageName) }.thenBy { it.name })
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Grouped view used by the Parent-mode app picker when no search is active. Each
    // group carries the count of currently-protected apps so the section header can
    // render its master switch + "x / y" label without re-deriving on every recomposition.
    val groupedApps: StateFlow<List<AppGroup>> = combine(_installedApps, protectedApps) { apps, protected ->
        val byCategory = apps.groupBy { it.category }
        AppCategory.values()
            .sortedBy { it.order }
            .mapNotNull { cat ->
                val catApps = byCategory[cat]?.sortedBy { it.name.lowercase() } ?: return@mapNotNull null
                if (catApps.isEmpty()) return@mapNotNull null
                AppGroup(
                    category = cat,
                    apps = catApps,
                    protectedCount = catApps.count { protected.contains(it.packageName) },
                    totalCount = catApps.size
                )
            }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _usageStats = MutableStateFlow<List<AppUsageInfo>>(emptyList())
    val usageStats: StateFlow<List<AppUsageInfo>> = _usageStats.asStateFlow()

    init {
        loadInstalledApps()
        fetchUsageStats(0)
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isUpdatedSystemApp = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        (isUpdatedSystemApp || !isSystemApp) && app.packageName != context.packageName
                    }
                    .map {
                        val label = it.loadLabel(pm).toString()
                        AppInfo(
                            name = label,
                            packageName = it.packageName,
                            icon = it.loadIcon(pm),
                            category = AppCategorizer.categoryOf(it, label)
                        )
                    }
            }
            _installedApps.value = apps
        }
    }

    fun fetchUsageStats(daysAgo: Int) {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
                
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                val startTime = calendar.timeInMillis
                
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                val endTime = if (daysAgo == 0) System.currentTimeMillis() else calendar.timeInMillis

                val usageList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                val pm = context.packageManager
                
                usageList.mapNotNull { stat ->
                    if (stat.totalTimeInForeground <= 0) return@mapNotNull null
                    try {
                        val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                        AppUsageInfo(
                            name = pm.getApplicationLabel(appInfo).toString(),
                            packageName = stat.packageName,
                            icon = pm.getApplicationIcon(appInfo),
                            usageTimeMs = stat.totalTimeInForeground
                        )
                    } catch (e: Exception) { null }
                }
                .groupBy { it.packageName }
                .map { (_, group) -> group.maxByOrNull { it.usageTimeMs }!! }
                .sortedByDescending { it.usageTimeMs }
            }
            _usageStats.value = stats
        }
    }

    fun setLockMessageType(type: Int) {
        viewModelScope.launch {
            dataStoreManager.setLockMessageType(type)
        }
    }

    fun setLockDeviceSettings(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setLockDeviceSettings(enabled) }
    }

    fun setLockOwnApp(enabled: Boolean) {
        viewModelScope.launch { dataStoreManager.setLockOwnApp(enabled) }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun toggleAppProtection(packageName: String) { viewModelScope.launch { dataStoreManager.toggleProtectedApp(packageName) } }
    fun saveFaceEmbedding(embedding: FloatArray) { viewModelScope.launch { dataStoreManager.saveFaceEmbedding(embedding) } }

    // Batch-toggle every app in a given category. Single DataStore write — avoids
    // 30 sequential edits when the user taps "Protect all Games".
    fun toggleAllInCategory(category: AppCategory, protect: Boolean) {
        viewModelScope.launch {
            val catApps = _installedApps.value.filter { it.category == category }.map { it.packageName }
            val current = dataStoreManager.protectedApps.first()
            val next = if (protect) current + catApps.toSet() else current - catApps.toSet()
            dataStoreManager.setProtectedApps(next)
        }
    }

    // ---- Kid Mode + Screen Time setters (Phase 2) ----
    fun setOwnerType(type: String) { viewModelScope.launch { dataStoreManager.setOwnerType(type) } }
    fun setDailyLimitMinutes(minutes: Int) { viewModelScope.launch { dataStoreManager.setDailyLimitMinutes(minutes) } }
    fun setAlwaysAllowedPreset(preset: Int) { viewModelScope.launch { dataStoreManager.setAlwaysAllowedPreset(preset) } }
    fun toggleCustomAlwaysAllowed(packageName: String) { viewModelScope.launch { dataStoreManager.toggleCustomAlwaysAllowed(packageName) } }

    // ---- Multiple-kids profiles (Phase 4) ----
    // Turning multi-kid on the first time migrates the current single-kid settings into "Kid 1"
    // and seeds a "Kid 2" with the same defaults.
    fun setMultiKidEnabled(on: Boolean) {
        viewModelScope.launch {
            if (on && dataStoreManager.kidProfiles.first().isEmpty()) {
                val limit = dataStoreManager.dailyLimitMinutes.first()
                val preset = dataStoreManager.alwaysAllowedPreset.first()
                val custom = dataStoreManager.customAlwaysAllowed.first()
                dataStoreManager.setKidProfiles(
                    listOf(
                        KidProfile("p1", "Kid 1", 0xFF006C7FL, limit, preset, custom),
                        KidProfile("p2", "Kid 2", 0xFFFF9E7AL, limit, preset)
                    )
                )
            }
            dataStoreManager.setMultiKidEnabled(on)
        }
    }

    fun setKidProfileName(id: String, name: String) {
        viewModelScope.launch {
            val updated = dataStoreManager.kidProfiles.first().map { if (it.id == id) it.copy(name = name) else it }
            dataStoreManager.setKidProfiles(updated)
        }
    }

    fun setKidProfileLimit(id: String, minutes: Int) {
        viewModelScope.launch {
            val updated = dataStoreManager.kidProfiles.first().map { if (it.id == id) it.copy(dailyLimitMinutes = minutes) else it }
            dataStoreManager.setKidProfiles(updated)
        }
    }

    fun setKidProfilePreset(id: String, preset: Int) {
        viewModelScope.launch {
            val updated = dataStoreManager.kidProfiles.first().map { if (it.id == id) it.copy(allowedPreset = preset) else it }
            dataStoreManager.setKidProfiles(updated)
        }
    }

    fun toggleKidProfileCustomAllowed(id: String, pkg: String) {
        viewModelScope.launch {
            val updated = dataStoreManager.kidProfiles.first().map {
                if (it.id == id) {
                    val cur = it.customAllowed
                    it.copy(customAllowed = if (pkg in cur) cur - pkg else cur + pkg)
                } else it
            }
            dataStoreManager.setKidProfiles(updated)
        }
    }

    fun setKidProfilePerAppLimit(id: String, pkg: String, minutes: Int) {
        viewModelScope.launch { dataStoreManager.setKidProfilePerAppLimit(id, pkg, minutes) }
    }

    fun saveKidFaceEmbedding(id: String, embedding: FloatArray) {
        viewModelScope.launch { dataStoreManager.setKidFaceEmbedding(id, embedding) }
    }

    // ---- Earn screen-time (Phase 7) ----
    fun addEarnedTask(title: String, minutes: Int) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val task = com.shantanu.shield.data.EarnedTask(
                System.currentTimeMillis().toString(), title.trim(), minutes.coerceIn(1, 240)
            )
            dataStoreManager.setEarnedTasks(dataStoreManager.earnedTasks.first() + task)
        }
    }

    fun removeEarnedTask(id: String) {
        viewModelScope.launch {
            dataStoreManager.setEarnedTasks(dataStoreManager.earnedTasks.first().filterNot { it.id == id })
        }
    }

    // ---- Schedules (Phase 7) ----
    // Save the CURRENT kid-mode config (limit + allowed apps) as a named, re-applyable schedule.
    fun addSchedule(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val s = com.shantanu.shield.data.Schedule(
                id = System.currentTimeMillis().toString(),
                name = name.trim(),
                dailyLimitMinutes = dataStoreManager.dailyLimitMinutes.first(),
                allowedPreset = dataStoreManager.alwaysAllowedPreset.first(),
                customAllowed = dataStoreManager.customAlwaysAllowed.first()
            )
            dataStoreManager.setSchedules(dataStoreManager.schedules.first() + s)
        }
    }

    // Apply a schedule = write its values into the live kid-mode config.
    fun applySchedule(schedule: com.shantanu.shield.data.Schedule) {
        viewModelScope.launch {
            dataStoreManager.setDailyLimitMinutes(schedule.dailyLimitMinutes)
            dataStoreManager.setAlwaysAllowedPreset(schedule.allowedPreset)
            dataStoreManager.setCustomAlwaysAllowed(schedule.customAllowed)
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            dataStoreManager.setSchedules(dataStoreManager.schedules.first().filterNot { it.id == id })
        }
    }

    // Free-Play / Temp-Kid-Mode session: hand the phone to the kid for a fixed
    // window during which every app bypasses face-unlock (except the FaceShield
    // app itself). Session is wall-clock; persists across reboots; expires when
    // the timer fires — there is no early-end action.
    fun startKidSession(durationMinutes: Int) {
        viewModelScope.launch {
            val grantedMs = durationMinutes.coerceIn(1, 240) * 60_000L
            val now = System.currentTimeMillis()
            val endAt = now + grantedMs
            dataStoreManager.setKidSessionEndAt(endAt)
            dataStoreManager.appendFreePlayRecord(now, endAt, grantedMs)
        }
    }

    // Parent grants the kid extra screen time for today once the daily budget is hit.
    // Additive only (no "reset to zero") so the day's total grant stays auditable. The
    // enforcement check is `usedMin >= dailyLimit + extensionMin`, so this directly
    // relaxes the lock for `minutes` more. Resets to 0 at the next 07:00 boundary.
    fun grantExtension(minutes: Int) {
        viewModelScope.launch {
            dataStoreManager.addExtensionMinutes(minutes.coerceIn(1, 240))
        }
    }

    fun setFirstRunCompleted(value: Boolean) {
        viewModelScope.launch { dataStoreManager.setFirstRunCompleted(value) }
    }

    fun setCoachMarksEnabled(value: Boolean) {
        viewModelScope.launch { dataStoreManager.setCoachMarksEnabled(value) }
    }

    fun markTourSeen(tourId: String) {
        viewModelScope.launch { dataStoreManager.markTourSeen(tourId) }
    }

    fun replayTour(tourId: String) {
        viewModelScope.launch { dataStoreManager.resetSeenTours() }
    }
}

data class AppUsageInfo(val name: String, val packageName: String, val icon: Drawable, val usageTimeMs: Long)
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val category: AppCategory = AppCategory.OTHER
)
data class AppGroup(
    val category: AppCategory,
    val apps: List<AppInfo>,
    val protectedCount: Int,
    val totalCount: Int
)
