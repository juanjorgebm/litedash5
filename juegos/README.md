# Juegos para el carro

App aparte (no forma parte de LiteDash) con 6 minijuegos para la pantalla táctil del carro,
pensada para pantallas de 8 a 10 pulgadas en horizontal con Android 5.1 (API 22) o más nuevo.

- **Solitario** (robar 1 o 3; tocar una carta la mueve sola, también se puede arrastrar)
- **Tres en raya** (contra la app en 3 niveles, o entre dos)
- **Reflejos** (pantalla dividida para dos, o medir tu tiempo de reacción)
- **Parejas** (12, 18 o 24 cartas; solo contra el reloj o por turnos)
- **Cuatro en línea** (contra la app en 3 niveles, o entre dos)
- **Golpea al topo** (solo, o dos a la vez con la pantalla dividida)

**Torneo**: dos jugadores eligen los juegos y cuántas vueltas; la app lleva el marcador,
anuncia al ganador y guarda el historial. Si quedan empatados se juega un desempate de Reflejos.

Todo se dibuja con Canvas (sin librerías), así que el APK es pequeño y va fluido en equipos modestos.
La fuente Nunito (licencia OFL) va incluida porque la negrita del sistema del carro no trae tildes.

## Compilar

GitHub Actions (`.github/workflows/juegos.yml`) compila el APK en cada cambio dentro de `juegos/`
y lo publica en la release `juegos-carro`. En local: `cd juegos && ./gradlew assembleRelease`.

El APK se firma con `app/juegos.keystore` (incluida a propósito) para que cada versión nueva
se instale encima de la anterior sin borrar récords.
