<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/png/litedash-horizontal-sobre-oscuro.png">
    <img src="brand/png/litedash-horizontal-sobre-claro.png" alt="LiteDash" width="420">
  </picture>
</p>

<p align="center">
  <b>Launcher para pantallas Android de autos y motos: ligero, rápido y elegante.</b><br>
  <i>A light, fast and clean launcher for Android car and motorcycle screens.</i><br><br>
  <a href="https://litedash.web.app/">Sitio</a> ·
  <a href="https://github.com/IrvingGuerra/litedash/releases/latest">Descargar APK</a> ·
  <a href="https://litedash.web.app/en/">English</a>
</p>

<p align="center">
  <a href="https://ko-fi.com/irvingguerradev"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Apóyame en Ko-fi"></a>
</p>

---

## Qué es

LiteDash es una pantalla de inicio limpia para las pantallas Android que se
ponen en autos y motos, y también para tablets y teléfonos (de 6" a 11" o más).
**Todo es opcional y viene apagado**, para que vuele hasta en una pantalla de
1 GB de RAM: lo que está apagado no gasta memoria, batería ni permisos.

**Tú acomodas tu inicio**: deja presionado para mover, agrandar o quitar piezas
(tu auto, música, navegar, reloj, apps). Entre más grande la pantalla, más
lugares. Sin anuncios, sin cuentas, sin rastreo: cada permiso se pide solo si
activas la función que lo usa. Las funciones nuevas llegan dentro de la misma
app, con actualizaciones frecuentes.

## What it is (English)

LiteDash is a clean home screen for Android car and motorcycle screens, tablets and phones
(6"–11"+). **Everything is optional and off by default**, so it stays fast on
low-end units. **You arrange your home**: long-press to move, resize or remove
pieces (your car, music, navigation, clock, apps). No ads, no accounts, no
tracking.

## Instalar / Install

1. Descarga el APK de [Releases](https://github.com/IrvingGuerra/litedash/releases/latest)
   (pronto también en Google Play). Después se actualiza solo desde
   Ajustes → Acerca de → Buscar actualizaciones.
2. Instálalo y elige **LiteDash** como pantalla de inicio. En muchas pantallas de
   auto esto está en Ajustes → Aplicaciones → Pantalla de inicio, o en un menú
   del fabricante para elegir el launcher.

Requiere Android 5.0 (Lollipop) o más nuevo, para que corra también en las
pantallas de auto viejas.

## Compilar / Build

JDK 17 y el SDK de Android con la plataforma 37.

```bash
./gradlew assemblePlayDebug        # versión para Google Play, depuración
./gradlew assembleGithubRelease    # APK para GitHub
tools/avd.sh                       # emuladores de pantallas de auto (1024×600 a 2000×1200)
tools/spin.sh                      # qué tan fluido gira el auto (cuadros por segundo)
tools/measure.sh                   # medir arranque, memoria y fluidez en un equipo conectado
```

Hay dos variantes del mismo código: `play` (Google Play, sin permiso de
internet) y `github` (APK; usa internet solo cuando tocas "Buscar
actualizaciones" en Ajustes, para bajar e instalar la versión nueva).

## Contribuir / Contributing

Lee [`CONTRIBUTING.md`](CONTRIBUTING.md). Los reportes de pantallas, sensores y
cajas CAN son de lo más útil.

## Licencia / License

Código bajo [GPL-3.0](LICENSE). El nombre y el logo de LiteDash no están
incluidos en esa licencia. *The LiteDash name and logo are not covered by the
code license.*

Las recetas para leer la presión de llantas de fábrica por OBD
(`feature/obd/.../FactoryTires.kt`) son derivadas de [OBDb](https://github.com/OBDb),
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), adaptadas. *The factory
tire-pressure recipes are derived from OBDb (CC BY-SA 4.0), adapted.*
