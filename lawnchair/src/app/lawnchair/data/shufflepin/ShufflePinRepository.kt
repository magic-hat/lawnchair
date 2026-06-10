/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
