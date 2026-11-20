package com.example

import android.app.Application
import com.example.blocker.FreedomMonitorService
import com.example.blocker.PermissionStatusChecker
import com.example.blocker.SessionAlarmScheduler
import com.example.data.local.FastBlockStateStore
import com.example.data.local.FreedomDatabase
import com.example.data.repository.FreedomRepository
import com.example.data.repository.InstalledAppsProvider
import com.example.data.sync.FilterListSyncEngine
import com.example.domain.CompactDomainIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FreedomContainer(app: Application) {
    val database: FreedomDatabase = FreedomDatabase.getInstance(app)
    val fastStore: FastBlockStateStore = FastBlockStateStore(app)
    val domainIndex: CompactDomainIndex = CompactDomainIndex()
    val installedAppsProvider: InstalledAppsProvider = InstalledAppsProvider(app)
    val alarmScheduler: SessionAlarmScheduler = SessionAlarmScheduler(app)
    val permissionChecker: PermissionStatusChecker = PermissionStatusChecker(app)
    val filterSyncEngine: FilterListSyncEngine = FilterListSyncEngine(
        context = app,
        dao = database.freedomDao(),
        fastStore = fastStore,
        domainIndex = domainIndex
    )
    val repository: FreedomRepository = FreedomRepository(
        context = app,
        dao = database.freedomDao(),
        fastStore = fastStore,
        installedAppsProvider = installedAppsProvider,
        alarmScheduler = alarmScheduler,
        filterSyncEngine = filterSyncEngine
    )
}

class FreedomApplication : Application() {

    lateinit var container: FreedomContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        FreedomMonitorService.ensureNotificationChannels(this)
        container = FreedomContainer(this)
        appScope.launch {
            container.repository.initialize()
        }
    }
}
