# Guantera v1: catálogo de mantenimiento

**Manda el manual del fabricante.** Estos valores son puntos de partida editables, sin asumir marca, modelo ni país. Se aplica lo que ocurra primero (km o tiempo).

Origen de cada valor:
- **V** = Verificado: el valor sale de una fuente que abrí y leí, indicada en la nota. Son fuentes de divulgación o de un fabricante de cubiertas, nunca el manual de un auto concreto.
- **C** = Criterio propio.
- **—** = sin intervalo por defecto en ese eje.

| Ítem | Km | Origen | Tiempo | Origen | Nota |
|---|---|---|---|---|---|
| Aceite y filtro | 10.000 | V [2] | 12 meses | C | [2] da 8.000 a 16.000 km; [1] da 5.000 mi con aceite convencional y hasta 10.000 mi con sintético. Depende del aceite. |
| Rotación de cubiertas | 10.000 | V [2] | 6 meses | V [1][2] | [1] da 6.000 mi (≈ 9.650 km). Algunos autos no admiten rotación cruzada. |
| Alineación y balanceo | 20.000 | C | — | — | |
| Revisión de desgaste de cubiertas y presión | — | — | 1 mes | C | Reemplazo a 1,6 mm de dibujo según [3]. El mínimo legal depende del país. |
| Reemplazo de cubiertas por antigüedad | — | — | 120 meses | V [3] | Se cuenta desde la fabricación: cargar como último service la fecha del código DOT. Desde los 5 años, inspección profesional anual [3]. |
| Filtro de aire de motor | 16.000 | V [1] | — | — | [1] da 10.000 mi; [2] lo ubica en 50.000 km. Los valores difieren. |
| Filtro de habitáculo | — | — | 12 meses | V [1] | Algunos autos no tienen. |
| Líquido de frenos | — | — | 24 meses | V [1][2] | |
| Revisión de frenos (pastillas y discos) | 20.000 | C | 12 meses | C | [1] habla de ≈ 50.000 mi para pastillas; [2] no da intervalo fijo y pide revisar. |
| Refrigerante | — | — | 24 meses | V [2] | Una sola fuente. Los refrigerantes de larga duración pueden durar más: ver manual. |
| Aceite de transmisión | 100.000 | V [2] | — | — | [2]: automática 100.000 km, manual 50.000 a 100.000 km. |
| Bujías | 50.000 | V [2] | — | — | No aplica a motores diésel ni eléctricos. |
| Correa de distribución | 100.000 | V [2] | — | — | [1] da 60.000 a 90.000 mi. **Si el motor usa cadena, borrar el ítem.** Una correa rota puede dañar seriamente el motor: priorizar el manual. |
| Batería (prueba) | — | — | 36 meses | V [1] | [1]: prueba a los 3 años, vida de 3 a 6 años. |
| Escobillas | — | — | 12 meses | C | |
| Seguro | — | — | Fecha fija | — | Sin valor por defecto: fecha de la póliza. |
| Revisión técnica o documentación | — | — | Fecha fija | — | Depende del país: fecha que indique el trámite. |

Conversión usada: 1 mi = 1,609 km, redondeada.

## Comportamiento en la app
- Se cargan en el primer uso, salvo Seguro y Revisión técnica: no tienen valor por defecto y R15 exige una fecha, así que se agregan a mano con la fecha del trámite. Todos se pueden editar o borrar, y se pueden agregar ítems propios.
- Sin último service cargado, el ítem queda SIN DATOS y no avisa: la app no supone cuándo se hizo.
- El uso severo (ciudad, remolque, polvo, viajes cortos) puede acortar los intervalos. [2] reduce el aceite a 5.000 mi en esas condiciones. El manual suele tener su propia tabla.

## Fuentes abiertas y leídas
Leídas con una herramienta que extrae y resume la página. Las cifras no se cotejaron a mano contra el original.
- [1] Nationwide, Car Maintenance Checklist: https://www.nationwide.com/lc/resources/auto-insurance/articles/car-maintenance-checklist
- [2] carVertical, Car maintenance schedule: https://www.carvertical.com/blog/car-maintenance-schedule
- [3] Michelin Canadá, When to replace tires: https://www.michelin.ca/en/auto/learn/tire-buying-guide/when-to-replace-tires

## Fuentes que no pude abrir (no se usan como Verificado)
Devolvieron error o bloqueo: Kelley Blue Book, ConsumerAffairs, CarGurus, Bridgestone. Consumer Reports solo apareció como resultado de búsqueda.
