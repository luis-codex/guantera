# Arquitectura de Guantera

Clean Architecture con `:domain` como núcleo y adaptadores Android separados. La dependencia apunta
siempre hacia `:domain`.

```
:guantera ──► :storage ──► :domain
          └─► :media   ──► :domain
:app      ──► :data    ──► :domain
```

## Módulos
- `:domain` (Kotlin/JVM puro, sin Android; solo kotlinx-coroutines-core). Modelos, puertos, reglas y casos de uso.
  Verificable: `grep -rE "^import android" domain/` no devuelve nada, y el plugin es `kotlin-jvm`.
- `:storage` (Android library). Persistencia Room, copias locales, exportación extraíble y chequeo
  de salud de datos.
- `:media` (Android library). Control real de reproducción y diagnóstico de rutas disponibles.
- `:guantera` (Android application). UI, `TrackingService`, `BootReceiver` y la raíz de composición
  `AppContainer`; nunca crea repositorios directamente.
- `:data` y `:app` conservan la sonda independiente.

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

## Seguimiento y persistencia

`TrackingService` es foreground y arranca al conceder ubicación o al encender la radio. Mantiene un
solo `TripEngine`, recupera viajes al despertar, consume fixes GPS y ejecuta `ProcessTick` cada
segundo incluso sin señal. `StorageFactory` conecta Room, WAL y las copias verificadas.

## Arranque: recuperación (P12) y primer uso (P0)
`GuanteraApp.onCreate` crea el almacenamiento (Room abre recién en la primera consulta) y corre
`CheckDataHealthAndRecover` **antes de crear `AppContainer`** y de iniciar el servicio. Si la base está sana, o
se restauró una copia, se crea el contenedor. Si no, `MainActivity` redirige a `RecoveryActivity`, que solo usa
`GuanteraApp.recovery` (`RealRecovery` sobre un grafo liviano de `startup/RecoveryWiring`, sin ubicación ni
notificaciones) y `RecoveryPresenter`. Nada de P12 depende del contenedor, del shell ni del servicio, así que un
fallo en el resto de la app no quita el acceso a los datos. "Empezar de cero" exige confirmación y aparta la base,
nunca la borra. Al terminar, `GuanteraApp.recoveryFinished()` crea el contenedor y arranca el servicio.

P0 (`OnboardingPresenter`) vive dentro del shell: `ShellViewState.showFirstUse` reemplaza riel y pantallas hasta
que `Settings.firstUseComplete` sea verdadero; P2 (conducción) tiene prioridad.

## Escrituras y confirmación de detenido
Toda escritura de un formulario pasa por el dominio (`FormSubmission`) y vuelve como `FormOutcome`
(`Saved`, `NeedsConfirmation`, `CarMoving`, `Rejected`, `StorageFull`, `StorageFailed`). Las pantallas no adivinan
si el auto está estacionado: con `NeedsConfirmation` preguntan "¿El auto está estacionado?", llaman a
`ConfirmParked` (`GuanteraData.confirmStopped`) y repiten la escritura; con un error de guardado el formulario
conserva lo escrito y guardar de nuevo reintenta.

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
