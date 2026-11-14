# Análisis de zFont 3 (3.9.2.2): de TTF/OTF a MTZ y aplicación en Temas de Xiaomi

Este documento recoge **lo que se extrajo del APK** y **con qué grado de certeza**.
Leyenda que se usa en todo el texto:

- ✅ **Verificado**: se ve directamente en el código descompilado o en los recursos, o lo comprueban los tests de este repositorio.
- 🟡 **Inferido**: deducción razonable a partir del código, pero el flujo no se ha seguido de principio a fin (el código está ofuscado con R8).
- ❓ **Sin validar**: hace falta un dispositivo Xiaomi/HyperOS real.

No se ha ejecutado zFont ni la app propia en un dispositivo físico ni en un emulador (el contenedor no tiene KVM).

---

## 1. Material y herramientas

| Elemento | Detalle |
|---|---|
| Fichero recibido | `zFont3-3.9.2.2.apkm` (paquete APKMirror = ZIP de *split APKs*) |
| Contenido | `base.apk` (29 MB), `config.arm64_v8a.apk`, `config.es.apk`, `config.xxhdpi.apk` |
| Paquete | `com.htetznaing.zfont2`, versionCode 93, minSdk 23, targetSdk 36 |
| Herramientas | Apktool 2.10.0 (recursos/manifiesto), JADX 1.5.1 (Java), aapt2/apksigner (build-tools 35), Python + fontTools (fuentes de prueba) |

JADX descompiló 16 260 clases y falló en 93 métodos, ninguno de ellos relevante para MTZ. El código propio está ofuscado con R8 (paquetes `am`, `vi`, `jd`, `n1`, `ui`, `wi`, `yl`…). Solo se conservan algunos nombres: `com.htetz.zfontcompose.MainActivity`, `NativeLib`, `ZFontDocumentsProvider` y `service.VivoWatcherService`. La interfaz está hecha con Jetpack Compose.

### Componentes nativos y empaquetados
- `lib/arm64-v8a/libzfont3.so` contiene las cadenas `bin/mt/signature/KillerApplication` y `sharkfall/inc/signkiller/SignKillerApp`, que corresponden a detección de herramientas que eliminan la firma (anti-manipulación), y la clase `NativeLib` (`createToken`, `validateToken`, `m28…m37`). ✅ **No participa en la creación del MTZ.** Se usa para autenticar las peticiones al servidor de zFont (ver método 4).
- `assets/main.jar` es un DEX con `com.topjohnwu.superuser.internal.RootServerMain` (libsu): el servidor para el modo **Root**. ✅
- `assets/source/xiaomi/one` y `assets/source/xiaomi/three/*` son blobs comprimidos con zlib: un objeto de derechos OMA-DRM firmado y componentes base de tema MIUI (ver §4). ✅

---

## 2. Manifiesto: permisos e integración

✅ zFont **no declara ningún permiso especial de Xiaomi**. Los permisos relevantes son:

- `WRITE_EXTERNAL_STORAGE` (maxSdk 29) y `READ_EXTERNAL_STORAGE` (maxSdk 32).
- `moe.shizuku.manager.permission.API_V23`. Además declara `rikka.shizuku.ShizukuProvider` (authority `com.htetznaing.zfont2.shizuku`), que es el patrón estándar de Shizuku.
- `com.htetz.file.PERMISSION_ACCESS`: permiso de su app auxiliar **zFile** (`com.htetznaing.zfile`).
- `REQUEST_DELETE_PACKAGES` y `KILL_BACKGROUND_PROCESSES`, que usa en los flujos de otras marcas.

`<queries>` incluye `moe.shizuku.privileged.api`, `com.htetznaing.zfile` y los gestores de temas de Huawei, Honor, OPPO, vivo y Transsion. **No incluye `com.android.thememanager`**: no hace falta, porque `startActivity` con un componente explícito no está sujeto al filtrado de visibilidad de paquetes. ✅

zFont **no usa ningún ContentProvider de la app Temas**. Solo expone su propio `ZFontDocumentsProvider`, que es un DocumentsProvider sobre sus carpetas `zFont`. ✅

---

## 3. Formato MTZ reconstruido

### 3.1 Contenedor
✅ Un MTZ es un **ZIP estándar**. Lo empaqueta `n1.a.X(dir, file)`, que recorre el árbol con `ZipOutputStream` (DEFLATE por defecto) y **añade entradas de directorio explícitas** (`fonts/`, `preview/`). No hay cifrado, firma ni cabecera propia.

### 3.2 MTZ de fuente creado por zFont (`am.d2.j`)
✅ Estructura:
```
description.xml
fonts/Roboto-Regular.ttf          ← la fuente del usuario, sin modificar (solo renombrada)
preview/preview_fonts_small_0.png ← 435×70, texto negro sobre transparente
preview/preview_fonts_0.jpg       ← 720×1280, fondo #00A052, logo y nombre de la fuente
```
- La fuente **no se convierte**: TTF y OTF se copian byte a byte con el nombre `Roboto-Regular.ttf`. ✅
- zFont no extrae fuentes de una colección TTC; MTZ Forge sí lo hace (§6).

### 3.3 `description.xml`
✅ Lo genera `wi.l.a()`. Valores fijos en zFont: `version` = versión de la app, `uiVersion` = **8**, `author`/`designer` = "zFont 3".
```xml
<theme>
  <version>…</version> <uiVersion>8</uiVersion>
  <author>…</author> <designer>…</designer> <title>…</title> <description>…</description>
  <authors><author locale="en_US">…</author><author locale="zh_CN">…</author></authors>
  <designers>…</designers> <titles>…</titles> <descriptions>…</descriptions>
</theme>
```
Todos los textos van en CDATA. ❓ No se ha comprobado si otras versiones de HyperOS exigen un `uiVersion` mayor; en MTZ Forge es configurable (por defecto 8).

### 3.4 Tabla de componentes ("ruta en el MTZ" → código de recurso)
✅ La tabla está en la clase `ui.a`. Se usa al convertir un MTZ al almacén interno de Temas:

| Ruta en el MTZ | resourceCode |
|---|---|
| `fonts/Roboto-Regular.ttf` | `fonts` |
| `fonts/DroidSansFallback.ttf` | `fonts_fallback` |
| `icons` (sin extensión) | `icons` |
| `com.miui.home` | `launcher` |
| `com.android.systemui` | `statusbar` |
| `framework-res` | `framework` |
| `com.android.contacts` / `com.android.mms` | `contact` / `mms` |
| `lockscreen` | `lockstyle` |
| `wallpaper/default_wallpaper.jpg` / `default_lock_wallpaper.jpg` | `wallpaper` / `lockscreen` |
| `ringtones/*.mp3`, `boots/bootanimation.zip` | `ringtone`, `notification`, `alarm`, `bootanimation` |

### 3.5 Componente `icons`
✅ Estructura sacada del asset real `source/xiaomi/three/icons`, que es un recurso de iconos MIUI completo. Solo se analizó su estructura; su contenido no se reutiliza.
- Es un **ZIP dentro del MTZ**, sin extensión.
- `res/drawable-xxhdpi/<paquete>.png` (por ejemplo `com.android.browser.png`). Si la entrada corresponde a una actividad concreta, el fichero lleva el nombre completo de la clase: `com.android.contacts.activities.TwelveKeyDialer.png`.
- PNG de **168×168** en ese ejemplo. También existe la carpeta `res/drawable-nxhdpi/`.
- Ficheros auxiliares: `icon_mask.png`, `icon_folder*.png`, `transform_config.xml` (`<IconTransform><PointsMapping>…`) y `fancy_icons/<pkg>/manifest.xml`, que son iconos animados en formato MAML (calendario, reloj).

### 3.6 Almacén interno de Temas (`.data`)
✅ Ruta: `Android/data/com.android.thememanager/files/MIUI/theme/.data` en API 30 o superior; `MIUI/theme/.data` en API 29 o inferior.
```
content/<resourceCode>/<id>.mrc   ← contenido (la fuente, el zip de iconos…)
meta/<resourceCode>/<id>.mrm      ← JSON: localId, onlineId, hash(SHA-1), platform, size, updatedTime,
                                     version, authors/designers/titles/descriptions{fallback},
                                     builtInThumbnails, builtInPreviews, parentResources, subResources,
                                     extraMeta, metaPath, contentPath, rightsPath, …
preview/<resourceCode>/<id>/…     ← imágenes de vista previa
rights/<resourceCode>/<hash>.mra  ← objeto de derechos OMA-DRM (XML ODRL firmado)
```

---

## 4. Los cuatro métodos de zFont para Xiaomi (`ui.b`: FONT, THEME, MTZ, OFFICIAL)

Todos parten del mismo MTZ (§3.2) y después divergen (`am.d2`):

| # | Nombre en la interfaz | Qué hace | ¿Reimplementado? |
|---|---|---|---|
| 1 | "Apply using font" (`jd.s.g`) | ✅ Convierte el MTZ al almacén `.data`: `content/fonts/<uuid>.mrc` más un `content/theme/<uuid>.mrc` vacío, los JSON `.mrm` de fuente y de tema, y **`rights/theme/3a086aa2….mra`, copiado de un objeto de derechos firmado incluido en el APK** (`assets/source/xiaomi/one`). Después lo copia a la carpeta privada de Temas con el *file handler* elegido. | ❌ **No.** Reutilizar una licencia DRM firmada de terceros para que Temas acepte contenido es eludir su control de derechos. |
| 2 | "Apply using MTZ package" | ✅ Escribe `zfont.mtz` en un directorio que le da su *file handler* (`uh.a`) y luego lanza `com.android.thememanager/.ApplyThemeForScreenshot` con `theme_file_path=<ruta>` y `api_called_from=theme`. Mensaje en la interfaz: *"Tap Apply Font… Manually reboot your phone"*. 🟡 El directorio parece ser el `.data` de Temas, porque `bi.e.j(XIAOMI)` devuelve esa ruta; no se ha seguido el flujo completo. | ✅ **Sí**, en modo experimental, con la ruta del MTZ exportado en Descargas (❓ ver §7). |
| 3 | "Apply using theme" | ✅ Crea un **tema completo** con componentes base incluidos en el APK (`three/`: icons, launcher, lockscreen, wallpaper…) y la fuente; genera `.mra` por componente y lo copia a `.data`. En la interfaz aparece como "(Apply me) <nombre>". | ❌ No, por la misma razón que el método 1. |
| 4 | "Apply with new method" (`vi.a`) | ✅ Convierte un MTZ cualquiera al formato `.data`. ID = `UUID.nameUUIDFromBytes(título)`. La licencia `.mra` se pide **al servidor de zFont** (`fm.e`) con un token generado en código nativo (`NativeLib.createToken`). | ❌ No: depende de un servicio privado de licencias. |

### Cómo abre zFont la app Temas (✅ en `n1.a` y `a1.g`)
- Abrir un recurso local: `Intent(VIEW, "ViewLocalResource://view.local.resource#<id>")`, paquete `com.android.thememanager`, extras `REQUEST_RESOURCE_CODE` = `fonts`/`theme` y `REQUEST_APPLY_EVENT=true`.
- Pestaña de fuentes o temas: `com.android.thememanager.activity.ThemeTabActivity` con `REQUEST_RESOURCE_CODE`.
- Alternativas: `ThemeSettingsActivity`, `com.android.settings/.Settings$FontSettingsActivity` y `Settings.ACTION_DISPLAY_SETTINGS`.
- Flags: `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` (0x10008000).

---

## 5. ¿Cómo funciona sin root?

✅ zFont tiene cinco *file handlers* (`uh.b`): **LEGACY, SAF, SHIZUKU, ZFILE, ROOT**. Lo que obliga a usarlos es escribir en `Android/data/com.android.thememanager/…`, una carpeta que Android 11 o superior bloquea para otras apps.

- **Shizuku**: se usa mediante binder directo (`moe.shizuku.server.IShizukuService`, `IRemoteProcess`). Lanza procesos `sh` con el uid de shell (ADB), que **sí puede escribir en `Android/data`**. Es lo que la propia app recomienda para Android 11 o superior. ✅
- **ADB manual**: el texto `adb_req_msg` ofrece ejecutar un comando desde un PC, Brevent o "ADB OTG". ✅
- **SAF**: permisos persistentes sobre carpetas de `Android/data`. Algunas ROMs o versiones de Android lo permiten; en Android 13 o superior está bloqueado en la mayoría. 🟡
- **Truco de ruta `"​Android/"`**: sustituye `Android/` por un espacio de ancho cero seguido de `Android/` (7 apariciones, p. ej. en `bm.g` y `bi.e`). 🟡 Parece explotar diferencias de normalización de rutas en algunas compilaciones para esquivar el bloqueo. ❓ No se ha probado y es probable que esté corregido en parches de seguridad recientes.
- **zFile**: app auxiliar del mismo autor que hace las escrituras. **Root** usa libsu. ✅
- Para vivo usa un *path traversal* hacia el gestor de ficheros del sistema (`com.android.filemanager.FILE_OPEN` con `/storage/emulated/0/../../..`). Esto no afecta a Xiaomi. ✅

**Conclusión:** de los métodos para Xiaomi, el **método 2** es el único cuya parte de aplicación es solo un *intent* explícito, sin permisos especiales. Aun así, zFont escribe el fichero mediante su *file handler*. Los métodos 1, 3 y 4 necesitan escribir en la carpeta privada de Temas (Shizuku/ADB/root) **y además** un `.mra` válido.

---

## 6. Qué implementa MTZ Forge (código propio)

| Función | Estado |
|---|---|
| Validación SFNT propia (TTF, OTF-CFF, CFF2, TTC; rechaza WOFF y ficheros corruptos), lectura de nombres desde la tabla `name` | ✅ Tests JVM |
| Extracción de una fuente de una colección TTC a SFNT independiente, recalculando `checkSumAdjustment` | ✅ Test: suma = 0xB1B0AFBA; fontTools la abre |
| `description.xml` propio (CDATA escapado, locales en/zh/es, `uiVersion` configurable) | ✅ Test de ida y vuelta |
| MTZ de fuente con la estructura de §3.2 y vistas previas de diseño propio | ✅ Tests JVM y Robolectric (Canvas nativo) |
| Pack de iconos APK → MTZ: `appfilter.xml` binario (`res/xml`) o de texto (`assets`), mapeo a `<pkg>.png` y `<Actividad>.png`, PNG renderizados a 168 px, `iconback/mask/upon` → `icon_background/mask/border.png` | ✅ Estructura (tests). ❓ Aceptación por HyperOS |
| Inspector de MTZ: vuelve a leer cada MTZ generado antes de exportarlo | ✅ |
| Exportación a `Descargas/MTZ Forge/` (MediaStore en API 29+, ruta directa en API 26–28) | 🟡 Compila; ❓ falta probarlo en un dispositivo |
| Botón "Abrir Temas de Xiaomi" (ThemeTabActivity → lanzador → ajustes) | ✅ Intent probado con Robolectric; ❓ en dispositivo |
| Botón "Aplicar con Temas (experimental)" (ApplyThemeForScreenshot) | ✅ Intent probado con Robolectric; ❓ en dispositivo |

Lo que **no** se implementa a propósito: copiar ficheros `.mra` o licencias, peticiones al servidor de zFont, Shizuku/root ni escritura en `Android/data` de otras apps.

---

## 7. Qué hay que probar en un dispositivo Xiaomi (lista de comprobación)

1. ❓ Instalar `dist/MtzForge-0.1.0-debug.apk` y convertir una fuente TTF. Probar a importarla manualmente: abrir el `.mtz` desde el gestor de archivos con Temas, o usar la opción de importar de Temas si tu versión la tiene (❓ la ruta exacta del menú no se ha verificado). Esta vía no depende de intents internos.
2. ❓ Botón **"Aplicar con Temas (experimental)"**: comprobar si `ApplyThemeForScreenshot` existe, si está exportada en tu versión y si **acepta una ruta en `Download/`**. zFont probablemente le pasa una ruta dentro de `.data` (§4, método 2). Si Temas rechaza la ruta, la vía sin root disponible es la importación manual del punto 1.
3. ❓ Tras aplicar, puede hacer falta **reiniciar** (zFont también lo indica).
4. ❓ Comprobar si HyperOS exige `uiVersion` > 8 o una firma para fuentes de terceros. Algunas regiones, como la global, restringen las fuentes no oficiales.
5. ❓ Iconos: instalar `dist/ForgeSampleIcons-debug.apk` y convertirlo con **"Pack instalado…"**, o elegir el APK directamente. Comprobar si Temas muestra el MTZ de iconos y si respeta `res/drawable-xxhdpi/<pkg>.png` y el tamaño de 168 px. Probar también con `activityIcons` desactivado.
6. ❓ Cargar el APK desde un fichero (`getPackageArchiveInfo` + `getResourcesForApplication`) con packs grandes (más de 5000 iconos): tiempo y memoria (los PNG se escriben en *streaming* a disco).

Comandos ADB útiles para diagnosticar (opcionales):
```sh
adb shell cmd package resolve-activity --components -a android.intent.action.VIEW \
    -n com.android.thememanager/.ApplyThemeForScreenshot
adb shell dumpsys package com.android.thememanager | grep -A3 ApplyThemeForScreenshot
adb logcat | grep -i -E "thememanager|ApplyTheme"
```

---

## 8. Ficheros de prueba generados

- `tools/make_test_fonts.py`: genera fuentes **propias** (TTF, OTF-CFF, TTC con dos fuentes y un fichero corrupto) con fontTools.
- `samples/ForgeTestSans.mtz` (sin vistas previas) y `samples/ForgeTestSans-con-previews.mtz` (con vistas previas renderizadas por el Canvas nativo de Android en Robolectric).
- `samples/ForgeSampleIcons.mtz`: 19 iconos PNG reales de 168×168 generados con el `appfilter.xml` del pack de muestra.
- `sample-iconpack/`: APK de pack de iconos de prueba (`dist/ForgeSampleIcons-debug.apk`). Incluye a propósito una entrada inválida y un drawable inexistente.
