# Guantera v1: especificación de producto

Casos de uso (CU-xx) y reglas: `modelo-dominio.md`. Ítems e intervalos: `catalogo-mantenimiento.md`. Umbrales de viaje: `docs/investigacion/deteccion-viajes.md`.
Salvo que se cite una fuente, todo este documento es **Criterio propio**.

## 1. Usuario y principios
Conductor particular, un solo auto por radio. Sin cuenta, sin internet, sin datos CAN. Quiere que los km y los avisos salgan solos y cargar datos solo estando detenido.
- **Se entiende de un vistazo:** cada pantalla responde una sola pregunta, con números grandes y frases cortas.
- **No interrumpe:** sin sonidos, sin ventanas emergentes, sin animaciones, sin texto que se desplace solo.
- **Registra sin que se lo pidan:** el usuario nunca inicia ni termina un viaje para que se guarde.
- **No inventa datos:** si falta un dato se muestra "sin datos". Los km son siempre una estimación por GPS, y se dice.

## 2. Base de seguridad
**Verificado** en [NHTSA, Guías Fase 1, 78 FR 24818, 2013](https://www.govinfo.gov/content/pkg/FR-2013-04-26/pdf/2013-09883.pdf):
- Son voluntarias y cubren equipo original. Para equipos de posventa, NHTSA alienta a adoptar lo que sea factible. Guantera las usa como referencia, no las cumple por certificación.
- Tareas posibles al manejar: miradas de 2 s o menos y 12 s acumulados como máximo.
- Bloqueo recomendado al manejar: funciones no pensadas para el conductor, ingreso manual de texto para mensajes o navegación web, video, imágenes no relacionadas con conducir, texto que se desplaza solo.
- Una tarea interrumpida debe poder retomarse y el equipo no debe perder lo ya ingresado.
- La guía entiende "detenido" como palanca en Park. **Guantera no puede saberlo** (sin CAN), así que usa las reglas de la sección 5.

**No medido:** los tiempos de mirada no se probaron. Hay que medirlos en la radio real antes de darlo por bueno.

## 3. Flujos principales
1. **Primer uso:** instalar, conceder ubicación, cargar odómetro (o saltear), elegir moneda. Se cargan los ítems del catálogo. Queda en Inicio.
2. **Viaje normal:** dar contacto, el registro arranca solo, manejar, estacionar, cortar. El viaje queda guardado sin ningún toque.
3. **Aviso de mantenimiento:** un ítem pasa a PRÓXIMO o VENCIDO, se ve en Inicio, se hace el service, se registra en P7 con costo opcional, que crea el gasto.
4. **Gasto:** detenido, Gastos, Nuevo, categoría y monto, Guardar. El costo por km se actualiza.
5. **Estacionado:** al estacionar, tocar "Ya estacioné" o esperar 3 min. P10 muestra el lugar y un QR para llevárselo en el celular.

## 4. Pantallas para 1280x720 horizontal
**Armazón común.** Barra superior de 64 px: estado de GPS, estado de movimiento y hora. Riel izquierdo de 200 px con Inicio, Viajes, Mantenimiento, Gastos, Estacionado y Ajustes. Contenido de 1080x656 px.
**Tamaños mínimos, en px físicos:** zonas táctiles de 96 px de alto con 16 px de separación, texto principal de 32 px, secundario de 24 px, cifras clave de 64 px. Contraste alto, tema oscuro. Una sola mano. Los estados no dependen solo del color: llevan siempre texto.
**Sin confirmar en la radio:** densidad real y si la barra de sistema quita alto. Se ajusta con el diagnóstico de la sonda.

**P0. Primer uso** (un paso por pantalla, solo sin movimiento)
- Contenido: permiso de ubicación con el porqué en una frase, odómetro actual, moneda (texto libre, por ejemplo "$").
- Acciones: Conceder, Guardar, Saltear. Sin odómetro, los ítems por km quedan SIN DATOS y los viajes funcionan igual.
- Texto fijo: "Los intervalos son orientativos. Manda el manual de tu auto."
- CU-08, CU-14, CU-22, CU-24.

**P1. Inicio**: ¿está todo bien?
- Tres tarjetas grandes. **Hoy:** km y viajes del día. **Mantenimiento:** el aviso más urgente o "Todo al día". **Estacionado:** hora y antigüedad del último lugar.
- Banner único arriba si hay problema (sección 6).
- Acciones: tocar una tarjeta abre P3, P5 o P10.
- CU-01, CU-05, CU-13, CU-19, CU-22, CU-23.

**P2. Modo conducción**: reemplaza a toda la app mientras el estado es EN_MOVIMIENTO
- Contenido fijo: km del viaje (cambia cada 1 km), minutos (cambia cada minuto), una línea con el aviso más urgente, leyenda "Detené el auto para usar Guantera".
- Sin riel, sin listas. Únicas acciones: controles de reproducción y volumen en botones grandes (ver `diseno.md`), y el botón **"Ya estacioné"**, que aparece tras 20 s en STOPPING y cierra el viaje.
- Si la app no estaba abierta, no se abre: el registro sigue en segundo plano.
- CU-01, CU-03, CU-20.

**P3. Viajes**: ¿cuánto anduve?
- Encabezado: km de hoy, de la semana y del mes. Lista paginada con arrastre manual: fecha, hora de inicio, duración, km.
- Marca "Cierre estimado" en viajes recuperados tras un corte. Los viajes son de solo lectura en v1.
- Acciones: tocar un viaje abre P4.
- Vacío: "Todavía no hay viajes. Se registran solos cuando el auto se mueve."
- CU-02, CU-06.

**P4. Detalle de viaje**
- Inicio y fin (hora, coordenadas), duración, km, velocidad media, tipo de cierre. Sin mapa: no hay mapas sin conexión en v1.
- Acción: Volver. CU-07.

**P5. Mantenimiento**: ¿qué toca hacer?
- Lista ordenada por urgencia: VENCIDO, PRÓXIMO, AL DÍA, SIN DATOS. Cada fila: nombre, estado en texto, lo que falta ("faltan 800 km", "vence en 12 días"), origen del dato.
- Acciones: tocar una fila abre P6, botón "Hecho" abre P7, "Agregar ítem" abre P6 vacío.
- Vacío: "No hay ítems. Agregá uno o restaurá el catálogo."
- CU-09, CU-13, CU-14.

**P6. Editar ítem** (formulario)
- Nombre, intervalo en km, intervalo en meses, fecha de vencimiento fija, último service (km y fecha).
- Validación: al menos un intervalo o una fecha. Aviso fijo: "Manda el manual del fabricante."
- Acciones: Guardar, Borrar con confirmación, Cancelar. CU-10, CU-11.

**P7. Registrar service** (formulario)
- Fecha (hoy por defecto), km (odómetro estimado por defecto), costo opcional, nota. Si el ítem solo tiene fecha fija, pide el nuevo vencimiento.
- Acciones: Guardar, Cancelar. Con costo, crea el gasto en categoría Mantenimiento. CU-12.

**P8. Gastos**: ¿cuánto me sale?
- Encabezado: **costo por km** en cifra grande, total y km del período. Selector: Este mes, 12 meses (por defecto), Todo.
- Lista paginada: fecha, categoría, monto.
- Acciones: "Nuevo" abre P9, tocar una fila abre P9 con datos.
- Vacío: "Sin gastos cargados." Con menos de 50 km en el período, el costo por km muestra "Pocos km para calcular".
- CU-15, CU-16, CU-17, CU-18.

**P9. Nuevo o editar gasto** (formulario)
- Categoría (botones grandes: Combustible, Mantenimiento, Seguro, Peajes, Estacionamiento, Lavado, Otros), monto, fecha (hoy por defecto), nota opcional.
- Acciones: Guardar, Borrar con confirmación si ya existe, Cancelar. CU-15, CU-16.

**P10. Estacionado**: ¿dónde quedó?
- Hora, antigüedad, coordenadas, precisión en metros, nota libre ("piso 2, columna B"), QR con la ubicación generado sin red. Si la posición es el último punto con señal, lo dice.
- Acciones: "Guardar acá ahora", Editar nota.
- Vacío: "Todavía no hay un lugar guardado."
- CU-02, CU-19, CU-20, CU-21.

**P11. Ajustes**
- Odómetro actual y fecha de la última calibración, moneda, estado de GPS y permiso, "Abrir Guantera al arrancar" (apagado por defecto), "Restaurar catálogo", fecha de la última copia y de la última exportación.
- Acciones: Corregir odómetro, Conceder permiso, cambiar valores, Exportar a pendrive, Importar desde pendrive. CU-08, CU-14, CU-22, CU-24, CU-26, CU-27.

**P12. Recuperación** (pantalla mínima, independiente del resto de la app, ver `resiliencia.md`)
- Aparece si la base está dañada, si falló una migración o tras 3 cierres inesperados seguidos al abrir.
- Contenido: qué pasó en una frase, copias disponibles con fecha y cantidad de datos.
- Acciones: Restaurar una copia, Importar desde pendrive, Exportar lo que se pueda, Empezar de cero (con confirmación, apartando los datos anteriores). CU-27, CU-28.

**Confirmación de detenido** (diálogo al abrir cualquier formulario en DESCONOCIDO: P0, P6, P7, P9, P10, P11): "¿El auto está estacionado?" Sí / No. CU-04.

## 5. Auto en movimiento
Sin CAN, el estado sale del GPS (definición exacta en `modelo-dominio.md`):

| Estado | Cuándo | Efecto |
|---|---|---|
| EN_MOVIMIENTO | Arrancando o con viaje en curso, incluso detenido en un semáforo o sin señal en un túnel | P2 reemplaza todo |
| DETENIDO | Sin viaje en curso y fix reciente y preciso, sin velocidad | Todo habilitado |
| DESCONOCIDO | Sin viaje en curso y sin fix válido, o sin permiso o GPS apagado | Lectura habilitada, formularios piden confirmación |

| Función | DETENIDO | DESCONOCIDO | EN_MOVIMIENTO |
|---|---|---|---|
| Ver P1, P3, P4, P5, P8, P10, P11 | Sí | Sí | No, se ve P2 |
| Formularios P0, P6, P7, P9, nota de P10, cambios en P11 | Sí | Tras confirmar | No |
| "Guardar acá ahora" | Sí | Tras confirmar | No |
| "Ya estacioné" | No aplica | No aplica | Solo tras 20 s en STOPPING |
| Registro de viaje | Sí | Sí | Sí |

- La confirmación vale 10 min o hasta que se detecte movimiento, lo que ocurra primero.
- **Interrupción:** si arranca el movimiento con un formulario abierto, P2 lo tapa y el borrador se conserva. Al detenerse se vuelve al mismo punto. Si se corta la energía antes de guardar, el borrador se pierde.
- Los avisos nuevos no cambian la notificación en viaje: esperan a que deje de ser EN_MOVIMIENTO.
- Límite conocido: en un corte de GPS mayor a 5 min el viaje se cierra y el estado pasa a DESCONOCIDO, aun si el auto sigue andando. Por eso los formularios piden confirmación.

## 6. Estados de error y avisos
Un solo banner a la vez, en este orden de prioridad. Datos anteriores siempre visibles.

| Situación | Mensaje | Acción | Efecto |
|---|---|---|---|
| Sin permiso de ubicación | "Sin permiso de ubicación. No se registran viajes." | Conceder (P11) | Sin registro, estado DESCONOCIDO |
| GPS apagado | "GPS apagado. No se registran viajes." | Abrir ajustes de ubicación | Igual que arriba |
| Fecha no confiable | "La hora de la radio parece mal. Avisos por fecha en pausa." | Se corrige sola con GPS | Ítems por fecha sin aviso |
| Buscando señal | "Buscando GPS…" | Ninguna | Registro en espera |
| Odómetro sin cargar | "Cargá el odómetro para avisos por km." | Abrir P11 | Ejes por km SIN DATOS |
| Error al guardar | "No se pudo guardar. Probá de nuevo." | Reintentar | El formulario conserva los datos |
| Registro interrumpido | "Hubo un período sin registro. Los km pueden faltar." | Corregir odómetro (P11) | Se cierra al tocarlo |
| Almacenamiento lleno | "Sin espacio en la radio. El viaje puede perderse." | Ninguna | Copias antiguas borradas según retención |
| Exportación vencida | "Hace mucho que no exportás tus datos." | Abrir P11 | Solo sin movimiento, baja prioridad |

Viaje cortado por apagón: se cierra en el último punto válido, con marca "Cierre estimado".

## 7. Qué aparece al arrancar
1. El registro arranca solo y en silencio al encender la radio, si el permiso ya fue concedido.
2. **Guantera no se abre sola** y nunca tapa radio, música ni cámara de retroceso.
3. La notificación permanente muestra el estado y, si hay, el aviso más urgente, una vez por arranque.
4. Con "Abrir Guantera al arrancar" activo, abre P1 una vez por arranque y solo si no está EN_MOVIMIENTO.
5. Al abrir manualmente: P2 si es EN_MOVIMIENTO; si no, P0 si falta el primer uso; si no, P1.

**Por probar en la radio real:** si la notificación se ve en la interfaz del fabricante y si la radio conserva la hora sin alimentación.

## 8. Decisiones tomadas por el dueño
- **QR de estacionado:** se incluye. La radio queda dentro del auto, así que P10 muestra un QR generado sin red, con un enlace a un mapa que el celular abre con sus propios datos. Se escanea antes de bajar. No es sincronización ni requiere cuenta.
- **Respaldo:** tres niveles, base en la radio, copia automática en la radio y exportación por pendrive. Criterio general: funcionar en el peor escenario, detallado en `resiliencia.md`.
