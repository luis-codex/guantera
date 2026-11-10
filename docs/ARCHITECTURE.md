# Arquitectura de Guantera Sonda

Clean Architecture en tres módulos Gradle. La dependencia apunta siempre hacia `:domain`.

```
:app  ──►  :data  ──►  :domain
 └──────────────────────►
```

## Módulos
- `:domain` (Kotlin/JVM puro, sin Android; solo kotlinx-coroutines-core). Modelos, puertos, reglas y casos de uso.
  Verificable: `grep -rE "^import android" domain/` no devuelve nada, y el plugin es `kotlin-jvm`.
- `:data` (Android library). Adaptadores de los puertos: `AndroidLocationSource`, `FileEventLog`,
  `AndroidDiagnosticsSource`, `DownloadsReportStore`, `SystemClock`.
- `:app` (Android application). UI (`ui/`), puntos de entrada (`ProbeService`, `BootReceiver`,
  `ProbeApplication`) y la raíz de composición `AppContainer`.

## Regla de dependencias
`:domain` no conoce `:data` ni `:app`. La UI y los puntos de entrada solo llaman casos de uso
(`container.trackLocation`, `container.exportReport`, ...); nunca crean ni usan adaptadores.
Los puntos de entrada son finos: traducen un evento de Android a una llamada a un caso de uso.

## Patrones y por qué
| Patrón | Dónde | Motivo |
|---|---|---|
| Ports & Adapters | `domain/port` ↔ `data/` | Cada frontera con Android (GPS, archivo, diagnóstico, exportación, reloj) se puede reemplazar por un fake en tests. |
| Observer (Flow/StateFlow) | `EventLog.observeRecent`, `TrackingStatus`, `TrackLocation.latestFix` | La pantalla y la notificación se actualizan al cambiar el dato; no hay sondeo cada N segundos. |
| Strategy | `FixSamplingPolicy` / `IntervalSamplingPolicy` | La política de muestreo (hoy 1 fix cada 30 s) cambia sin tocar `TrackLocation`. |
| Composition Root manual | `AppContainer` | Inyección sin Hilt/Koin/Dagger: cero costo de arranque y RAM en el SoC SC7731E. |
| Reloj inyectado | puerto `Clock` | Suspensión y muestreo dependen del tiempo; con `FakeClock` los tests son deterministas. |

No hay interfaces con una sola implementación que no sean frontera (p. ej. los casos de uso son clases concretas).

## Resultados tipados
Los fallos esperados no son excepciones: `StartResult` (sin permiso, GPS), `ExportResult`
(`StoragePermissionRequired`, `Failed`). La UI los muestra con textos de `res/values/strings.xml`.
Las líneas del registro viven en `RecordEvent` y `TrackLocation` (son datos, no textos de UI).

## Registro en disco (`FileEventLog`)
Escritor único fuera del hilo principal, `fsync` por línea, rotación por tamaño (5 archivos x 1 MiB = 5 MiB máximo; se descarta el más antiguo).
Nada lanza: `append` devuelve `AppendResult`, la salud de la escritura es `EventLog.health` y la UI muestra los fallos.
Se guarda en almacenamiento protegido por dispositivo (funciona en Direct Boot). Exportar lo transmite por streaming, rotados incluidos.

## Tests
`./gradlew test` corre sin emulador (`:domain` y la lógica de archivos de `:data`, sin Context). Las reglas (`SuspensionDetector`, `IntervalSamplingPolicy`) son puras;
los casos de uso se prueban con fakes de los puertos (`domain/src/test/.../Fakes.kt`).

## Cómo agregar una función nueva
1. Modelo y resultados tipados en `domain/model`; si necesita Android, un puerto en `domain/port`.
2. Regla pura en `domain/rule` y caso de uso en `domain/usecase`, con tests usando fakes.
3. Adaptador del puerto en `:data`.
4. Cablear en `AppContainer` (único lugar con `new` de adaptadores).
5. UI o punto de entrada: llamar al caso de uso; textos nuevos en `strings.xml`.
