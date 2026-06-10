package app.lawnchair.shuffle

import android.content.ContentValues
import android.content.Context
import android.util.Log
import app.lawnchair.data.shufflepin.ShufflePinRepository
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors
import com.android.launcher3.util.GridOccupancy
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Manages the shuffle operation for home screen icons.
 *
 * Collects all non-pinned, non-widget app icons on the workspace,
 * gathers available grid positions (excluding pinned items and widgets),
 * performs a Fisher-Yates shuffle on positions, and writes the new
 * positions to the database.
 */
class ShuffleManager(
    private val context: Context,
    private val model: LauncherModel,
) {

    companion object {
        private const val TAG = "ShuffleManager"
    }

    /**
     * Data class representing a grid position on a specific home screen page.
     */
    data class GridPosition(
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
    )

    fun shuffleNow(onSuccess: Runnable, onEmpty: Runnable) {
        val autoSave = PreferenceManager2.getInstance(context)
            .autoSaveLayoutBeforeShuffle.firstBlocking()
        val snapshotManager = LayoutSnapshotManager(context, model)

        // Keep a way back to the layout as it was before this shuffle. Auto-save
        // refreshes snapshots it created itself but never overwrites one the user
        // saved manually. If the save fails we still shuffle, so the action never
        // silently breaks.
        val origin = snapshotManager.snapshotOrigin()
        if (autoSave && origin != LayoutSnapshotManager.ORIGIN_MANUAL) {
            snapshotManager.saveSnapshot(
                origin = LayoutSnapshotManager.ORIGIN_AUTO,
                onSuccess = Runnable { performShuffleAsync(onSuccess, onEmpty) },
                onFailure = Runnable { performShuffleAsync(onSuccess, onEmpty) },
            )
        } else {
            performShuffleAsync(onSuccess, onEmpty)
        }
    }

    private fun performShuffleAsync(onSuccess: Runnable, onEmpty: Runnable) {
        model.loadAsync { dataModel ->
            if (dataModel == null) {
                Log.w(TAG, "Data model not loaded, cannot shuffle")
                Executors.MAIN_EXECUTOR.execute(onEmpty)
                return@loadAsync
            }
            val count = try {
                performShuffle(dataModel)
            } catch (e: Exception) {
                Log.e(TAG, "Error performing shuffle", e)
                0
            }
            Executors.MAIN_EXECUTOR.execute(if (count > 0) onSuccess else onEmpty)
        }
    }

    private fun performShuffle(dataModel: BgDataModel): Int {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val numColumns = idp.numColumns
        val numRows = idp.numRows

        // Collect all workspace items (apps on the desktop, not in hotseat)
        val allWorkspaceItems = synchronized(dataModel) {
            ArrayList(dataModel.workspaceItems)
        }

        // Collect all widgets
        val allWidgets = synchronized(dataModel) {
            ArrayList(dataModel.appWidgets)
        }

        val pinnedKeys = ShufflePinRepository.INSTANCE.get(context).pinnedSet

        // Separate items into shuffleable and fixed
        val shuffleableItems = mutableListOf<ItemInfo>()
        val fixedItems = mutableListOf<ItemInfo>()

        for (item in allWorkspaceItems) {
            if (item.container != Favorites.CONTAINER_DESKTOP) continue

            val component = item.targetComponent
            when {
                item.itemType == Favorites.ITEM_TYPE_FOLDER -> fixedItems.add(item)

                item.itemType == Favorites.ITEM_TYPE_APP_PAIR -> fixedItems.add(item)

                // Shuffleable items are placed into single free cells, so anything
                // larger than 1x1 must stay put.
                item.spanX != 1 || item.spanY != 1 -> fixedItems.add(item)

                component != null && ComponentKey(component, item.user) in pinnedKeys -> fixedItems.add(item)

                else -> shuffleableItems.add(item)
            }
        }

        // Widgets are always fixed
        for (widget in allWidgets) {
            if (widget.container == Favorites.CONTAINER_DESKTOP) {
                fixedItems.add(widget)
            }
        }

        if (shuffleableItems.isEmpty()) {
            Log.d(TAG, "No items to shuffle")
            return 0
        }

        // Determine which screens exist
        val screenIds = mutableSetOf<Int>()
        for (item in allWorkspaceItems) {
            if (item.container == Favorites.CONTAINER_DESKTOP) {
                screenIds.add(item.screenId)
            }
        }
        for (widget in allWidgets) {
            if (widget.container == Favorites.CONTAINER_DESKTOP) {
                screenIds.add(widget.screenId)
            }
        }

        // Build occupancy grids for each screen, reserving the search container
        // region the loader treats as occupied — placing an icon there would get
        // it deleted on the next load.
        val reservedColumns = ShuffleGrid.reservedColumns(
            idp.numSearchContainerColumns,
            FeatureFlags.topQsbOnFirstScreenEnabled(context),
        )
        val occupancyMap = mutableMapOf<Int, GridOccupancy>()
        for (screenId in screenIds) {
            val occupancy = GridOccupancy(numColumns, numRows)
            ShuffleGrid.reserve(occupancy, screenId, reservedColumns)
            occupancyMap[screenId] = occupancy
        }

        // Mark fixed items as occupied
        for (item in fixedItems) {
            occupancyMap[item.screenId]?.markCells(
                item.cellX,
                item.cellY,
                item.spanX,
                item.spanY,
                true,
            )
        }

        // Collect all available positions (cells not occupied by fixed items)
        val availablePositions = mutableListOf<GridPosition>()
        for ((screenId, occupancy) in occupancyMap) {
            for (x in 0 until numColumns) {
                for (y in 0 until numRows) {
                    if (!occupancy.cells[x][y]) {
                        availablePositions.add(GridPosition(screenId, x, y))
                    }
                }
            }
        }

        if (availablePositions.size < shuffleableItems.size) {
            Log.w(
                TAG,
                "Not enough available positions (${availablePositions.size}) " +
                    "for shuffleable items (${shuffleableItems.size})",
            )
            return 0
        }

        // Fisher-Yates shuffle on available positions
        val shuffledPositions = availablePositions.toMutableList()
        for (i in shuffledPositions.size - 1 downTo 1) {
            val j = (0..i).random()
            val temp = shuffledPositions[i]
            shuffledPositions[i] = shuffledPositions[j]
            shuffledPositions[j] = temp
        }

        // Write new positions to the database
        val dbController = model.modelDbController
        for (i in shuffleableItems.indices) {
            val item = shuffleableItems[i]
            val newPos = shuffledPositions[i]

            val values = ContentValues()
            values.put(Favorites.CELLX, newPos.cellX)
            values.put(Favorites.CELLY, newPos.cellY)
            values.put(Favorites.SCREEN, newPos.screenId)

            dbController.update(
                Favorites.TABLE_NAME,
                values,
                "${Favorites._ID} = ?",
                arrayOf(item.id.toString()),
            )
        }

        Log.d(TAG, "Shuffled ${shuffleableItems.size} items across ${screenIds.size} screens")
        model.forceReload()
        return shuffleableItems.size
    }
}
