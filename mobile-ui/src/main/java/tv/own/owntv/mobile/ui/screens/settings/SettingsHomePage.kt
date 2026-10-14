package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.MobileSwitch
import tv.own.owntv.mobile.ui.components.SettingRow

/**
 * Home, as the profile wants it: which rows appear, in what order, and what the Keep watching row is
 * allowed to draw from.
 *
 * ZenPlay's Trending uses newly added 2026 movies from the provider, independently of TMDB matching.
 */
@Composable
fun SettingsHomePage(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val profileId = vm.settings.activeProfileId.pref(-1L)
    val home = remember(profileId) {
        if (profileId < 0) flowOf(HomeConfig()) else vm.settings.homeConfig(profileId)
    }.pref(HomeConfig())

    fun editHome(transform: (HomeConfig) -> HomeConfig) {
        if (profileId < 0) return
        vm.edit { updateHomeConfig(profileId, transform) }
    }

    SettingsPage(modifier) {
        settingsSection(R.string.zenplay_home_trending)
        settingsNote(R.string.zenplay_home_fixed_sections_mobile)
        settingsGroup(key = "trending") {
            SettingRow(
                title = stringResource(R.string.zenplay_home_trending),
                subtitle = stringResource(R.string.zenplay_home_trending_catalog_description),
                enabled = false,
            )
        }

        settingsSection(R.string.settings_sections)
        settingsNote(R.string.settings_hidden_sections)
        settingsGroup(key = "rows") {
            home.settingsRows.forEachIndexed { index, row ->
                val order = home.settingsRows
                ReorderableSwitchRow(
                    title = stringResource(row.titleRes()),
                    subtitle = if (row in home.hidden) stringResource(R.string.settings_hidden)
                    else stringResource(row.descriptionRes()),
                    checked = row !in home.hidden,
                    onCheckedChange = { show ->
                        editHome { it.copy(hidden = if (show) it.hidden - row else it.hidden + row) }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onMove = { delta ->
                        val moved = order.toMutableList().apply { add(index + delta, removeAt(index)) }
                        editHome { it.copy(order = listOf(HomeRow.TRENDING) + moved) }
                    },
                )
                // Only the two channel rows have a second shape to draw in, so only they carry the
                // choice — the same rule, and the same stored value, the television uses.
                row.liveMode(home)?.let { mode ->
                    SettingRow(
                        title = stringResource(R.string.settings_mode, stringResource(mode.labelRes())),
                        onClick = {
                            editHome {
                                when (row) {
                                    HomeRow.RECENT_CHANNELS -> it.copy(recentLiveMode = mode.toggled())
                                    else -> it.copy(favoriteLiveMode = mode.toggled())
                                }
                            }
                        },
                    )
                }
            }
        }

        settingsSection(R.string.settings_keep_watching) {
            SettingRow(
                title = stringResource(R.string.settings_hero_preview),
                subtitle = stringResource(R.string.settings_hero_preview_description),
                checked = vm.settings.heroPreviewEnabled.pref(vm.settings.heroPreviewDefault),
                onCheckedChange = { on -> vm.edit { setHeroPreviewEnabled(on) } },
            )
            SettingRow(
                title = stringResource(R.string.settings_live_keep_watching),
                subtitle = stringResource(R.string.settings_live_keep_watching_description),
                checked = home.heroIncludeLive,
                onCheckedChange = { on -> editHome { it.copy(heroIncludeLive = on) } },
            )
            SettingRow(
                title = stringResource(R.string.settings_movies_keep_watching),
                subtitle = stringResource(R.string.settings_movies_keep_watching_description),
                checked = home.heroIncludeMovies,
                onCheckedChange = { on -> editHome { it.copy(heroIncludeMovies = on) } },
            )
            SettingRow(
                title = stringResource(R.string.settings_series_keep_watching),
                subtitle = stringResource(R.string.settings_series_keep_watching_description),
                checked = home.heroIncludeSeries,
                onCheckedChange = { on -> editHome { it.copy(heroIncludeSeries = on) } },
            )
        }
    }
}

/**
 * A row that can be both switched off and moved. The TV app picks a row up with OK and walks it with
 * the D-pad; a thumb gets two arrows instead, because there is nothing to hold on a touch screen.
 */
@Composable
private fun ReorderableSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    MobileListRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Row {
                MoveButton(up = true, enabled = canMoveUp) { onMove(-1) }
                MoveButton(up = false, enabled = canMoveDown) { onMove(1) }
                MobileSwitch(checked = checked)
            }
        },
    )
}

private fun HomeRow.titleRes() = when (this) {
    HomeRow.TRENDING -> R.string.home_row_now_trending
    HomeRow.HERO -> R.string.settings_keep_watching
    HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_channels
    HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_channels
    HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies
    HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series
}

private fun HomeRow.descriptionRes() = when (this) {
    HomeRow.TRENDING -> R.string.home_row_trending_description
    HomeRow.HERO -> R.string.home_row_hero_description
    HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_description
    HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_description
    HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies_description
    HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series_description
}

/** Which of the two channel rows this is, and the shape it is currently drawn in. */
private fun HomeRow.liveMode(config: HomeConfig): HomeLiveRowMode? = when (this) {
    HomeRow.RECENT_CHANNELS -> config.recentLiveMode
    HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
    else -> null
}

private fun HomeLiveRowMode.labelRes() = when (this) {
    HomeLiveRowMode.CARDS -> R.string.home_row_cards
    HomeLiveRowMode.ON_NOW -> R.string.home_row_on_now
}
