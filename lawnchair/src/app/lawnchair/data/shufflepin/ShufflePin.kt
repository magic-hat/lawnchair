package app.lawnchair.data.shufflepin

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.android.launcher3.util.ComponentKey

@Entity
data class ShufflePin(
    @PrimaryKey val target: ComponentKey,
)
