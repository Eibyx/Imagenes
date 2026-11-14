# MTZ Forge

App Android independiente en Kotlin (sin AndroidX ni dependencias de terceros en tiempo de ejecución) que:

- convierte fuentes **TTF / OTF / TTC → MTZ** (formato de temas de Xiaomi MIUI/HyperOS);
- convierte **packs de iconos APK** (formato `appfilter.xml`), instalados o desde un fichero APK, **→ MTZ**;
- exporta el resultado a **Descargas/MTZ Forge/**;
- abre la app **Temas de Xiaomi** e incluye un botón *experimental* para pedirle que aplique el MTZ.

La implementación es original. El formato se dedujo analizando zFont 3. El informe completo, con lo verificado y lo que no, está en [`docs/INGENIERIA_INVERSA.md`](docs/INGENIERIA_INVERSA.md).

> ⚠️ No se ha probado en un dispositivo Xiaomi real. La lista de comprobaciones pendientes está en la sección 7 del informe.

## Estructura
```
core/            Kotlin/JVM puro: parser SFNT, description.xml, escritor/inspector MTZ,
                 appfilter → iconos MIUI, CLI de escritorio. Tests JUnit.
app/             App Android (minSdk 26, targetSdk 35). Tests Robolectric (Canvas nativo, intents).
sample-iconpack/ Pack de iconos de prueba con diseños propios (para probar la conversión).
samples/         MTZ y vistas previas generados por los tests.
dist/            APKs de depuración ya compilados.
tools/           Generador de fuentes de prueba (fontTools).
```

## Compilar y probar
```sh
# Requiere JDK 17+ y Android SDK (platform 35, build-tools 35) en local.properties (sdk.dir=...)
./gradlew :core:test :app:testDebugUnitTest      # 21 tests
./gradlew :app:assembleDebug :sample-iconpack:assembleDebug
```

CLI de escritorio (sin vistas previas):
```sh
./gradlew :core:installDist
core/build/install/core/bin/core font MiFuente.ttf MiFuente.mtz "Mi fuente"
core/build/install/core/bin/core icons appfilter.xml carpeta_png/ iconos.mtz "Mis iconos"
core/build/install/core/bin/core inspect MiFuente.mtz
```

## Formato generado
```
description.xml
fonts/Roboto-Regular.ttf            (MTZ de fuente)
icons                               (MTZ de iconos: ZIP interno con res/drawable-xxhdpi/<paquete>.png)
preview/preview_fonts_small_0.png | preview/preview_fonts_0.jpg | preview/preview_icons_0.png
```
