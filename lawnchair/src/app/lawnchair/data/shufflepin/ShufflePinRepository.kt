package app.lawnchair.data.shufflepin

import android.content.Context
import app.lawnchair.data.AppDatabase
import com.android.launcher3.LauncherAppState
import com.android.launcher3.pm.PackageInstallInfo
import com.android.launcher3.pm.PackageInstallInfo.STATUS_INSTALLED
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.MainThreadInitializedObject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ShufflePinRepository(private val context: Context) {

    private val scope = MainScope() + CoroutineName("ShufflePinRepository")
    private val dao = AppDatabase.INSTANCE.get(context).shufflePinDao()

    @Volatile
    private var _pinnedSet: Set<ComponentKey> = emptySet()
    val pinnedSet: Set<ComponentKey> get() = _pinnedSet

    init {
        scope.launch {
            dao.observeAll()
                .flowOn(Dispatchers.Main)
                .collect { rows ->
                    _pinnedSet = rows.map { it.target }.toSet()
                }
        }
    }

    fun isPinned(target: ComponentKey): Boolean = target in _pinnedSet

    suspend fun setPinned(target: ComponentKey, pinned: Boolean) {
        if (pinned) dao.insert(ShufflePin(target)) else dao.delete(target)
        LauncherAppState.getInstance(context).model.onPackageStateChanged(
            PackageInstallInfo.fromState(
                STATUS_INSTALLED,
                target.componentName.packageName,
                target.user,
            ),
        )
    }

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject(::ShufflePinRepository)
    }
}
