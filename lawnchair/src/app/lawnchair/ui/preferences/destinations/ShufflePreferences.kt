package app.lawnchair.ui.preferences.destinations

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.shuffle.ShuffleManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R

/**
 * Settings screen for the home screen shuffle feature.
 *
 * Shuffle, save, and restore also live on the home screen long-press menu;
 * this screen offers the shuffle action plus its configuration.
 */
@Composable
fun ShufflePreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager2()
    val context = LocalContext.current
    PreferenceLayout(
        label = stringResource(id = R.string.shuffle_settings_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(
            description = stringResource(id = R.string.shuffle_about_description),
        ) {
            ClickablePreference(
                label = stringResource(id = R.string.shuffle_action),
                confirmationText = stringResource(id = R.string.shuffle_description),
                onClick = {
                    val model = LauncherAppState.getInstance(context).model
                    ShuffleManager(context, model).shuffleNow(
                        Runnable {
                            Toast.makeText(context, R.string.shuffle_complete, Toast.LENGTH_SHORT).show()
                        },
                        Runnable {
                            Toast.makeText(context, R.string.shuffle_no_items, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            )
        }
        PreferenceGroup(
            heading = stringResource(id = R.string.shuffle_behavior_label),
        ) {
            SwitchPreference(
                adapter = prefs.autoSaveLayoutBeforeShuffle.getAdapter(),
                label = stringResource(id = R.string.shuffle_auto_save_label),
                description = stringResource(id = R.string.shuffle_auto_save_description),
            )
        }
    }
}
