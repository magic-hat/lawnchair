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

package app.lawnchair.shuffle

import android.content.ContentValues
import android.content.Context
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors
import com.android.launcher3.util.GridOccupancy
import com.patrykmichalik.opto.core.firstBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves and restores a single homescreen layout snapshot so users have a way
 * back after a shuffle.
 */
class LayoutSnapshotManager(
    private val context: Context,
    private val model: LauncherModel,
) {

    companion object {
        private const val TAG = "LayoutSnapshotManager"
        private const val PREFS_NAME = "shuffle_layout_snapshot"
        private const val KEY_SNAPSHOT = "snapshot_v1"
        private const val SNAPSHOT_VERSION = 1

        /** Snapshot saved explicitly by the user. */
        const val ORIGIN_MANUAL = "manual"

        /** Snapshot saved automatically right before a shuffle. */
        const val ORIGIN_AUTO = "auto"
    }

    private data class SnapshotItem(
        val id: Int,
        val container: Int,
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
        val itemType: Int,
    )

    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSnapshot(): Boolean = prefs.contains(KEY_SNAPSHOT)

    /**
     * Returns [ORIGIN_MANUAL] or [ORIGIN_AUTO] for the stored snapshot, or null
     * if there is none. Snapshots saved before the origin was recorded are
     * treated as manual so they are never overwritten automatically.
     */
    fun snapshotOrigin(): String? {
        val json = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optString("origin", ORIGIN_MANUAL)
            } else {
                ORIGIN_MANUAL
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading snapshot origin", e)
            ORIGIN_MANUAL
        }
    }

    fun saveSnapshot(origin: String, onSuccess: Runnable, onFailure: Runnable) {
        model.loadAsync { dataModel ->
            if (dataModel == null) {
                Log.w(TAG, "Data model not loaded, cannot save snapshot")
                Executors.MAIN_EXECUTOR.execute(onFailure)
                return@loadAsync
            }
            try {
                val items = collectItems(dataModel)
                prefs.edit().putString(KEY_SNAPSHOT, serialize(items, origin)).apply()
                Log.d(TAG, "Saved $origin snapshot with ${items.size} items")
                Executors.MAIN_EXECUTOR.execute(onSuccess)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving snapshot", e)
                Executors.MAIN_EXECUTOR.execute(onFailure)
            }
        }
    }

    fun restoreSnapshot(onSuccess: Runnable, onMissing: Runnable, onFailure: Runnable) {
        val json = prefs.getString(KEY_SNAPSHOT, null)
        if (json == null) {
            Executors.MAIN_EXECUTOR.execute(onMissing)
            return
        }
        model.loadAsync { dataModel ->
            if (dataModel == null) {
                Log.w(TAG, "Data model not loaded, cannot restore snapshot")
                Executors.MAIN_EXECUTOR.execute(onFailure)
                return@loadAsync
            }
            try {
                val items = deserialize(json)
                val accepted = validatePlacements(items, dataModel)
                if (accepted.isEmpty()) {
                    Log.w(TAG, "No snapshot item fits the current grid, not restoring")
                    Executors.MAIN_EXECUTOR.execute(onFailure)
                    return@loadAsync
                }
                val dbController = model.modelDbController
                for (item in accepted) {
                    val values = ContentValues().apply {
                        put(Favorites.CONTAINER, item.container)
                        put(Favorites.SCREEN, item.screen)
                        put(Favorites.CELLX, item.cellX)
                        put(Favorites.CELLY, item.cellY)
                        put(Favorites.SPANX, item.spanX)
                        put(Favorites.SPANY, item.spanY)
                    }
                    dbController.update(
                        values,
                        "${Favorites._ID} = ?",
                        arrayOf(item.id.toString()),
                    )
                }
                Log.d(TAG, "Restored ${accepted.size} of ${items.size} snapshot items")
                model.forceReload()
                Executors.MAIN_EXECUTOR.execute(onSuccess)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring snapshot", e)
                Executors.MAIN_EXECUTOR.execute(onFailure)
            }
        }
    }

    /**
     * Filters snapshot items down to those whose stored position is still legal.
     * The loader deletes items that fall outside the current grid, overlap the
     * search container, or overlap another item (see
     * [com.android.launcher3.model.LoaderCursor.checkItemPlacement]), so a stale
     * snapshot must never write such positions.
     */
    private fun validatePlacements(
        items: List<SnapshotItem>,
        dataModel: BgDataModel,
    ): List<SnapshotItem> {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val numColumns = idp.numColumns
        val numRows = idp.numRows
        val reservedColumns = ShuffleGrid.reservedColumns(
            idp.numSearchContainerColumns,
            PreferenceManager2.getInstance(context).enableSmartspace.firstBlocking(),
        )

        val currentById = HashMap<Int, ItemInfo>()
        synchronized(dataModel) {
            for (item in dataModel.workspaceItemInfos()) {
                if (item.container == Favorites.CONTAINER_DESKTOP) currentById[item.id] = item
            }
            for (widget in dataModel.widgetInfos()) {
                if (widget.container == Favorites.CONTAINER_DESKTOP) currentById[widget.id] = widget
            }
        }

        // Restore structural anchors first so an overlap drops an icon rather
        // than the widget or folder it collides with.
        val candidates = items
            .filter { it.container == Favorites.CONTAINER_DESKTOP && it.id in currentById }
            .sortedBy { structuralWeight(it.itemType) }
        val candidateIds = candidates.mapTo(HashSet()) { it.id }

        // Desktop items missing from the snapshot keep their position and act as
        // fixed obstacles. A snapshot item that has to be skipped also stays
        // where it currently is, which can invalidate an earlier acceptance, so
        // repeat until the accepted set is stable.
        val skippedIds = mutableSetOf<Int>()
        while (true) {
            val occupancyMap = HashMap<Int, GridOccupancy>()
            fun gridFor(screenId: Int) = occupancyMap.getOrPut(screenId) {
                GridOccupancy(numColumns, numRows).also {
                    ShuffleGrid.reserve(it, screenId, reservedColumns)
                }
            }
            for (current in currentById.values) {
                if (current.id !in candidateIds || current.id in skippedIds) {
                    gridFor(current.screenId).markCells(
                        current.cellX,
                        current.cellY,
                        current.spanX,
                        current.spanY,
                        true,
                    )
                }
            }
            val accepted = mutableListOf<SnapshotItem>()
            val newlySkipped = mutableListOf<Int>()
            for (item in candidates) {
                if (item.id in skippedIds) continue
                val grid = gridFor(item.screen)
                if (grid.isRegionVacant(item.cellX, item.cellY, item.spanX, item.spanY)) {
                    grid.markCells(item.cellX, item.cellY, item.spanX, item.spanY, true)
                    accepted.add(item)
                } else {
                    newlySkipped.add(item.id)
                }
            }
            if (newlySkipped.isEmpty()) {
                if (skippedIds.isNotEmpty()) {
                    Log.w(TAG, "Skipped ${skippedIds.size} snapshot items that no longer fit")
                }
                return accepted
            }
            skippedIds.addAll(newlySkipped)
        }
    }

    private fun structuralWeight(itemType: Int): Int = when (itemType) {
        Favorites.ITEM_TYPE_APPWIDGET, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> 0
        Favorites.ITEM_TYPE_FOLDER, Favorites.ITEM_TYPE_APP_PAIR -> 1
        else -> 2
    }

    private fun collectItems(dataModel: BgDataModel): List<SnapshotItem> {
        val items = mutableListOf<SnapshotItem>()
        synchronized(dataModel) {
            for (item in dataModel.workspaceItemInfos()) {
                if (item.container == Favorites.CONTAINER_DESKTOP) {
                    items.add(item.toSnapshotItem())
                }
            }
            for (widget in dataModel.widgetInfos()) {
                if (widget.container == Favorites.CONTAINER_DESKTOP) {
                    items.add(widget.toSnapshotItem())
                }
            }
        }
        return items
    }

    private fun ItemInfo.toSnapshotItem() = SnapshotItem(
        id = id,
        container = container,
        screen = screenId,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        itemType = itemType,
    )

    private fun serialize(items: List<SnapshotItem>, origin: String): String {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("container", item.container)
                    put("screen", item.screen)
                    put("cellX", item.cellX)
                    put("cellY", item.cellY)
                    put("spanX", item.spanX)
                    put("spanY", item.spanY)
                    put("type", item.itemType)
                },
            )
        }
        return JSONObject().apply {
            put("version", SNAPSHOT_VERSION)
            put("origin", origin)
            put("numColumns", idp.numColumns)
            put("numRows", idp.numRows)
            put("items", arr)
        }.toString()
    }

    private fun deserialize(json: String): List<SnapshotItem> {
        val trimmed = json.trim()
        // Snapshots saved before versioning were a bare array of items.
        val arr = if (trimmed.startsWith("{")) {
            JSONObject(trimmed).getJSONArray("items")
        } else {
            JSONArray(trimmed)
        }
        val items = mutableListOf<SnapshotItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(
                SnapshotItem(
                    id = obj.getInt("id"),
                    container = obj.getInt("container"),
                    screen = obj.getInt("screen"),
                    cellX = obj.getInt("cellX"),
                    cellY = obj.getInt("cellY"),
                    spanX = obj.getInt("spanX"),
                    spanY = obj.getInt("spanY"),
                    itemType = obj.optInt("type", Favorites.ITEM_TYPE_APPLICATION),
                ),
            )
        }
        return items
    }
}
