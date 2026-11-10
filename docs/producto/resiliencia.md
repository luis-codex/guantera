# Guantera v1: funcionar en el peor escenario

Principio del dueño: la app tiene que seguir siendo útil en el peor caso. Todo este documento es **Criterio propio**. Nada se probó todavía en la radio real.

## Reglas de diseño
1. **La energía se puede cortar en cualquier instante.** Ninguna operación puede dejar datos a medias.
2. **Nada depende de internet, de cuentas ni de servicios de Google.**
3. **Nunca se borran datos para resolver un error.** Lo dañado se aparta, no se elimina.
4. **La falla se muestra.** La app nunca finge que registró algo que no registró.
5. **Siempre hay una salida.** Aunque la app esté rota, se puede exportar y restaurar.

## Tres niveles de datos
| Nivel | Dónde | Cuándo | Sobrevive a |
|---|---|---|---|
| 1. Base de datos | Radio, privado de la app | Continuo | Cortes de energía |
| 2. Copia automática | Radio, carpeta pública `Guantera/respaldos` | Al cerrar un viaje y al guardar un formulario | Base dañada, actualización fallida, desinstalación |
| 3. Exportación | Pendrive | Manual, botón en P11 | Restablecimiento de fábrica, radio muerta |

**Formato de las copias:** archivo JSON con versión de esquema, fecha, cantidad de registros y suma de verificación. Es una copia lógica de los datos, para no arrastrar una base dañada y poder leerla en otra radio.

**Escritura segura:** escribir en un temporal, forzar a disco, releer y validar, y recién ahí renombrar. Una copia válida nunca se reemplaza antes de tener la siguiente validada.

**Retención en la radio:** las últimas 7 copias, más la más nueva de cada semana calendario durante 4 semanas, para que un error no descubierto a tiempo no se lleve todas las copias buenas.

## Qué pasa en cada escenario
| Escenario | Respuesta | Pérdida máxima |
|---|---|---|
| Corte de energía manejando | Estado guardado cada 10 s. Al volver, viaje cerrado como "Cierre estimado" | Unos 10 s de viaje |
| Corte durante una escritura | Transacciones de SQLite con WAL y escritura sincronizada | La última operación sin confirmar |
| Corte durante una copia | El temporal se descarta, sigue valiendo la copia anterior | Nada |
| Base de datos dañada | Chequeo de integridad al abrir. Se aparta el archivo dañado, se restaura la última copia válida y se avisa con cuántos datos | Lo posterior a la última copia |
| Sin copia válida y base dañada | Se aparta el archivo y se ofrece importar desde pendrive o empezar de cero, a elección del usuario | Lo posterior a la última exportación |
| Restablecimiento de fábrica | Al primer uso, la app ofrece importar desde pendrive | Lo posterior a la última exportación |
| Radio muerta o cambio de radio | Instalar la app en la nueva e importar | Lo posterior a la última exportación |
| Desinstalar y reinstalar | Si encuentra copias en la radio, ofrece restaurar | Nada o casi nada |
| Actualización con migración fallida | Copia antes de migrar. Nunca migraciones destructivas. Si falla, entra en Recuperación | Nada |
| Almacenamiento lleno | Se borran copias antiguas según retención, nunca la base. Si sigue lleno, banner y el viaje queda en memoria | El viaje en curso si además se corta la energía |
| Pendrive retirado al exportar | Se detecta al validar, mensaje de error, no se marca como exportado | Nada |
| Pendrive dañado o archivo ajeno al importar | Se valida todo antes de tocar nada | Nada |
| Importar una copia de versión más nueva | Se rechaza con un mensaje claro | Nada |
| Importar por error | Antes de reemplazar se crea una copia de lo actual | Nada |
| El sistema mata la app | Servicio persistente. La máquina de estados se recupera de lo guardado | Lo que dure caído |
| El servicio no vuelve tras la suspensión | Al abrir o despertar se detecta el hueco y se muestra "Registro interrumpido" | Km de ese período, corregibles con el odómetro |
| GPS sin señal o roto | Banner. El resto de la app sigue funcionando | Km de ese período, corregibles con el odómetro |
| Hora de la radio incorrecta | Avisos por fecha en pausa, reglas R28 y R29 | Nada |
| La app se cierra sola al abrir, 3 veces seguidas | Abre la pantalla de Recuperación, sin el resto de la app | Nada |
| Sin internet, siempre | Ninguna función lo necesita | Nada |

## Recordatorio de exportación
La exportación es lo único que sobrevive a un restablecimiento, y depende de una persona. Si pasaron más de 30 días desde la última exportación, o nunca se hizo, P1 lo muestra como un banner de baja prioridad y solo con el auto detenido.

## Por probar en la radio real
- Si la carpeta pública sobrevive a una desinstalación.
- Si un restablecimiento borra también esa carpeta.
- Cómo aparece el pendrive para la app y si escribir en él es confiable.
- Cuánto tarda la copia en este almacenamiento.
- Prueba de destrucción: cortar la energía durante un viaje y durante una copia, varias veces.
