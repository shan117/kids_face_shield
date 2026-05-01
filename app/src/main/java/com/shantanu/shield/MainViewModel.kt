package com.shantanu.shield

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
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
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val protectedApps = dataStoreManager.protectedApps
    val faceEmbedding = dataStoreManager.faceEmbedding
    val lockMessageType = dataStoreManager.lockMessageType

    val filteredApps: StateFlow<List<AppInfo>> = combine(_installedApps, _searchQuery, protectedApps) { apps, query, protected ->
        val list = if (query.isBlank()) apps else apps.filter { it.name.contains(query, ignoreCase = true) }
        // Sort so that protected apps come first, then sort by name
        list.sortedWith(compareByDescending<AppInfo> { protected.contains(it.packageName) }.thenBy { it.name })
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
                    .map { AppInfo(it.loadLabel(pm).toString(), it.packageName, it.loadIcon(pm)) }
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

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun toggleAppProtection(packageName: String) { viewModelScope.launch { dataStoreManager.toggleProtectedApp(packageName) } }
    fun saveFaceEmbedding(embedding: FloatArray) { viewModelScope.launch { dataStoreManager.saveFaceEmbedding(embedding) } }
}

data class AppUsageInfo(val name: String, val packageName: String, val icon: Drawable, val usageTimeMs: Long)
data class AppInfo(val name: String, val packageName: String, val icon: Drawable)
