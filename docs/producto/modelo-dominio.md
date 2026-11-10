# Guantera v1: modelo de dominio
Todo en `:domain`, sin dependencias de plataforma. Pantallas P0 a P11: `especificacion.md`. Umbrales de viaje: `docs/investigacion/deteccion-viajes.md`. Las reglas son **Criterio propio**, salvo los umbrales, que vienen de ese documento.

## Entidades y objetos de valor
- **LocationFix** (valor): lat, lon, velocidad, precisión, instante.
- **Trip**: id, inicio, fin?, distanciaMetros, puntoInicio, puntoFin?, cierre (`AUTOMATIC`, `MANUAL`, `RECOVERED`), estado (`ACTIVE`, `CLOSED`).
- **OdometerCalibration**: fecha, odoRealKm, gpsAcumuladoKm. Se agrega, nunca se edita.
- **MaintenanceItem**: id, nombre, intervalKm?, intervalMonths?, fixedDueDate?, lastServiceKm?, lastServiceDate?.
- **ServiceRecord**: id, itemId, fecha, km?, nota?.
- **Expense**: id, categoría, montoMinor (entero), fecha, nota?, serviceRecordId?.
- **ParkingSpot**: id, lat, lon, precisión, instante, origen (`TRIP_END`, `MANUAL`), nota?, esUltimoPuntoConSenal.
- **Settings**: moneda, abrirAlArrancar, primerUsoCompleto.
- **DrivingState** (valor): `DETENIDO`, `EN_MOVIMIENTO`, `DESCONOCIDO`.
- **MaintenanceStatus** (valor): `VENCIDO`, `PROXIMO`, `AL_DIA`, `SIN_DATOS`.

## Reglas e invariantes
**Viajes.** R1. La máquina `PARKED`, `STARTING`, `IN_TRIP`, `STOPPING` y sus umbrales son los de `deteccion-viajes.md`. R2. Hay como máximo un Trip `ACTIVE`. R3. Un Trip cerrado tiene fin ≥ inicio y distancia ≥ 0, y es inmutable. R4. Al recuperarse de un corte, un Trip `ACTIVE` se cierra como `RECOVERED` en su último punto válido si pasó más de `STOP_TIMEOUT` o si la radio se reinició (ver `bootCount` en `deteccion-viajes.md`). R5. Todo cierre crea un ParkingSpot `TRIP_END` en el último punto válido, también si el viaje se descarta por no llegar a los mínimos, porque el auto igual cambió de lugar.
**Movimiento.** R6. `EN_MOVIMIENTO` si la máquina no está en `PARKED`. R7. `DETENIDO` si está en `PARKED`, con fix de hace ≤ 30 s, precisión ≤ `ACCURACY_MAX` y velocidad < `V_STOP`. R8. En cualquier otro caso, `DESCONOCIDO`. R9. La confirmación manual vale solo desde `DESCONOCIDO`, 10 min o hasta salir de `PARKED`. R10. Los formularios se aceptan en `DETENIDO` o con confirmación vigente. R11. "Ya estacioné" se acepta solo en `STOPPING` con ≥ 20 s detenido, cierra como `MANUAL` y pasa la máquina a `PARKED`.
**Odómetro.** R12. `OdoActual = odoRealKm + (GpsAcumulado − gpsAcumuladoKm)` con la última calibración. R13. Calibrar no altera viajes. R14. Sin calibración, `OdoActual` es desconocido.
**Mantenimiento.** R15. Un ítem tiene al menos uno de intervalKm, intervalMonths o fixedDueDate, y los intervalos son > 0. R16. `dueKm = lastServiceKm + intervalKm`. R17. `dueDate = fixedDueDate` si existe; si no, `lastServiceDate + intervalMonths`. R18. Se evalúa cada eje con dato y manda el peor: lo que ocurra primero. R19. `PROXIMO`: faltan ≤ min(1000 km, 10 % del intervalo), o ≤ min(30 días, 10 % del intervalo en días); con fixedDueDate, ≤ 30 días. R20. `SIN_DATOS` si ningún eje se puede calcular, y nunca genera aviso. R21. Registrar un service actualiza el último service, limpia fixedDueDate o lo reemplaza por el nuevo vencimiento, y, si hay costo, crea un Expense; todo en una sola transacción.
**Gastos.** R22. montoMinor > 0, moneda única en la app, sin conversión. R23. Costo por km = gastos del período ÷ km de viajes cerrados del período. R24. Con menos de 50 km el costo no se calcula. R25. Editar o borrar un Expense ligado a un service no borra el service; solo rompe el vínculo al borrar.
**Estacionado.** R26. El ParkingSpot vigente es el de instante más reciente. R27. `MANUAL` no se acepta en `EN_MOVIMIENTO` y exige un fix válido.
**Tiempo.** R28. El reloj es confiable si no es anterior al último instante guardado y difiere ≤ 24 h del último fix. R29. Con reloj no confiable se usa el tiempo del fix si hay uno, y sin fix los ejes por fecha no generan avisos.
**Avisos.** R30. Las alertas se ordenan `VENCIDO`, `PROXIMO`; dentro de cada grupo, por menor margen. R31. Con `EN_MOVIMIENTO` no se publican alertas nuevas.
**Respaldo.** R32. Una copia solo cuenta como válida si se releyó y su suma de verificación coincide. R33. Una copia válida no se reemplaza ni se borra antes de validar la siguiente. R34. Retención: las últimas 7 y una por semana durante 4 semanas. R35. Importar valida todo el archivo antes de modificar datos, y crea una copia de lo actual antes de reemplazarlo. R36. Se rechaza una copia de esquema más nuevo que el de la app. R37. Lo dañado se aparta, nunca se borra. Escenarios y pérdida máxima: `resiliencia.md`.

## Casos de uso y rastreo a pantallas
| CU | Caso de uso | Pantalla |
|---|---|---|
| CU-01 | ProcessLocationFix y ProcessTick: alimentan la máquina con puntos y con el paso del tiempo, y persisten | P1, P2 (efecto visible) |
| CU-02 | RecoverTripAfterWake | P3 (marca "Cierre estimado"), P10 |
| CU-03 | ObserveDrivingState | P2, barra común |
| CU-04 | ConfirmParked | Diálogo de confirmación en P0, P6, P7, P9, P10, P11 |
| CU-05 | GetDashboard | P1 |
| CU-06 | ListTrips | P3 |
| CU-07 | GetTripDetail | P4 |
| CU-08 | CalibrateOdometer | P0, P11 |
| CU-09 | ListMaintenanceStatus | P5 |
| CU-10 | SaveMaintenanceItem | P6 |
| CU-11 | DeleteMaintenanceItem | P6 |
| CU-12 | RegisterService | P7 |
| CU-13 | GetPendingAlerts | P1, P5, notificación |
| CU-14 | SeedOrRestoreCatalog | P0, P5, P11 |
| CU-15 | SaveExpense | P8, P9 |
| CU-16 | DeleteExpense | P8, P9 |
| CU-17 | ListExpenses | P8 |
| CU-18 | GetCostPerKm | P8 |
| CU-19 | GetLastParkingSpot | P10, P1 |
| CU-20 | MarkParkedNow | P2, P10 |
| CU-21 | SaveParkingNote | P10 |
| CU-22 | CheckTrackingHealth | P0, P1, P11, banner |
| CU-23 | ResolveTrustedTime: sin UI propia | P1 (banner) |
| CU-24 | SaveSettings | P0, P11 |
| CU-25 | CreateAutomaticBackup: sin UI propia | P11 (fecha de la última copia) |
| CU-26 | ExportBackup | P11, P12 |
| CU-27 | ImportBackup | P0, P11, P12 |
| CU-28 | CheckDataHealthAndRecover | P12 |

## Puertos
Fronteras reales, cada una con su adaptador en `:data`:
- **LocationSource**: flujo de LocationFix a 1 Hz. La política de muestreo de ~30 s de la sonda solo sirve para diagnóstico; el motor de viajes necesita 1 Hz.
- **TrackingHealthSource**: permiso y GPS encendido.
- **Clock**: instante actual, ya previsto en la arquitectura.
- **TripRepository**, **OdometerRepository**, **MaintenanceRepository**, **ExpenseRepository**, **ParkingRepository**, **SettingsRepository**.
- **TransactionRunner**: atomicidad de R21.
- **AlertPublisher**: publica la alerta más urgente.
- **BackupStore**: guarda, lista, valida y rota copias en la radio.
- **ExternalBackupTransfer**: exporta e importa en pendrive.
- **DataHealthChecker**: comprueba la integridad de la base y aparta lo dañado.
