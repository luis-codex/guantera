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

## Ícono y texto
Encaja con la regla de espaciado: el hueco ícono-texto es la medida más chica del sistema, menor que cualquier padding de tarjeta o botón. Solo escala 4, 8, 16; tokens en `res/values/dimens_icon.xml`.

**Regla:** el ícono mide 4/3 del texto que lo acompaña (32 dp junto a texto de 24 sp, que es la altura de su línea) y el hueco visible entre ambos es ≈ 0,5 em en línea y ≈ 0,35 em apilado. Como la caja del ícono Lucide (viewBox 24, trazo 2) deja de 1 a 3 unidades de aire (1,3 a 4 dp) dentro, el token mide la caja y no la tinta: se resta ese aire.

| Contexto | Token | Valor |
|---|---|---|
| Ícono y rótulo en la misma línea: riel, botones de vista (Lista, Calendario) | `icon_gap_inline` | 8 dp |
| Ícono arriba del rótulo de 20 sp: categorías de gasto | `icon_gap_stacked` | 4 dp |
| Ícono que marca una fila de dos líneas: registros de Gastos y Viajes | `icon_gap_leading` | 16 dp |
| Encabezados, avisos, diálogos, primer uso, recuperación, música | sin íconos junto a texto, no aplica | — |

**Alineación vertical:** con `includeFontPadding="false"`, la caja de línea de Space Grotesk (ascenso 0,984 em, descenso 0,292 em) tiene su centro a 0,638 em del borde y el centro de las mayúsculas (altura 0,700 em) a 0,634 em: difieren 0,1 dp a 24 sp. Centrar el ícono en la línea equivale a centrarlo en las mayúsculas, sin desplazamientos manuales. No se centra en la altura de las minúsculas (0,486 em), que queda 2,5 dp más abajo. Los íconos no se corrigen uno por uno por su aire interno. Las zonas táctiles no cambian (96 dp).

**Fuentes** (consultadas al redactar; lo no verificado se marca):
- Material 3, botones con ícono: ícono de 18 dp y 8 dp de separación (relación 1,29 con texto de 14 sp). Verificado solo en fuentes secundarias (docs de Expo sobre Compose, resumen de `ButtonDefaults`); falta confirmar en m3.material.io.
- IBM Carbon, [estilo de botón](https://carbondesignsystem.com/components/button/style): ícono de 16 px (20 expresivo); hueco de 8 px en botón fantasma con ícono. Pide al menos 16 px entre rótulo e ícono solo en botones con el ícono al extremo, caso que Guantera no usa.
- Atlassian, [espaciado](https://atlassian.design/foundations/spacing): de 0 a 8 px para piezas pequeñas y compactas, ícono estándar de 16 px.
- Lucide, [guía de diseño de íconos](https://lucide.dev/guide/design/icon-design-guide): trazo de 2 px, al menos 1 px de aire contra el borde del lienzo de 24.
- Tonsky, [Centering Things](https://tonsky.me/blog/centering/): el ícono junto a texto se alinea a las mayúsculas, no a las minúsculas.
- Apple HIG y Polaris: no se obtuvo texto citable (la página de HIG no cargó; Polaris no publica valor), no se usan como respaldo.
- Los factores 0,5 y 0,35 em y el 4/3 son criterio propio, derivados de los valores de Material y Carbon, calibrados a la pantalla de 1280x720.

## Multimedia
Nueva pantalla **P13. Música** ("¿qué está sonando?") en el riel, y franja "Sonando" en Inicio.
- Contenido: título, artista, app de origen, estado.
- Acciones: anterior, reproducir o pausar, siguiente, bajar y subir volumen, silenciar.
- **Es la excepción permitida en P2 (conducción):** anterior, reproducir o pausar, siguiente y volumen, en botones grandes. Sin listas, sin búsqueda, sin texto para escribir ni que se desplace solo.
- Si no se pueden leer los datos de lo que suena, los controles siguen funcionando y la pantalla lo dice: "Controles activos. Para ver qué suena, habilitá el acceso a notificaciones", con el botón que lleva a ese ajuste.
- La radio FM del fabricante puede no responder a estos controles. No se sabe hasta probar en la radio real, y no se promete en la interfaz.
