package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.cardless
import com.irvingguerra.litedash.feature.music.MusicExpanded
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.music.RadioExpanded
import com.irvingguerra.litedash.feature.music.RadioTile
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.RadioScreen
import com.irvingguerra.litedash.feature.player.PlayerBrowser
import com.irvingguerra.litedash.feature.player.PlayerSource
import com.irvingguerra.litedash.feature.player.RadioFavorites
import com.irvingguerra.litedash.feature.player.Station
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.updater.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** El widget en grande (a media pantalla). */
enum class Expanded { MUSIC, RADIO, MAP }

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Drawer : Panel
    /** La radio por internet a pantalla completa: explorar por país, género o nombre. */
    data object RadioFull : Panel
    data object WeatherFull : Panel
    data object Contacts : Panel
    /** El mapa a pantalla completa con los lugares cercanos; [data] es el aspecto de la pieza que se tocó. */
    data class MapFull(val data: String) : Panel
    data object Settings : Panel
    /** Ajustes abiertos en Sistema → Actualizaciones (desde el aviso de versión nueva). */
    data object Updates : Panel
    data object AddTile : Panel
    data object Templates : Panel
    /** Elegir app para una pieza existente ([tileId]) o para una nueva (null). */
    data class PickApp(val tileId: String?) : Panel
    /** Ajustes abiertos en el Taller (pieza de acceso directo). */
    data object Workshop : Panel
    /** Ajustes abiertos en Apps → Música (para enlazar las fuentes desde la música en grande). */
    data object MusicSettings : Panel
    /** Elegir qué dato muestra un medidor existente ([tileId]) o uno nuevo (null). */
    data class PickGauge(val tileId: String?) : Panel
    /** Estilo, color y demás de un velocímetro o medidor ([tileId]). */
    data class CustomizeGauge(val tileId: String) : Panel
    /** La foto de una pieza de imagen ([tileId]). */
    data class CustomizeImage(val tileId: String) : Panel
    /** Orientación, vista, zoom y colores del widget Mapa ([tileId]). */
    data class CustomizeMap(val tileId: String) : Panel
    /** Ajustes abiertos en Llantas (desde la vista de llantas o la pieza sin configurar). */
    data object TiresSettings : Panel
    /** Elegir un widget de otra app de Android. */
    data object PickWidget : Panel
}

class Dependencies(
    val apps: AppRepository,
    val music: MusicRepository,
    val navigation: NavigationRepository,
    val prefs: Preferences,
    val dashboard: DashboardState,
    val cars: CarRepository,
    val updater: Updater,
    val weather: WeatherRepository,
    val trip: TripRepository,
    val reminders: ReminderRepository,
    val wheel: WheelKeys,
    val stats: Stats,
    /** LiteDash Pro (en prueba): [ProAccess.isPro] lo leen las funciones Pro. */
    val pro: ProAccess,
    /** Señales del auto (OBD, GPS…): el tablero solo lee de aquí. */
    val bus: VehicleBus,
    private val obdLazy: Lazy<ObdRepository>,
    private val obdSaved: () -> Boolean,
    private val tpmsLazy: Lazy<TpmsRepository>,
    private val tpmsSaved: () -> Boolean,
) {
    /** Estado de Compose: las piezas que preguntaron antes de que existiera se vuelven a dibujar. */
    private val obdCreated = mutableStateOf(false)

    /** El escáner OBD: se crea la primera vez que algo lo pide (el Taller o una pieza). */
    val obd: ObdRepository
        get() = obdLazy.value.also { if (!obdCreated.value) obdCreated.value = true }

    /** Ya se usó un adaptador OBD alguna vez (sin crear el escáner para saberlo). */
    val hasObdAdapter get() = obdSaved()

    /**
     * El módulo OBD prendido (de fábrica, si ya hay adaptador). Apagado nada se conecta solo;
     * apagarlo desconecta.
     */
    var obdModule: Boolean
        get() = prefs.obdModule ?: hasObdAdapter
        set(on) {
            prefs.obdModule = on
            if (!on) obdIfCreated()?.disconnect()
        }

    /** Hay adaptador y el módulo está prendido: las piezas pueden conectarse solas. */
    val obdAuto get() = hasObdAdapter && obdModule

    /** El escáner si ya existe, sin crearlo (una pieza solo quiere saber si está conectado). */
    fun obdIfCreated(): ObdRepository? = if (obdCreated.value) obdLazy.value else null

    private val tpmsCreated = mutableStateOf(false)

    init {
        // Las piezas de un módulo apagado no se ven ni apartan su lugar (ni las ponen las plantillas).
        dashboard.hidden = { type -> moduleOff(this, type) != null }
        dashboard.limits = ::limitsOf
    }

    /** Las llantas: se crean la primera vez que algo las pide (sus Ajustes o una pieza con fuente elegida). */
    val tpms: TpmsRepository
        get() = tpmsLazy.value.also { if (!tpmsCreated.value) tpmsCreated.value = true }

    /**
     * Hay una fuente de llantas elegida. Sin crear el repositorio para saberlo; ya creado, se
     * lee su estado (al elegir o quitar la fuente, el inicio se entera solo).
     */
    val hasTpmsSource: Boolean
        get() = if (tpmsCreated.value) tpmsLazy.value.source != null else tpmsSaved()
}

/**
 * Qué hace cada pieza al tocarla (abrir un panel, el mapa, la música o el auto en
 * grande). Se crea una vez: sus funciones leen el estado del inicio al llamarse.
 */
class TileActions(
    val onPanel: (Panel) -> Unit,
    val onNavigate: (App?) -> Unit,
    /** La música, la radio o el mapa en grande ([Expanded]), desde el widget que se tocó (su lado y su estilo). */
    val onExpand: (Expanded, Tile) -> Unit,
    /** Ver en grande el auto, la carretera o la imagen de esta pieza. */
    val onCarOnly: (Tile) -> Unit,
    /** Dónde está cada pieza que crece (por id): de ahí crece al verla sola. */
    val onCarBounds: (String, Rect) -> Unit,
    /** Si la pieza (por id) se esconde porque se ve en grande; se lee al dibujar (no recompone el tablero en cada cuadro de la animación). */
    val carHidden: (String) -> Boolean,
    /** Las llantas a pantalla completa. */
    val onTires: () -> Unit,
    /** Acomodando el inicio (las pastillas de llantas se esconden: estorban a los botones). */
    val editing: () -> Boolean,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun TileContent(d: Dependencies, p: Tile, apps: List<App>, car: CarPackage?, revision: Int, actions: TileActions) {
    val context = LocalContext.current
    when (p.type) {
        TileTypes.CAR, TileTypes.ROAD, TileTypes.IMAGE -> {
            val hero = Modifier
                .fillMaxSize()
                .onGloballyPositioned { actions.onCarBounds(p.id, it.boundsInRoot()) }
                // Mientras crece o se ve sola, la de la pieza se esconde (si no, se ve doble).
                .graphicsLayer { alpha = if (actions.carHidden(p.id)) 0f else 1f }
            val content = @Composable { onTires: (() -> Unit)? ->
                HeroTile(
                    d,
                    p,
                    car,
                    // Una imagen sin foto abre su panel para elegirla; lo demás crece.
                    onClick = {
                        when {
                            p.type == TileTypes.IMAGE && p.data.isEmpty() -> actions.onPanel(Panel.CustomizeImage(p.id))
                            // El Okavango con su clima: un toque abre el pronóstico por hora y por día.
                            car == null && p.type != TileTypes.IMAGE && p.type != TileTypes.ROAD -> actions.onPanel(Panel.WeatherFull)
                            else -> actions.onCarOnly(p)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onTires = onTires,
                )
            }
            // Las llantas junto a cada rueda en la carretera y en el auto 3D (si ya se sabe dónde pisan
            // y así se eligió); si no, en las esquinas. En una pieza chica las cuatro pastillas
            // taparían todo: ahí solo la pieza Llantas o el aviso. Sin llantas no hace falta medir la pieza.
            if (d.hasTpmsSource) BoxWithConstraints(hero) {
                // Dónde pisa cada rueda: un archivo chico aparte del paquete, se pide la primera vez.
                if (p.type == TileTypes.CAR && d.prefs.tiresOnCar == TiresOnCar.WHEELS) LaunchedEffect(car) { d.cars.fetchWheels(car) }
                val pills = maxWidth >= PILLS_MIN_WIDTH && maxHeight >= PILLS_MIN_HEIGHT && !actions.editing()
                val onWheels = p.type == TileTypes.ROAD ||
                    p.type == TileTypes.CAR && d.prefs.tiresOnCar == TiresOnCar.WHEELS && car?.wheels != null
                if (onWheels) content(actions.onTires.takeIf { pills && d.prefs.tiresOnCar != TiresOnCar.OFF }) else {
                    content(null)
                    if (pills) TirePills(d, actions.onTires)
                }
            } else Box(hero) { content(null) }
        }
        // Ya de media pantalla o más: lo de en grande ahí mismo (no se mueve).
        TileTypes.MUSIC -> if (p.fillsHalf(d.dashboard.grid)) MusicBig(d, close = null)
        else MusicTile(d.music, revision, onExpand = remember(p) { { actions.onExpand(Expanded.MUSIC, p) } })
        TileTypes.RADIO -> if (p.fillsHalf(d.dashboard.grid)) RadioBig(d, close = null, onMaximize = { actions.onPanel(Panel.RadioFull) }) { actions.onPanel(Panel.MusicSettings) }
        else RadioTile(d.music, revision, onExpand = remember(p) { { actions.onExpand(Expanded.RADIO, p) } })
        TileTypes.CLOCK -> ClockTile(d.prefs.driverName, d.weather)
        TileTypes.WEATHER -> WeatherTile(d.weather)
        TileTypes.TRIP -> TripTile(d)
        TileTypes.SPEEDOMETER -> SpeedometerTile(d, remember(p.data) { GaugeLook.parse(p.data) })
        TileTypes.GAUGE -> GaugeTile(
            d, signalOf(p.data), remember(p.data) { GaugeLook.parse(p.data) },
            onPick = { actions.onPanel(Panel.PickGauge(p.id)) },
            onWorkshop = { actions.onPanel(Panel.Workshop) },
        )
        TileTypes.WORKSHOP -> HealthTile(d, car) { actions.onPanel(Panel.Workshop) }
        TileTypes.PROJECTION -> ProjectionTile(d, apps) { actions.onPanel(Panel.Drawer) }
        TileTypes.TIRES -> TiresTile(d, onOpen = actions.onTires, onSettings = { actions.onPanel(Panel.TiresSettings) })
        TileTypes.MAP -> {
            val app = mapApp(d, apps)
            // Un toque abre el mapa a pantalla completa con los lugares cercanos.
            val expand = remember(p) { { actions.onPanel(Panel.MapFull(p.data)) } }
            MapTile(
                d, p.data, app, editing = actions.editing(),
                onPlace = remember(app) { { navigateTo(context, app, it) } },
                onNavigate = remember(app) { { actions.onNavigate(app) } },
                onExpand = expand,
            )
        }
        TileTypes.FAVORITES -> FavoritesTile(onOpen = { actions.onPanel(Panel.Contacts) })
        TileTypes.CAR_NAV -> {
            val navApp = mapApp(d, apps)
            CarNavTile(d, onOpen = remember(navApp) { { actions.onNavigate(navApp) } })
        }
        TileTypes.COMPASS -> CompassTile(d)
        TileTypes.WIDGET -> WidgetTile(p.data)
        TileTypes.PHONE -> ShortcutTile(Icons.phone, stringResource(R.string.tile_phone), null) {
            openDialer(context)
        }
        TileTypes.WIFI -> WifiToggleTile()
        TileTypes.BLUETOOTH -> BluetoothToggleTile()
        TileTypes.APP -> {
            val app = d.apps.find(apps, p.data)
            AppTile(d.apps, app) {
                if (app != null) d.apps.open(context, app) else actions.onPanel(Panel.PickApp(p.id))
            }
        }
    }
}

/**
 * El módulo apagado del que depende una pieza (su nombre), o null si se puede ver: el Taller y
 * los medidores son del OBD, la pieza Llantas de Llantas. Esas piezas no se ven ni apartan su
 * lugar ([DashboardState.hidden]) y no se ofrecen para agregar.
 */
fun moduleOff(d: Dependencies, type: String): Int? = when (type) {
    TileTypes.WORKSHOP, TileTypes.GAUGE -> R.string.module_obd.takeIf { !d.obdModule }
    TileTypes.TIRES -> R.string.module_tpms.takeIf { !d.hasTpmsSource }
    else -> null
}

/** La de Ajustes; si no, la primera instalada. */
fun mapApp(d: Dependencies, apps: List<App>): App? =
    d.prefs.mapApp?.let { d.apps.find(apps, it) }
        ?: d.apps.firstOf(apps, *NAVIGATION_PACKAGES)

/** Inicio: barra de estado, tablero personalizable y dock. */
@Composable
fun Home(d: Dependencies, revision: Int, homePressed: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val currentApps by rememberUpdatedState(apps)
    val car = d.cars.pkg(d.prefs.carModel)
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editing by remember { mutableStateOf(false) }
    // La música, la radio o el mapa a media pantalla; null = el tablero normal.
    var expanded by remember { mutableStateOf<Expanded?>(null) }
    // El widget que creció (por id: si cambia el acomodo se lee el de ahora): da el lado y, al mapa, su estilo.
    var expandedFromId by remember { mutableStateOf<String?>(null) }
    // Solo el auto a pantalla completa (un toque al auto entra y otro sale), con animación:
    // [carOnly] es a dónde va y [carShown] si la capa sigue en pantalla (también al regresar).
    var carOnly by remember { mutableStateOf(false) }
    // La pieza que se ve en grande (auto, carretera o imagen) y dónde está cada una.
    var bigTile by remember { mutableStateOf<Tile?>(null) }
    val tileBounds = remember { mutableStateMapOf<String, Rect>() }
    // Las llantas a pantalla completa: [tiresOpen] es a dónde va, [tiresShown] si sigue en pantalla.
    var tiresOpen by remember { mutableStateOf(false) }
    var tiresShown by remember { mutableStateOf(false) }
    var tiresReady by remember { mutableStateOf(false) }
    val tiresProgress = remember { Animatable(0f) }
    LaunchedEffect(tiresOpen) {
        if (tiresOpen) {
            tiresShown = true
            // Aparece cuando el auto de arriba ya está pintado (la primera vez tarda en leerse); si no, igual.
            withTimeoutOrNull(TIRES_WAIT_MS) { snapshotFlow { tiresReady }.first { it } }
            tiresProgress.animateTo(1f, tween(TIRES_FADE_MS, easing = FastOutSlowInEasing))
        } else if (tiresShown) {
            tiresProgress.animateTo(0f, tween(TIRES_FADE_MS, easing = FastOutSlowInEasing))
            tiresShown = false
            tiresReady = false
        }
    }
    fun openTires() {
        tiresOpen = true
    }
    fun backToDashboard() {
        panel = null
        tiresOpen = false
        carOnly = false
        expanded = null
        editing = false
    }

    var carShown by remember { mutableStateOf(false) }
    var carReady by remember { mutableStateOf(false) }
    val carProgress = remember { Animatable(0f) }
    LaunchedEffect(carOnly) {
        if (carOnly) {
            carShown = true
            // Crece cuando la imagen grande ya está pintada (en su pieza se ve igual que la
            // chica); si tarda, crece de todos modos.
            withTimeoutOrNull(600) { snapshotFlow { carReady }.first { it } }
            carProgress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        } else if (carShown) {
            carProgress.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
            carShown = false
            carReady = false
        }
    }

    // Cada toque y cada botón del volante reinician la cuenta del modo estacionado. Se
    // leen dentro del efecto (no al armar la pantalla): tocar no vuelve a armar el inicio.
    var touched by remember { mutableIntStateOf(0) }
    var parked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(touched, d.wheel.presses, panel, editing, expanded, carOnly, tiresOpen, d.prefs.parkedMode) }
            .collectLatest {
                parked = false
                if (!d.prefs.parkedMode || panel != null || editing || expanded != null || carOnly || tiresOpen) return@collectLatest
                delay(PARK_AFTER_MS)
                // Manejando no: espera a que se detenga (velocidad del OBD o del GPS, la que haya).
                snapshotFlow { (d.bus.value(Signal.SPEED) ?: 0f) > MOVING_KMH }.first { !it }
                parked = true
            }
    }
    // Lo que sigue aunque se abra Ajustes o se acomode el inicio: el registro del viaje y el
    // aviso del motor (si no, abrir Ajustes cortaría el viaje y "Entendido" se olvidaría).
    val engineAlert = remember { EngineAlertState() }
    if (d.prefs.welcomeSeen) TripRecorderEffect(d)

    BackHandler(enabled = (panel != null || editing || expanded != null || carOnly || tiresOpen) && d.prefs.welcomeSeen) {
        when {
            panel != null -> panel = null
            tiresOpen -> tiresOpen = false
            carOnly -> carOnly = false
            expanded != null -> expanded = null
            else -> editing = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    LaunchedEffect(homePressed) { if (d.prefs.welcomeSeen) backToDashboard() }
    // Clima al volver al inicio y cada media hora con el inicio a la vista (si está activado); nunca en segundo plano.
    WeatherRefresh(d.weather, revision)
    // Versión nueva de la app (a lo más cada 12 h, al volver al inicio) y modelo 3D nuevo del auto (cada hora).
    val ctxBt = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(d.prefs.keepBluetooth) {
        val stop = if (d.prefs.keepBluetooth) CarBluetoothKeeper.watch(ctxBt) { CarBluetoothKeeper.ensure(ctxBt) } else ({})
        onDispose { stop() }
    }
    LaunchedEffect(revision) { d.updater.checkQuietly() }
    LaunchedEffect(d.prefs.carModel) { d.cars.checkQuietly(d.prefs.carModel) }
    // Cuántas pantallas usan LiteDash: a lo más una vez al día.
    LaunchedEffect(revision) { d.stats.pingDaily() }
    // Pro: lee lo guardado y revisa el código a lo más cada 12 h (sin código guardado, nada de red).
    LaunchedEffect(revision) { d.pro.refresh() }

    val actions = remember {
        TileActions(
            onPanel = { panel = it },
            onNavigate = { app -> openNavigation(context, d.apps, app) },
            onExpand = { kind, tile ->
                expandedFromId = tile.id
                expanded = kind
            },
            onCarOnly = {
                // Con Llantas activo, tocar el auto 3D abre las llantas; si no, lo agranda.
                if (it.type == TileTypes.CAR && d.hasTpmsSource && d.prefs.carTap == CarTap.TIRES) openTires()
                else {
                    bigTile = it
                    carOnly = true
                }
            },
            onCarBounds = { id, rect -> if (tileBounds[id] != rect) tileBounds[id] = rect },
            carHidden = { id -> carShown && bigTile?.id == id && carProgress.value > 0f },
            onTires = { openTires() },
            editing = { editing },
        )
    }

    // Mandos al volante: las acciones que abren algo (mapa, música, voz, inicio) se hacen
    // aquí, una sola vez cada una.
    LaunchedEffect(Unit) {
        snapshotFlow { d.wheel.request }.filterNotNull().collect { action ->
            d.wheel.consume()
            when (action) {
                WheelAction.NAVIGATE -> actions.onNavigate(mapApp(d, currentApps))
                WheelAction.MUSIC -> {
                    backToDashboard()
                    // Del lado de su widget; si ya es de media pantalla, se queda como está.
                    val tile = d.dashboard.shown.firstOrNull { it.type == TileTypes.MUSIC }
                    if (tile?.fillsHalf(d.dashboard.grid) != true) {
                        expandedFromId = tile?.id
                        expanded = Expanded.MUSIC
                    }
                }
                WheelAction.VOICE -> voiceIntent(context)?.let { runCatching { context.startActivity(it) } }
                WheelAction.HOME -> backToDashboard()
                else -> Unit
            }
        }
    }

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val hasFloor = car?.hasFloor == true && d.dashboard.tiles.any { it.type == TileTypes.CAR }
    // Las fotos de piezas de imagen que se quitaron (o cambiaron) salen de memoria.
    LaunchedEffect(d.dashboard.tiles) { releaseTileImages(d.dashboard.tiles) }
    // Velocímetros guardados hasta la 1.4.61 (su máximo en la unidad que se veía): a km/h.
    LaunchedEffect(d.dashboard.tiles) {
        val miles = d.prefs.units.miles
        d.dashboard.changeData(
            d.dashboard.tiles.filter { it.type == TileTypes.SPEEDOMETER }
                .mapNotNull { t -> migrateSpeedMax(t.data, miles)?.let { t.id to it } }
                .toMap(),
        )
    }
    val backdrop = if (hasFloor) {
        Brush.verticalGradient(0.3f to c.background, 1f to lerp(c.background, Color.Black, if (c.dark) 0.35f else 0.08f))
    } else SolidColor(c.background)
    var promptsHeight by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            // Cualquier toque reinicia la cuenta del modo estacionado (sin quitarle el toque a nadie).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched++
                }
            },
    ) {
        // Tu imagen de fondo (Inicio y apariencia → Inicio → Fondo de pantalla), detrás de todo.
        if (panel == null) HomeWallpaper(d.prefs)
        // Los paneles tapan todo el inicio: mientras hay uno abierto no se dibuja ni se
        // pinta nada debajo (el auto del tablero no se tiñe dos veces al cambiar el color).
        if (panel == null) BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
          // Pantalla acostada: barra lateral a la izquierda (como DuduAuto), con la hora y tus
          // apps; el tablero se queda con toda la altura. Parada: el dock abajo, como siempre.
          val rail = when (d.prefs.barPosition) {
              BarPosition.SIDE -> true
              BarPosition.BOTTOM -> false
              BarPosition.AUTO -> maxWidth > maxHeight && !isShortScreen()
          }
          val favorites = remember(apps, d.prefs.dock, d.prefs.musicApp) { dockFavorites(d, apps) }
          Row(Modifier.fillMaxSize()) {
            val railContext = LocalContext.current
            if (rail) Rail(
                active = expanded,
                onHome = { backToDashboard() },
                onProjection = {
                    val projection = projectionApp(apps)
                    if (projection != null) d.apps.open(railContext, projection) else panel = Panel.Drawer
                },
                onMusic = { expanded = Expanded.MUSIC },
                onRadio = {
                    // Con la radio por internet encendida, el botón abre esa (no la FM de la pantalla).
                    if (d.prefs.internetRadio) { d.music.radioInternet = true; expanded = Expanded.RADIO }
                    else if (d.music.sourceApps[SourceKind.RADIO] != null) d.music.openRadioApp() else expanded = Expanded.RADIO
                },
                onPhone = { openDialer(railContext) },
                onApps = { panel = Panel.Drawer },
                onSettings = { panel = Panel.Settings },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Si ya hay un reloj en el tablero (o en la barra lateral), la barra no repite la hora.
                val showTime = true
                val barHidden = !editing && !showTime
                TopBar(
                    editing = editing,
                    showTime = showTime,
                    driverName = d.prefs.driverName,
                    weather = d.weather,
                    onAdd = { panel = Panel.AddTile },
                    onTemplates = { panel = Panel.Templates },
                    onFinish = { editing = false },
                )
                // Los avisos tampoco cuentan para la cuadrícula: aparecen y se van, y no deben
                // hacer que se cargue otro acomodo.
                Column(Modifier.onSizeChanged { promptsHeight = it.height }) {
                    if (d.prefs.welcomeSeen && !editing) {
                        if (!d.prefs.dontAskHome && !isShortScreen()) HomePrompt(d.prefs, revision)
                        EngineAlertBanner(d, engineAlert)
                        TiresAlertBanner(d, actions.onTires)
                        if (!isShortScreen()) ReminderBanner(d.reminders)
                        UpdateBanner(d.updater, onOpen = { openUpdates(); panel = Panel.Updates })
                        val newModel = d.cars.outdated?.takeIf { it.id == car?.id }
                        if (car != null && newModel != null) CarUpdateNotice(d.cars, car, newModel)
                    }
                }
                // Con la barra al costado no hay dock abajo: el tablero lleva su propio aire inferior
                // (en pantallas sin barra de gestos las piezas quedaban pegadas al borde).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (rail) 10.dp else 0.dp)) {
                    // La cuadrícula se calcula siempre como si la barra estuviera: si dependiera
                    // de ella, un reloj en el acomodo la oculta, la cuadrícula crece, se carga
                    // otro acomodo sin reloj, vuelve la barra... y el inicio parpadea sin parar.
                    val prompts = with(LocalDensity.current) { promptsHeight.toDp() }
                    // El aire inferior de la barra lateral tampoco cuenta: la cuadrícula (y el acomodo
                    // guardado) debe ser la misma con o sin él.
                    // Con la barra de Android oculta hay más alto, pero la cuadrícula tampoco cambia.
                    val statusHidden = statusBarHiddenHeight()
                    val gridHeight = (if (barHidden) maxHeight - (topBarHeight() - TOP_BAR_HIDDEN) else maxHeight) + prompts + (if (rail) 10.dp else 0.dp) - statusHidden
                    val grid = remember(maxWidth, gridHeight) { Grid.forSize(maxWidth.value, gridHeight.value) }
                    // Carga el acomodo de esta cuadrícula antes de dibujar (solo hace algo si cambió).
                    d.dashboard.use(grid)
                    // La música, la radio o el mapa en grande ocupan media pantalla; en la otra mitad, el auto
                    // como en la pantalla dividida (auto 3D o carretera, según se eligió ahí), o tu imagen.
                    val big = expanded
                    val from = expandedFromId?.let { id -> d.dashboard.shown.firstOrNull { it.id == id } }
                    // Si el widget ya no está (otra plantilla, otro acomodo) o ya es de media pantalla, se cierra.
                    if (big != null && expandedFromId != null && (from == null || from.fillsHalf(grid))) LaunchedEffect(Unit) { expanded = null }
                    // Con algo a pantalla completa encima, lo que gasta en los widgets (GPS, mapa) descansa.
                    CompositionLocalProvider(LocalHomeCovered provides (carShown || tiresShown || parked)) {
                        if (big != null) HalfScreen(
                            side = {
                                val shown = d.dashboard.shown
                                val hero = shown.firstOrNull { it.type == (if (d.prefs.splitRoad) TileTypes.ROAD else TileTypes.CAR) } ?: shown.firstOrNull { it.cardless }
                                if (hero != null) TileContent(d, hero, apps, car, revision, actions) else ClockTile(d.prefs.driverName, d.weather)
                            },
                            // Crece en la mitad donde estaba: derecha (o abajo) si estaba toda ahí; si no, izquierda (o arriba).
                            sideFirst = from?.let { if (grid.vertical) it.row * 2 >= grid.rows else it.col * 2 >= grid.columns } ?: false,
                            vertical = grid.vertical,
                        ) {
                            val close = { expanded = null }
                            when (big) {
                                Expanded.MUSIC -> MusicBig(d, close, Modifier.padding(6.dp))
                                Expanded.RADIO -> RadioBig(d, close, Modifier.padding(6.dp), onMaximize = { panel = Panel.RadioFull }) { panel = Panel.MusicSettings }
                                Expanded.MAP -> {
                                    val app = mapApp(d, apps)
                                    MapExpanded(d, from?.data ?: "", app, onPlace = { navigateTo(context, app, it) }, onNavigate = { actions.onNavigate(app) }, close = close, Modifier.padding(6.dp))
                                }
                            }
                        } else PagedDashboard(
                                state = d.dashboard,
                                grid = grid,
                                editing = editing,
                                limits = ::limitsOf,
                                onEdit = { editing = true },
                                onSettings = {
                                    panel = when (it.type) {
                                        TileTypes.IMAGE -> Panel.CustomizeImage(it.id)
                                        TileTypes.MAP -> Panel.CustomizeMap(it.id)
                                        else -> Panel.CustomizeGauge(it.id)
                                    }
                                },
                                hasSettings = { it.type in CUSTOMIZABLE },
                            ) { p -> TileContent(d, p, apps, car, revision, actions) }
                    }
                }
                if (!rail) Dock(
                    repo = d.apps,
                    favorites = favorites,
                    onApps = { panel = Panel.Drawer },
                    onSettings = { panel = Panel.Settings },
                )
            }
          }
        }

        when (val p = panel) {
            null -> Unit
            Panel.Drawer -> DrawerPanel(d, apps, close = { panel = null })
            Panel.Contacts -> ContactsScreen(close = { panel = null })
            Panel.WeatherFull -> WeatherFullScreen(d, close = { panel = null })
            Panel.RadioFull -> RadioScreen({ panel = null }, Modifier.fillMaxSize())
            is Panel.MapFull -> MapFullScreen(d, p.data, mapApp(d, apps), close = { panel = null })
            Panel.Settings, Panel.Updates, Panel.Workshop, Panel.MusicSettings, Panel.TiresSettings -> SettingsPanel(
                d,
                revision = revision,
                close = { panel = null },
                // Acomodar cierra lo que esté en grande (si no, el tablero ni se ve).
                onEdit = { panel = null; expanded = null; editing = true },
                onTemplates = { panel = Panel.Templates },
                start = when (p) {
                    Panel.Updates -> SettingsSection.SYSTEM
                    Panel.Workshop -> SettingsSection.WORKSHOP
                    Panel.MusicSettings -> SettingsSection.APPS
                    Panel.TiresSettings -> SettingsSection.TIRES
                    else -> SettingsSection.CAR
                },
                onTires = { panel = null; openTires() },
            )
            Panel.Templates -> TemplatesPanel(d.dashboard, d.prefs, close = { panel = null })
            Panel.AddTile -> AddTilePanel(
                d,
                close = { panel = null },
                onPickApp = { panel = Panel.PickApp(null) },
                onPickGauge = { panel = Panel.PickGauge(null) },
                onPickWidget = { panel = Panel.PickWidget },
            )
            is Panel.PickApp -> PickAppPanel(d, apps, p.tileId, close = { panel = null })
            is Panel.PickGauge -> PickGaugePanel(d, p.tileId, close = { panel = null })
            Panel.PickWidget -> PickWidgetPanel(d, close = { panel = null })
            is Panel.CustomizeGauge -> CustomizeGaugePanel(d, p.tileId, close = { panel = null }, onPickSignal = { panel = Panel.PickGauge(p.tileId) })
            is Panel.CustomizeImage -> CustomizeImagePanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeMap -> CustomizeMapPanel(d, p.tileId, close = { panel = null })
        }

        val big = bigTile
        if (carShown && panel == null && big != null) {
            CarOnly(
                d, car, big, tileBounds[big.id] ?: Rect.Zero, { carProgress.value }, backdrop,
                onTap = { carOnly = false }, onReady = { carReady = true },
            )
        }

        if (tiresShown && panel == null) {
            TiresFull(
                d, { tiresProgress.value },
                onClose = { tiresOpen = false },
                onSettings = { tiresOpen = false; panel = Panel.TiresSettings },
                onReady = { tiresReady = true },
            )
        }

        if (parked) Parked(d, onWake = { parked = false; touched++ })

        // Animación de entrada con tu imagen, una vez por cada vez que se abre LiteDash (no al cambiar de idioma).
        var intro by rememberSaveable { mutableStateOf(!MainActivity.takeRestarted() && d.prefs.logoIntro && d.prefs.welcomeSeen) }
        if (intro) LogoIntro(d.prefs) { intro = false }

        if (!d.prefs.welcomeSeen) {
            Welcome(d.prefs, d.dashboard, revision, onFinish = { d.prefs.welcomeSeen = true })
        }
    }
}

/**
 * Estacionado: reloj grande, saludo y clima (o tu imagen, si la elegiste) sobre fondo oscuro.
 * Sale tras [PARK_AFTER_MS] sin tocar el inicio (y sin ir en movimiento); un toque lo quita.
 */
@Composable
private fun Parked(d: Dependencies, onWake: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        // Sin imagen guardada, el reloj.
        if (d.prefs.parkedView == ParkedView.IMAGE && parkedImageShown(d.prefs)) return@Box
        val clock = rememberClock()
        val hello = greeting(d.prefs.driverName)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Siempre en colores oscuros: de noche no deslumbra.
            LiteDashTheme(dark = true, scale = 1f, accent = d.prefs.accentColor) {
                BigTime(clock, Typography.clock.copy(fontSize = 120.sp))
                Label(hello?.let { "$it · ${clock.date}" } ?: clock.date, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1)
                Box(Modifier.padding(top = 12.dp)) { WeatherChip(d.weather) }
            }
        }
    }
}

private const val PARK_AFTER_MS = 5 * 60_000L

/** Más rápido que esto, va manejando (el modo estacionado espera). */
private const val MOVING_KMH = 8f

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
fun dockFavorites(d: Dependencies, apps: List<App>): List<App> {
    d.prefs.dock?.let { keys -> return keys.mapNotNull { d.apps.find(apps, it) } }
    return listOfNotNull(
        // Android Auto / CarPlay primero: mucha gente usa la pantalla con su celular.
        projectionApp(apps),
        d.apps.firstOf(apps, *NAVIGATION_PACKAGES),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.prefs.musicApp?.let { d.apps.firstOf(apps, it) } ?: d.apps.firstOf(apps, *MUSIC_PACKAGES),
        d.apps.firstOf(apps, "com.google.android.youtube"),
        d.apps.firstOf(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.firstOf(apps, "com.android.chrome"),
    )
}

/** Alto de la barra de arriba, y el espacio que deja cuando no muestra nada. */
@Composable
private fun topBarHeight() = if (isShortScreen()) 44.dp else 64.dp
private val TOP_BAR_HIDDEN = 12.dp

@Composable
private fun TopBar(editing: Boolean, showTime: Boolean, driverName: String?, weather: WeatherRepository, onAdd: () -> Unit, onTemplates: () -> Unit, onFinish: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val offline = rememberOffline()
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editing && !showTime) {
        if (!offline) return Spacer(Modifier.height(TOP_BAR_HIDDEN))
        return Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
            OfflineNotice { openWifiSettings(context) }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(topBarHeight()).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) {
            Label(stringResource(R.string.arrange_your_home), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.templates), onTemplates)
            Button(stringResource(R.string.add), onAdd)
            Button(stringResource(R.string.done), onFinish, primary = true)
        } else {
            val clock = rememberClock()
            // Fecha a la izquierda, hora grande al centro e indicadores de conexión a la derecha.
            val hello = greeting(driverName)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (offline) OfflineNotice { openWifiSettings(context) }
                else Label(if (hello != null) "$hello · ${clock.date}" else clock.date, Typography.body, c.textMuted, maxLines = 1)
            }
            Label(clock.full, Typography.title, maxLines = 1)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { StatusIcons() }
        }
    }
}

@Composable
private fun Dock(
    repo: AppRepository,
    favorites: List<App>,
    onApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    // En pantallas chicas (7") todo se ve lejos: íconos más grandes, más fáciles de atinar manejando.
    // Pero si casi no hay altura (teléfono acostado), compacto: el tablero va primero.
    val short = isShortScreen()
    val small = isSmallScreen() && !short
    val side = if (small) 56.dp else if (short) 36.dp else 44.dp
    val box = if (small) 68.dp else if (short) 46.dp else 56.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (small) 96.dp else if (short) 64.dp else 84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(c.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonSize = if (small) 64.dp else if (short) 46.dp else 56.dp
        IconButton(Icons.apps, onApps, size = buttonSize)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(c.line))
        BoxWithConstraints(Modifier.weight(1f)) {
            // Caben las que quepan: en pantallas chicas el dock no se encima.
            val fitCount = (maxWidth / (box + 8.dp)).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (app in favorites.take(fitCount)) AppShortcut(repo, app, box, side)
            }
        }
        Volume(buttonSize)
        // Voz: abre el asistente que tenga la pantalla; si no hay ninguno, no aparece.
        val voice = remember { voiceIntent(context) }
        if (voice != null) IconButton(Icons.mic, { runCatching { context.startActivity(voice) } }, size = buttonSize)
        IconButton(Icons.settings, onSettings, size = buttonSize)
    }
}

/** Una app favorita del dock o de la barra lateral: su ícono, un toque la abre. */
@Composable
private fun AppShortcut(repo: AppRepository, app: App, box: Dp, icon: Dp) {
    val context = LocalContext.current
    Box(Modifier.size(box).clip(RoundedCornerShape(Radius.inner)).clickable { repo.open(context, app) }, contentAlignment = Alignment.Center) {
        AppIcon(repo, app, icon)
    }
}

/**
 * Barra lateral (pantallas acostadas): accesos grandes a Inicio, Android Auto, Música, Radio,
 * Teléfono, Apps y Ajustes, con el activo en ámbar.
 */
@Composable
private fun Rail(
    active: Expanded?,
    onHome: () -> Unit,
    onProjection: () -> Unit,
    onMusic: () -> Unit,
    onRadio: () -> Unit,
    onPhone: () -> Unit,
    onApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val small = isSmallScreen()
    Card(Modifier.fillMaxHeight().width(if (small) 92.dp else 104.dp).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RailButton(Icons.home, stringResource(R.string.home), active == null, onHome, Modifier.weight(1f))
            RailButton(Icons.phoneCar, stringResource(R.string.rail_projection), false, onProjection, Modifier.weight(1f))
            RailButton(Icons.music, stringResource(R.string.tile_music), active == Expanded.MUSIC, onMusic, Modifier.weight(1f))
            RailButton(Icons.radio, stringResource(R.string.tile_radio), active == Expanded.RADIO, onRadio, Modifier.weight(1f))
            RailButton(Icons.phone, stringResource(R.string.tile_phone), false, onPhone, Modifier.weight(1f))
            RailButton(Icons.apps, stringResource(R.string.apps), false, onApps, Modifier.weight(1f))
            RailButton(Icons.settings, stringResource(R.string.rail_settings), false, onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RailButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.inner)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(if (on) c.accent else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (on) c.onPrimary else c.text
        Icon(icon, tint, 26.dp)
        Label(label, Typography.small.copy(fontSize = 12.sp), tint, maxLines = 1)
    }
}

/** Versión nueva encontrada al abrir LiteDash: verla en Ajustes o dejarla para después. */
@Composable
private fun UpdateBanner(updater: Updater, onOpen: () -> Unit) {
    val v = updater.notice ?: return
    Notice(stringResource(R.string.new_version, v.name), Icons.info) {
        Button(stringResource(R.string.update_see), onOpen, primary = true)
        Button(stringResource(R.string.update_later), updater::dismiss)
    }
}

/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun HomePrompt(prefs: Preferences, revision: Int) {
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    val isHome = remember(revision, returns) { isHomeApp(context) }
    if (isHome) return
    Notice(stringResource(R.string.not_home_notice)) {
        Button(stringResource(R.string.make_home), request, primary = true)
        Button(stringResource(R.string.dont_ask), { prefs.dontAskHome = true })
    }
}

/** El asistente de voz del sistema (manos libres primero); null si la pantalla no trae ninguno. */
private fun voiceIntent(context: Context): Intent? =
    listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

/** Lo que mide la barra de Android de arriba mientras está oculta (0 si se ve). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHiddenHeight(): Dp {
    val density = LocalDensity.current
    val always = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val now = WindowInsets.statusBars.getTop(density)
    return with(density) { (always - now).coerceAtLeast(0).toDp() }
}

/** Tamaño mínimo de la pieza del auto o imagen para ponerle las pastillas de llantas en sus esquinas. */
private val PILLS_MIN_WIDTH = 220.dp
private val PILLS_MIN_HEIGHT = 160.dp

/** Lo más que Llantas espera a que el auto de arriba esté pintado antes de aparecer. */
private const val TIRES_WAIT_MS = 300L

/** Lo que tardan las llantas a pantalla completa en aparecer (y en irse). */
private const val TIRES_FADE_MS = 260

/** Los widgets con botón de ajustes al acomodar el inicio. */
private val CUSTOMIZABLE = setOf(TileTypes.SPEEDOMETER, TileTypes.GAUGE, TileTypes.ROAD, TileTypes.IMAGE, TileTypes.MAP)

/**
 * Media pantalla: [content] (el widget en grande) y, en la otra mitad, [side]. Acostada, lado a
 * lado ([sideFirst] = [side] a la izquierda); parada, uno sobre otro ([side] arriba).
 */
@Composable
private fun HalfScreen(side: @Composable () -> Unit, sideFirst: Boolean, vertical: Boolean, content: @Composable () -> Unit) {
    val parts = if (sideFirst) listOf(side, content) else listOf(content, side)
    if (vertical) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (part in parts) Box(Modifier.weight(1f).fillMaxWidth()) { part() }
    } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (part in parts) Box(Modifier.weight(1f).fillMaxHeight()) { part() }
    }
}

/** Ocupa media pantalla o más ([grid] = la cuadrícula del inicio): crecer no tendría caso. */
private fun Tile.fillsHalf(grid: Grid?) = grid != null && width * height * 2 >= grid.columns * grid.rows

/** La música en grande: lo que suena, las fuentes y la biblioteca de la USB. */
@Composable
private fun MusicBig(d: Dependencies, close: (() -> Unit)?, modifier: Modifier = Modifier) = MusicExpanded(
    d.music, close = close, modifier,
    browser = { picked -> PlayerBrowser(PlayerSource.USB, picked, Modifier.fillMaxSize()) },
)

/** La radio en grande: FM o internet y, si está prendida, las estaciones por internet. */
@Composable
private fun RadioBig(d: Dependencies, close: (() -> Unit)?, modifier: Modifier = Modifier, onMaximize: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    // La estrella de lo que suena: solo si es una estación de internet.
    val station = Player.state.current as? Station
    val favorite = if (d.prefs.internetRadio && station != null) RadioFavorites.isFavorite(context, station.id) else null
    RadioExpanded(
        d.music, close = close, onSettings = onSettings, modifier,
        onMaximize = onMaximize,
        browser = if (d.prefs.internetRadio) { picked -> PlayerBrowser(PlayerSource.RADIO, picked, Modifier.fillMaxSize()) } else null,
        favorite = favorite,
        onFavorite = station?.let { st -> { RadioFavorites.toggle(context, st) } },
    )
}
