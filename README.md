# Guantera Sonda

App Android de diagnóstico para comprobar versión/hardware, GPS y continuidad de un servicio al cortar y devolver contacto. No usa red ni Google Play Services como dependencia.

## Compilar

Requiere JDK 17 y Android SDK con plataformas 28+ y Build Tools. En este equipo el JDK y SDK están instalados en `~/.local/share/jdks/temurin-17` y `~/Android/Sdk`; `local.properties` ya apunta al SDK.

```sh
./gradlew test assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`; `assembleDebug` lo copia solo a `dist/guantera-sonda-debug.apk`.

El proyecto tiene tres módulos (`:domain`, `:data`, `:app`); ver `docs/ARCHITECTURE.md`. Los tests del dominio corren con `./gradlew :domain:test`.

## Instalar y probar en el auto

Copiá el APK a un pendrive, conectalo al estéreo y abrí el archivo desde el administrador de archivos. Aceptá la instalación desde esa fuente y el permiso de ubicación precisa. Encendé el auto y tocá **Iniciar**; probá el GPS afuera con cielo abierto. Apagá el contacto unos minutos, volvé a darlo y revisá si el servicio se reanuda y aparece el evento de suspensión. Tocá **Exportar** y buscá el informe en Descargas; compartilo o copialo desde el equipo.

## Descargar los APK
Cada cambio en `main` se prueba y se compila solo. Los APK quedan en la release **Última compilación** del repositorio.
- `guantera-debug.apk`: la interfaz de Guantera con datos de ejemplo. Todavía no registra datos reales.
- `guantera-sonda-debug.apk`: la prueba de diagnóstico para la radio, ver `docs/prueba-en-auto.md`.

Las dos versiones usan una firma de desarrollo fija (`keystore/`), para poder actualizar sin desinstalar. No sirve para publicar en una tienda.
