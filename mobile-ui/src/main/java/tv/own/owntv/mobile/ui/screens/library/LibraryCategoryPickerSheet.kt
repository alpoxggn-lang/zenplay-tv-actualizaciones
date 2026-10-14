package tv.own.owntv.mobile.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.components.sheetListHeight
import tv.own.owntv.mobile.ui.theme.MobileDimens

/** Searchable category selection; the same keys continue to drive the library's existing filters. */
@Composable
internal fun LibraryCategoryPickerSheet(
    tab: LibraryTab,
    categories: List<VodCategory>,
    selected: LiveKey,
    onSelect: (VodCategory) -> Unit,
    onCustomize: (VodCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(tab) { mutableStateOf("") }
    val choices = categories.map { it to it.label(tab) }
    val matches = remember(choices, query) {
        choices.filter { (_, label) -> label.contains(query.trim(), ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    // Reopen on the selected category; a search begins at its first result instead.
    LaunchedEffect(query) {
        val index = if (query.isBlank()) matches.indexOfFirst { it.first.key == selected } else 0
        listState.scrollToItem(index.coerceAtLeast(0))
    }

    MobileBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.zenplay_categories_button),
    ) {
        MobileTextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.content_search_categories),
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH),
        )
        if (matches.isEmpty()) {
            Text(
                text = stringResource(R.string.zenplay_categories_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MobileDimens.ScreenPaddingH),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = sheetListHeight())) {
            items(matches, key = { (category, _) -> category.key.serialize() }) { (category, label) ->
                val isSelected = category.key == selected
                val canCustomize = category.builtIn == null
                MobileListRow(
                    title = label,
                    selected = isSelected,
                    onClick = { onSelect(category) },
                    onLongClick = if (canCustomize) ({ onCustomize(category) }) else null,
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSelected) Icon(MobileIcons.Check, contentDescription = null)
                            // Keep hide/reorder available without requiring a long-press gesture.
                            if (canCustomize) {
                                IconButton(onClick = { onCustomize(category) }) {
                                    Icon(
                                        MobileIcons.Tune,
                                        contentDescription = stringResource(R.string.zenplay_categories_options, label),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
