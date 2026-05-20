package app.lawnchair.data.shufflepin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.android.launcher3.util.ComponentKey
import kotlinx.coroutines.flow.Flow

@Dao
interface ShufflePinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ShufflePin)

    @Query("DELETE FROM shufflepin WHERE target = :target")
    suspend fun delete(target: ComponentKey)

    @Query("SELECT * FROM shufflepin")
    fun observeAll(): Flow<List<ShufflePin>>

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
