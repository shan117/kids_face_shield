package com.shantanu.shield

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
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

    // Free-Play / Temp-Kid-Mode session: hand the phone to the kid for a fixed
    // window during which every app bypasses face-unlock (except the FaceShield
    // app itself). Session is wall-clock; persists across reboots; expires when
    // the timer fires — there is no early-end action.
    fun startKidSession(durationMinutes: Int) {
        viewModelScope.launch {
            val endAt = System.currentTimeMillis() + durationMinutes.coerceIn(1, 240) * 60_000L
            dataStoreManager.setKidSessionEndAt(endAt)
        }
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
