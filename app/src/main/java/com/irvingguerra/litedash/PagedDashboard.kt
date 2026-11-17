package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.SizeLimits
import com.irvingguerra.litedash.feature.dashboard.Tile
import kotlinx.coroutines.launch

/**
 * El inicio en páginas, como el de Google Home: lo que no cabe en una página pasa a la siguiente y se pasa de
 * una a otra deslizando (de derecha a izquierda) o tocando los puntos de abajo. Cada página es un tablero normal.
 * Acomodando, el deslizamiento se apaga (el dedo arrastra piezas); los puntos siguen cambiando de página.
 */
@Composable
fun PagedDashboard(
    state: DashboardState,
    grid: Grid,
    editing: Boolean,
    limits: (String) -> SizeLimits,
    onEdit: () -> Unit,
    onSettings: (Tile) -> Unit,
    hasSettings: (Tile) -> Boolean,
    content: @Composable (Tile) -> Unit,
) {
    val c = LocalColors.current
    val all = state.shown
    val pages = (all.maxOfOrNull { it.page } ?: 0) + 1
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    // Si se quitó la última página con la que estabas, vuelve a la que sigue siendo la última.
    LaunchedEffect(pages) { if (pager.currentPage >= pages) pager.scrollToPage(pages - 1) }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !editing && pages > 1, beyondViewportPageCount = 0, key = { it }) { page ->
            val mine = remember(all, page) { all.filter { it.page == page } }
            Dashboard(
                grid = grid,
                tiles = mine,
                editing = editing,
                limits = limits,
                onChange = { new -> state.changeShown(all.filterNot { it.page == page } + new) },
                onEdit = onEdit,
                onSettings = onSettings,
                hasSettings = hasSettings,
                content = content,
            )
        }
        if (pages > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (i in 0 until pages) {
                val here = pager.currentPage == i
                Box(
                    Modifier
                        .size(if (here) 12.dp else 9.dp)
                        .clip(CircleShape)
                        .background(if (here) c.accent else c.textMuted.copy(alpha = 0.6f))
                        .clickable { scope.launch { pager.animateScrollToPage(i) } },
                )
            }
        }
    }
}
