# Guantera v1: dirección de diseño

Decisiones del dueño: estilo basado en ryoku.dev, todo intuitivo, control de multimedia desde la app y la filosofía de Mercedes. Manda sobre el resto de los documentos cuando haya conflicto visual. La seguridad de `especificacion.md` manda sobre este documento.

## Filosofía: "Das Beste oder nichts" (lo mejor o nada)
1. **Lo que no está terminado no se muestra.** Un botón que no hace nada no existe. Una pantalla a medias no entra.
2. **La seguridad primero.** Ante la duda entre lindo y seguro, gana seguro.
3. **Capa cero (idea de MBUX).** Lo importante ya está en pantalla, sin navegar menús. Inicio muestra lo que importa ahora.
4. **Detalle y sobriedad.** Alineación exacta, un solo sistema de medidas, nada decorativo que compita con el dato.

## Intuitivo: reglas verificables
- Cada pantalla responde una pregunta, escrita con palabras comunes.
- Todo se alcanza con **2 toques o menos** desde Inicio.
- Cada acción es un botón visible con texto. Nada de gestos ocultos ni pulsación larga.
- Ícono siempre acompañado de texto, salvo los controles universales de reproducción.
- Una acción principal por pantalla, la más evidente.
- Toda pantalla vacía dice qué hacer. Todo error dice qué pasó y cómo seguir.
- Nada de jerga técnica: "Datos guardados", nunca "Base de datos" ni "Sync".
- El estado actual se resalta siempre en el riel.

## Lenguaje visual (tomado de ryoku.dev, adaptado al auto)
Se toma el lenguaje, no la marca: **no** usar su nombre, logo, estatua, ilustraciones ni kanjis.

| Elemento | Valor |
|---|---|
| Fondo | `#000000` |
| Superficie elevada | `#0A0A0A` |
| Tinta principal, hueso cálido | `#CDC4BA` |
| Tinta secundaria | `#B0A9A0` |
| Tinta tenue, solo en rótulos | `#958F87` (mínimo contraste 4.5:1 sobre el fondo) |
| Señal, naranja quemado | `#CE5E32`, solo para lo urgente o el elemento activo, nunca decoración |
| Líneas | Tinta al 26 % (normal), 13 % (suave), 42 % (fuerte), 1 dp |
| Radio de esquinas | 2 dp |
| Espaciado | 4, 8, 12, 16, 24, 32, 48, 64 |

**Tipografías** (licencia OFL, empaquetadas en el APK, solo pesos necesarios y subconjunto latino):
- **Fraunces**: títulos de pantalla y cifras protagonistas.
- **Space Grotesk**: texto y botones.
- **Space Mono**: rótulos en mayúsculas con espaciado ancho, y numeración de sección (`01 — VIAJES`).

**Composición:**
- Filetes finos en lugar de sombras y tarjetas rellenas.
- Encabezado de sección: número en mono, rótulo, filete que corre hasta el borde.
- Filas "rótulo a la izquierda, valor a la derecha" unidas por filete.
- Grilla de cifras en celdas con borde fino (cifra grande arriba, rótulo chico abajo).
- Botón primario relleno hueso con texto negro. Secundario solo con borde.
- Grano sutil opcional sobre el fondo, solo si no afecta el rendimiento.

**Adaptación obligatoria al auto:** el sitio usa rótulos de 9 px, en el auto no. Valen los mínimos de `especificacion.md`: zonas táctiles de 96 dp, texto principal 32, secundario 24, rótulos mono no menos de 20, cifras clave 64 o más. Sin animaciones ni transiciones decorativas. Los estados llevan siempre texto, no solo color.

## Espaciado: regla única ("perfect padding")
"Perfect padding" no es una norma: es una práctica de ritmo visual. La regla base es que lo de adentro esté más junto que lo de afuera: **padding interno < separación entre piezas ≤ margen de pantalla**. Criterio propio, salvo donde se indica.

| Elemento | Valor |
|---|---|
| Texto del riel y logo, desde el borde izquierdo | 16 dp |
| Ancho del riel | 216 dp, para que "Mantenimiento" tenga 16 dp también a la derecha |
| Margen del contenido, a izquierda y derecha | 24 dp |
| Margen del contenido, arriba y abajo | 16 dp |
| Padding de tarjeta | 16 dp |
| Separación entre tarjetas | 24 dp |
| Separación entre bloques de una pantalla | 24 dp como mínimo, 32 si hay lugar |
| Padding de banner | 24 dp a los lados, 16 arriba y abajo |
| Padding de botón | Al menos 24 dp a los lados y 12 arriba y abajo, sin bajar nunca el tamaño táctil mínimo |
| Separación entre botones en P2 | 24 dp (guía de Android Automotive) |
| Separación entre botones con el auto detenido | 16 dp |
| Elementos dentro de una tarjeta | Esquinas rectas: radio interno = radio externo − padding, y con 2 dp da 0 |

**Alineación óptica:** `android:includeFontPadding="false"` en todo el texto, para que el padding visible sea igual arriba y abajo. Todas las columnas alinean a las mismas guías: el borde de un bloque coincide con el del bloque de arriba y el de abajo. Solo valores de la escala, ningún número suelto en un layout.

## Multimedia
Nueva pantalla **P13. Música** ("¿qué está sonando?") en el riel, y franja "Sonando" en Inicio.
- Contenido: título, artista, app de origen, estado.
- Acciones: anterior, reproducir o pausar, siguiente, bajar y subir volumen, silenciar.
- **Es la excepción permitida en P2 (conducción):** anterior, reproducir o pausar, siguiente y volumen, en botones grandes. Sin listas, sin búsqueda, sin texto para escribir ni que se desplace solo.
- Si no se pueden leer los datos de lo que suena, los controles siguen funcionando y la pantalla lo dice: "Controles activos. Para ver qué suena, habilitá el acceso a notificaciones", con el botón que lleva a ese ajuste.
- La radio FM del fabricante puede no responder a estos controles. No se sabe hasta probar en la radio real, y no se promete en la interfaz.

## Datos de ejemplo
Esta etapa muestra datos de ejemplo. Debe decirlo siempre, con un rótulo fijo `DATOS DE EJEMPLO` en la barra superior.
