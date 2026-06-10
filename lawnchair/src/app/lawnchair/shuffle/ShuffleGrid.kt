package app.lawnchair.shuffle

import com.android.launcher3.util.GridOccupancy

/**
 * Placement rules shared by [ShuffleManager] and [LayoutSnapshotManager].
 *
 * Mirrors the constraints enforced by Launcher3's loader
 * ([com.android.launcher3.model.LoaderCursor.checkItemPlacement]): items out of
 * grid bounds or overlapping the search container region are deleted on the
 * next load, so positions violating these rules must never be written to the
 * workspace database.
 */
object ShuffleGrid {

    /** The workspace screen that hosts the search container. */
    const val FIRST_SCREEN_ID = 0

    /**
     * Number of leading columns in the first row of [FIRST_SCREEN_ID] that the
     * loader treats as occupied by the search container.
     */
    fun reservedColumns(numSearchColumns: Int, qsbEnabled: Boolean): Int = if (qsbEnabled) numSearchColumns else 0

    /**
     * Marks the search container region as occupied, matching the loader's
     * reservation exactly.
     */
    fun reserve(occupancy: GridOccupancy, screenId: Int, reservedColumns: Int) {
        if (screenId == FIRST_SCREEN_ID && reservedColumns > 0) {
            occupancy.markCells(0, 0, reservedColumns, 1, true)
        }
    }
}
