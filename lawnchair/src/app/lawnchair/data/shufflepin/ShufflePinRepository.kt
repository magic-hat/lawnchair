package app.lawnchair.data.shufflepin

import android.content.Context
import app.lawnchair.data.AppDatabase
import app.lawnchair.util.MainThreadInitializedObject
import com.android.launcher3.util.ComponentKey
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
        // Update the in-memory set immediately so callers reading right after a
        // toggle (e.g. the icon redraw) don't race the Room flow emission.
        _pinnedSet = if (pinned) _pinnedSet + target else _pinnedSet - target
        if (pinned) dao.insert(ShufflePin(target)) else dao.delete(target)
    }

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject(::ShufflePinRepository)
    }
}
