package com.irvingguerra.litedash.feature.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Las estaciones favoritas, con un contador que hace repintar las vistas cuando cambian (la lista, la estrella de lo que suena). */
object RadioFavorites {
    var version by mutableIntStateOf(0)
        private set

    fun list(context: Context): List<Station> {
        version // leerlo hace que quien pinte se entere de los cambios
        return PlayerStore(context).favorites()
    }

    fun isFavorite(context: Context, id: String): Boolean = list(context).any { it.id == id }

    /** Avisa que la lista cambió por otro lado (la siembra de la primera vez). */
    fun refresh() { version++ }

    /** Agrega o quita; true = quedó en favoritas. */
    fun toggle(context: Context, station: Station): Boolean {
        val store = PlayerStore(context)
        val current = store.favorites()
        val added = current.none { it.id == station.id }
        store.setFavorites(if (added) current + station else current.filter { it.id != station.id })
        version++
        return added
    }
}
