package com.irvingguerra.litedash.feature.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Las emisoras recientes, con un contador que hace repintar la lista cuando suena otra. */
object RadioRecents {
    var version by mutableIntStateOf(0)
        private set

    fun list(context: Context): List<Station> {
        version // leerlo hace que quien pinte se entere de los cambios
        return PlayerStore(context).recents()
    }

    fun refresh() { version++ }
}
