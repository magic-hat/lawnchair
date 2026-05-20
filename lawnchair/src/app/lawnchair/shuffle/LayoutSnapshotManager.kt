package app.lawnchair.shuffle

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors
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
    }

    private data class SnapshotItem(
        val id: Int,
        val container: Int,
        val screen: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
    )

    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSnapshot(): Boolean = prefs.contains(KEY_SNAPSHOT)

    fun saveSnapshot(onSuccess: Runnable, onFailure: Runnable) {
        model.loadAsync { dataModel ->
            if (dataModel == null) {
                Log.w(TAG, "Data model not loaded, cannot save snapshot")
                Executors.MAIN_EXECUTOR.execute(onFailure)
                return@loadAsync
            }
            try {
                val items = collectItems(dataModel)
                prefs.edit().putString(KEY_SNAPSHOT, serialize(items)).apply()
                Log.d(TAG, "Saved snapshot with ${items.size} items")
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
        Executors.MODEL_EXECUTOR.execute {
            try {
                val items = deserialize(json)
                val dbController = model.modelDbController
                for (item in items) {
                    val values = ContentValues().apply {
                        put(Favorites.CONTAINER, item.container)
                        put(Favorites.SCREEN, item.screen)
                        put(Favorites.CELLX, item.cellX)
                        put(Favorites.CELLY, item.cellY)
                        put(Favorites.SPANX, item.spanX)
                        put(Favorites.SPANY, item.spanY)
                    }
                    dbController.update(
                        Favorites.TABLE_NAME,
                        values,
                        "${Favorites._ID} = ?",
                        arrayOf(item.id.toString()),
                    )
                }
                Log.d(TAG, "Restored snapshot with ${items.size} items")
                model.forceReload()
                Executors.MAIN_EXECUTOR.execute(onSuccess)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring snapshot", e)
                Executors.MAIN_EXECUTOR.execute(onFailure)
            }
        }
    }

    private fun collectItems(dataModel: BgDataModel): List<SnapshotItem> {
        val items = mutableListOf<SnapshotItem>()
        synchronized(dataModel) {
            for (item in dataModel.workspaceItems) {
                if (item.container == Favorites.CONTAINER_DESKTOP) {
                    items.add(item.toSnapshotItem())
                }
            }
            for (widget in dataModel.appWidgets) {
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
    )

    private fun serialize(items: List<SnapshotItem>): String {
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
                },
            )
        }
        return arr.toString()
    }

    private fun deserialize(json: String): List<SnapshotItem> {
        val arr = JSONArray(json)
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
                ),
            )
        }
        return items
    }
}
