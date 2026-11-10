# Detección de viajes y odómetro por GPS (Guantera)

## 1. Constantes y configuración inicial
- `V_MIN_MOVE` = 1.4 m/s (5.0 km/h), `V_STOP` = 0.8 m/s (~3.0 km/h) (histéresis de marcha).
- `ACCURACY_MAX` = 25.0 m, `MAX_SPEED_PLAUSIBLE` = 50.0 m/s (180 km/h), `MIN_STEP_DIST` = 2.0 m.
- `STOP_TIMEOUT_MS` = 180_000 (3 min), `TUNNEL_TIMEOUT_MS` = 300_000 (5 min), `START_TIMEOUT_MS` = 15_000.
- `TRIP_MIN_DIST` = 200.0 m, `TRIP_MIN_DURATION_S` = 120 s (descarte de maniobras de garaje).
- `LocationManager`: `minTimeMs` = 1000, `minDistance` = 0.0m (obligatorio para recibir fixes detenido).
- **Relojes**: Monotónico (`elapsedRealtime()`) para FSM, dt, velocidad y timeouts (inmune a saltos NTP/GPS). UTC (`currentTimeMillis()`) para metadatos de persistencia; si UTC retrocede, `endTimeUtc = startTimeUtc + durationMono`.

## 2. Sanitización e inferencia de puntos (`sanitizePoint`)
Aplica a todos los estados antes de entrar a la FSM:
1. Precisión: si `!pt.hasAccuracy() || pt.accuracy > ACCURACY_MAX`, descartar punto.
2. Orden: un punto con `monoMs` no posterior al último aceptado se descarta.
3. Velocidad: si `pt.hasSpeed()`, usar `pt.speed`. Si `!pt.hasSpeed()`, usar `haversine(previo, pt) / dt`, donde `previo` es el último punto aceptado por esta sanitización en cualquier estado; sin `previo`, `0.0f`. No se usa `lastValidPt`, porque en `PARKED` no avanza y un receptor sin velocidad nunca podría arrancar un viaje.

## 3. Máquina de estados (FSM pura en Domain)
Estados: `PARKED`, `STARTING`, `IN_TRIP`, `STOPPING`. Eventos: `OnPoint(pt)`, `OnTick(nowMonoMs)`.

Dos precisiones sobre el pseudocódigo: un punto aceptado aplica primero `onTick(pt.monoMs)`, para que un vencimiento no dependa de que haya llegado un tick antes; y en `STARTING`, `IN_TRIP` y `STOPPING` (al reanudar) un salto que supera `MAX_SPEED_PLAUSIBLE` contra la última ancla no mueve la máquina ni actualiza `lastValidPt`, para que un punto espurio no termine siendo el lugar de estacionado.

```kotlin
fun onPoint(pt: Point) {
  val cleanPt = sanitize(pt) ?: return
  when (state) {
    PARKED -> if (cleanPt.speed >= V_MIN_MOVE) { anchorPt = cleanPt; state = STARTING }
    STARTING -> if (haversine(anchorPt, cleanPt) >= 10.0 && cleanPt.speed >= V_MIN_MOVE) {
      trip = Trip(startUtcMs = anchorPt.utcMs, startMonoMs = anchorPt.monoMs)
      lastDistPt = anchorPt; lastValidPt = cleanPt; state = IN_TRIP
      accumulateDistance(cleanPt)
    }
    IN_TRIP -> if (cleanPt.speed < V_STOP) { stopCandidateMonoMs = cleanPt.monoMs; state = STOPPING }
               else { accumulateDistance(cleanPt); lastValidPt = cleanPt }
    STOPPING -> if (cleanPt.speed >= V_MIN_MOVE) { state = IN_TRIP; accumulateDistance(cleanPt); lastValidPt = cleanPt }
  }
}
fun onTick(nowMonoMs: Long) {
  when (state) {
    STARTING -> if (nowMonoMs - anchorPt.monoMs > START_TIMEOUT_MS) state = PARKED
    IN_TRIP -> if (nowMonoMs - lastValidPt.monoMs > TUNNEL_TIMEOUT_MS) finishTrip(lastValidPt.utcMs, lastValidPt.monoMs)
    STOPPING -> if (nowMonoMs - stopCandidateMonoMs >= STOP_TIMEOUT_MS) finishTrip(lastValidPt.utcMs, stopCandidateMonoMs)
  }
}
fun accumulateDistance(pt: Point) {
  val dist = haversine(lastDistPt, pt)
  val dt = (pt.monoMs - lastDistPt.monoMs) / 1000.0
  if (dt > 0 && (dist / dt) <= MAX_SPEED_PLAUSIBLE) {
    if (dist >= MIN_STEP_DIST) { trip.distanceMeters += dist; lastDistPt = pt } // Ancla no avanza si dist < 2m
  }
}
fun finishTrip(endUtcMs: Long, endMonoMs: Long) {
  val durSec = (endMonoMs - trip.startMonoMs) / 1000
  if (trip.distanceMeters >= TRIP_MIN_DIST || durSec >= TRIP_MIN_DURATION_S) save(trip.copy(endUtcMs = endUtcMs))
  state = PARKED
}
```

## 4. Casos borde, persistencia y odómetro
- **Paso de hombre**: `lastDistPt` solo avanza al sumar $\ge$ 2 m. 10 fixes de 0.5 m se acumulan contra el ancla y suman 2 m cada 4 s sin pérdida.
- **Túnel prolongado**: `OnTick` detecta > 300 s sin fixes en `IN_TRIP`; cierra el viaje con `endTime = lastValidPt.utcMs`.
- **Suspensión ACC OFF sin aviso**: Heartbeat cada 10 s a Room/SQLite (`ACTIVE`), guardando `monoMs`, `utcMs` y `bootCount` (`Settings.Global.BOOT_COUNT`). `elapsedRealtime()` sigue contando durante la suspensión pero vuelve a cero al reiniciar. Al despertar en el mismo arranque, si `nowMono - lastHeartbeatMono > STOP_TIMEOUT`, se cierra el viaje con el timestamp del último heartbeat. Si cambió `bootCount` o no se puede leer, se cierra siempre con ese timestamp, sin comparar relojes monotónicos de arranques distintos.
- **Reconciliación odómetro**: `OdoDisplay = OdoManualBase + (GpsAcumuladoActual - GpsAcumuladoCalibracion)`. Desfase determinista y no destructivo.

## 5. Casos de prueba (Domain Unit Tests)
1. **Auto detenido sin fixes**: En `STOPPING`, GPS deja de emitir fixes. `OnTick(now)` a los 180 s ejecuta `finishTrip`; estado pasa a `PARKED` y guarda viaje con hora del último fix.
2. **Túnel sin señal**: En `IN_TRIP` (10 km acumulados), entran 0 fixes durante 301 s. `OnTick` dispara `TUNNEL_TIMEOUT_MS`; cierra viaje en `PARKED` con distancia = 10 km y `endTime = lastValidPt.utcMs`.
3. **Semáforo**: En `IN_TRIP`, frena a 0 km/h durante 60 s (`STOPPING`). Luego punto a 30 km/h: retorna a `IN_TRIP`, viaje continúa unificado.
4. **Maniobra de garaje**: Recorre 35 m en 40 s y frena 180 s. `finishTrip` evalúa `dist < 200m` y `dur < 120s`; viaje descartado.
5. **Avance a paso de hombre**: 8 fixes consecutivos en línea recta, cada uno a 0.8 m del anterior y con velocidad 1.0 m/s, con el ancla en el punto inicial: suma en los fixes 3 (2.4 m) y 6 (2.4 m). Total = 4.8 m; los 1.6 m de los fixes 7 y 8 quedan pendientes contra el ancla hasta el próximo paso de 2 m.
6. **Fix espurio sin velocidad ni precisión**: Fix con `hasAccuracy = false` o `accuracy = 45m`: `sanitize` lo rechaza en cualquier estado; FSM no muta. Salto de 300 m en 1 s: rechazado por `MAX_SPEED_PLAUSIBLE`.

## 6. Rendimiento y Kalman (Criterio propio)
- Muestreo GPS a 1 Hz (`minDistance = 0m`). Escritura diferida en Room/SQLite con WAL (cada 10 s o fin de viaje).
- **Descarte de Kalman**: Sin IMU vehicular (giroscopio/acelerómetro), Kalman lineal sobre GPS ruidoso no supera el filtrado por gating e histéresis, y consume ciclos CPU innecesarios en un SoC Cortex-A7 de 32 bits (SC7731E).

## 7. Fuentes y distinción de origen
- **Verificado (leído en esta tarea)**:
  - Traccar Trips & Stops: https://www.traccar.org/trips-stops/ (`report.trip.minDistance` = 200 m y `report.trip.minDuration` = 180 s son parámetros para detección de paradas por permanencia dentro de un área geográfica, no umbrales de velocidad instantánea).
  - Traccar Configuration: https://www.traccar.org/configuration-file/ (claves de filtrado de posición: `filter.enable`, `filter.accuracy`, `filter.distance`, `filter.maxSpeed`).
  - Android Location APIs: https://developer.android.com/reference/android/location/Location (`hasAccuracy()`, `getAccuracy()`, `hasSpeed()`, `getSpeed()`, `getElapsedRealtimeNanos()`).
- **Criterio propio**:
  - Evento dual `OnPoint` + `OnTick` con `minDistance = 0.0m` para garantizar avance temporal de la FSM sin fixes.
  - Histéresis `V_MIN_MOVE` (5.0 km/h) / `V_STOP` (3.0 km/h) y filtro de paso de hombre por desplazamiento acumulado sobre ancla fija.
  - Descarte de Kalman y uso de persistencia por heartbeat a 10 s para tolerar la suspensión abrupta en el SoC SC7731E.
