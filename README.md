# Socram TV+

App Android nativa (Kotlin) que lee una playlist en JSON y reproduce cada
canal con **ExoPlayer / Media3**, con soporte para **DASH (.mpd)**,
**HLS (.m3u8)** y vídeo progresivo (mp4, etc.), cabeceras HTTP propias y
DRM **ClearKey** opcional. También reproduce **radio (audio)**, mostrando
el logo y "lo que se está escuchando", sigue sonando en segundo plano y
con la pantalla bloqueada, y admite **guía EPG** (qué programa toca ahora)
si la lista la trae (ver más abajo).

> Pensada para tu propio contenido: streams propios, autohospedados, o
> cualquier servicio para el que tengas licencia/autorización de uso.

## Abrir el proyecto

1. Android Studio (versión reciente) + JDK 17.
2. `File > Open` y selecciona esta carpeta.
3. Deja que Gradle sincronice (descargará AGP 8.13.2, Kotlin 2.2.21 y las
   librerías de Media3 1.10.1 — hace falta conexión a internet la primera vez).
4. Ejecuta en un emulador o dispositivo (minSdk 26 / Android 8.0+).

Las versiones de las dependencias (`app/build.gradle.kts`) son las
estables más recientes al escribir esto; si Android Studio te sugiere
actualizarlas, puedes aceptar tranquilamente.

## Esquema del JSON

Se acepta el formato propio:

```json
{
  "epgUrl": "https://tu-servidor/guia.xml.gz",
  "categories": [
    {
      "name": "Mis canales",
      "streams": [
        {
          "name": "Canal demo DASH",
          "type": "DASH",
          "url": "https://tu-servidor/manifest.mpd",
          "icon": "https://tu-servidor/logo.png",
          "headers": { "Authorization": "Bearer xxx" },
          "drm": { "keyId": "base64url...", "key": "base64url..." },
          "tvgId": "canal.demo"
        },
        {
          "name": "Canal demo HLS",
          "type": "HLS",
          "url": "https://tu-servidor/index.m3u8"
        }
      ]
    }
  ]
}
```

...y también una raíz en forma de array, con `samples` como alias de
`streams` y otro grupo de nombres de campo (estilo "exolist"):

```json
[
  {
    "name": "Mis canales",
    "samples": [
      {
        "name": "Canal demo",
        "uri": "https://tu-servidor/manifest.mpd",
        "extension": "mpd",
        "image": "https://tu-servidor/logo.png",
        "kid": "hex o base64url...",
        "key": "hex o base64url...",
        "drm_scheme": "clearkey",
        "headers": { "User-Agent": "..." },
        "token": "https://tu-servidor/generar-token"
      }
    ]
  }
]
```

Alias aceptados por campo: `url`/`uri`, `icon`/`image`/`icono`, `type`/`extension`,
`tvgId`/`tvg_id`/`tvg-id`/`epgId` (identificador EPG del canal), y
`epgUrl`/`epg_url`/`url-tvg`/`xmltv` a nivel de raíz (guía EPG de toda la
lista; solo con la raíz en forma de objeto, no en la raíz en array).
Para DRM ClearKey se acepta cualquiera de estas tres formas: `drm: {keyId,
key}`, `kid`+`key` sueltos, o `license_key` con el JSON de ClearKey ya
armado (`{"keys":[{"kty":"oct",...}],"type":"temporary"}`). `kid`/`key`
pueden venir en base64url o en hexadecimal — se normalizan solos.

- `type`/`extension`: `"DASH"`/`"mpd"`, `"HLS"`/`"m3u8"`, o cualquier otro
  valor → se trata como progresivo (URL directa a un archivo de vídeo o
  audio).
- `icon`, `headers`, `drm`/`kid`+`key`/`license_key` y `token` son
  opcionales.
- `token`: si la URL del canal contiene el texto `{token}`, antes de
  reproducir se hace una petición a esa URL (con las mismas `headers` del
  canal) y se sustituye por el texto que devuelva.
- Un `drm_scheme` que no sea `"clearkey"` (p. ej. `"widevine"`) se ignora:
  esos esquemas necesitan servidor de licencias propio y no están
  implementados; el canal se intenta reproducir sin descifrar.
- Estos campos son solo para contenido tuyo o licenciado — no es un
  mecanismo para saltarse la protección de contenido de terceros.

## Listas M3U

También se acepta M3U/M3U8 extendido (detecta el formato solo, por si
empieza con `#EXTM3U`):

```
#EXTM3U url-tvg="https://tu-servidor/guia.xml.gz"
#EXTINF:-1 tvg-id="canal.demo" tvg-name="Canal" tvg-logo="https://.../logo.png" group-title="Categoría",Nombre para mostrar
https://tu-servidor/stream
```

`group-title` se usa como categoría, `tvg-logo` como icono, y `tvg-name`
(o el texto tras la coma si falta) como nombre. El tipo de cada canal se
adivina por la extensión de la URL (`.m3u8` → HLS, `.mpd` → DASH; si no,
progresivo). `url-tvg` (o `x-tvg-url`) en la cabecera `#EXTM3U` y `tvg-id`
en cada canal son para la guía EPG (ver más abajo). Este formato no
admite cabeceras, DRM ni token — para eso usa el JSON.

Desde la app, usa el icono de carpeta (barra superior) para elegir un
archivo JSON o M3U con cualquiera de estos formatos desde tu dispositivo. La
app recuerda el último archivo cargado y lo reabre al iniciar; si no hay
ninguno, carga una lista de ejemplo (`app/src/main/assets/sample_playlist.json`)
con dos streams públicos de prueba (Big Buck Bunny en DASH y el stream de
ejemplo de Apple en HLS).

## Listas públicas tipo tdtchannels.com

También se reconoce solo —mirando si el JSON trae una clave `"countries"`
en la raíz, sin que haga falta indicar nada— el formato de listas
públicas como las de [tdtchannels.com](https://www.tdtchannels.com/):

```json
{
  "epg": { "json": "https://www.tdtchannels.com/epg/example.json" },
  "countries": [
    {
      "name": "España",
      "ambits": [
        {
          "name": "Nacional",
          "channels": [
            {
              "name": "Canal demo",
              "logo": "https://tu-servidor/logo.png",
              "epg_id": "canal.demo",
              "referer": "https://tu-servidor/",
              "options": [
                { "format": "hls", "url": "https://tu-servidor/index.m3u8" }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

De cada canal se recorren las `options` en orden y se usa la primera que
la app pueda reproducir directamente (HLS/DASH/progresivo); si un canal
solo trae opciones de YouTube (`"format": "youtube"`), se usa esa —no hay
otra— pero se abre con la app de YouTube (o el navegador si no está
instalada) en vez de intentar reproducirla dentro de Socram TV+, porque un
enlace de YouTube no es un stream directo que ExoPlayer entienda. `format`
decide si el canal se trata como HLS, DASH, progresivo o YouTube, igual
que `type`/`extension` decide en el formato propio. `ambits` se usa como
categoría (agrupando por país solo si la lista trae más de uno). Si el
canal trae `referer`, se manda como cabecera HTTP `Referer` en cada
petición al stream —algunos servidores la exigen tal cual y rechazan la
conexión sin ella—. Esto es genérico —se detecta por la forma del JSON,
no por la URL—, así que debería funcionar igual con cualquier otra lista
de tdtchannels.com que comparta este mismo esquema (por ejemplo sus listas
de televisión, no solo la de radio, que es donde además aparecen más a
menudo `referer` y canales de YouTube).

Su guía EPG (`epg.json` en la raíz) viene en un formato JSON propio, no
XMLTV — ver la sección EPG más abajo, donde se explican los dos formatos
que se admiten.

## Listas remotas (Lista 1 / 2 / 3)

En la portada hay tres botones para cargar una lista directamente desde
una URL (por ejemplo, un enlace "raw" de un archivo en GitHub), en vez de
tener que elegir un archivo local cada vez. La primera vez que tocas uno
te pide la URL; a partir de ahí, tocarlo vuelve a descargar y leer esa
misma URL (para recoger cambios que hayas hecho en el archivo remoto).
Mantén pulsado el botón para cambiar la URL guardada en ese hueco. Acepta
tanto JSON como M3U, igual que la carga desde archivo.

## Radio, "ahora suena" y reproducción en segundo plano

No hace falta marcar nada especial en el JSON/M3U para que un canal se
trate como radio: al reproducirlo, la app mira las pistas reales del
stream y, si no trae vídeo, muestra automáticamente el logo del canal y
una pantalla de "ahora suena" en vez del hueco negro del vídeo.

- Si el propio stream envía metadatos ICY/ID3 (el "título de la canción
  actual" que emiten muchas radios por Internet), se muestra ahí y se
  actualiza solo según van cambiando; si no, se queda con el nombre fijo
  del canal. La app pide ese metadato con la cabecera `Icy-MetaData: 1` en
  cada petición, pero es la propia emisora quien decide si lo manda o no:
  algunas no emiten nunca un "ahora suena", y ahí solo se puede mostrar el
  nombre fijo del canal.
- Mientras esa pantalla de radio esté activa, bloquear el teléfono **no
  corta la reproducción**: sigue sonando y aparecen los controles de
  reproducción (play/pausa) en la pantalla de bloqueo y en una
  notificación, con el logo y el nombre del canal. Volver a abrir la app
  y salir del reproductor (botón atrás) sí para la radio.
- Para un canal de vídeo/TV normal, el comportamiento no cambia: al
  bloquear el teléfono o cambiar de app se pausa, igual que antes.
- La primera vez, Android puede pedir permiso de notificaciones (Android
  13 o superior); sin ese permiso la radio sigue sonando en segundo plano
  igual, pero no se ven los controles en la pantalla de bloqueo.

Por dentro, esto lo gestiona `PlaybackService` (un `MediaSessionService`
de Media3): es quien tiene el ExoPlayer real y sigue vivo aunque
`PlayerActivity` se detenga. `PlayerActivity` solo se conecta a él como
`MediaController` para mandar "reproduce este canal" / "pausa" / "cambia
de pista", y todo lo que antes resolvía para construir el reproductor
(tipo DASH/HLS/progresivo, cabeceras propias, DRM ClearKey) viaja dentro
del `MediaItem` para que el servicio pueda construir el `MediaSource`
igual que antes.

## EPG (guía de programación)

Si la lista trae guía EPG (`epgUrl`/`url-tvg` a nivel de lista y
`tvgId`/`tvg-id` por canal, ver arriba), la app la descarga y la lee sola
en segundo plano en cuanto cargas esa lista, sin bloquear nada:

- **En la lista de canales**: cada canal con `tvg-id` que tenga
  coincidencia en la guía muestra, debajo de su nombre, hasta tres líneas
  pequeñas con su horario: "Ahora (17:30–18:30): Previo toros desde
  Sevilla", "Después (18:30–20:30): Corrida de toros" y "Esta noche
  (22:00–23:30): Cine de noche". Cada línea se oculta por separado si no
  hay dato para ese hueco (por ejemplo, si la guía no llega tan lejos en
  el tiempo); "Esta noche" además se oculta si coincide con "Ahora" o
  "Después" (ya se está viendo o es lo siguiente, así que repetirlo no
  aporta nada). "Esta noche" apunta al programa que cubre las 22:00 del
  día de emisión actual (que va de las 06:00 a las 06:00 del día
  siguiente, como en cualquier guía de TV: a la 1 de la madrugada, "esta
  noche" sigue siendo la noche que ya empezó, no una futura).
- **En la pantalla de radio**: si el propio stream no manda su título
  ICY/ID3 (o no lo manda todavía), se usa el programa que marca la guía
  EPG como respaldo, en el mismo sitio. Se revisa cada minuto mientras
  esa pantalla está abierta, por si cambia de programa.
- Formatos admitidos, detectados solos mirando el contenido ya
  descargado (da igual lo que diga la URL o las cabeceras HTTP, y el
  `.gz` también se detecta solo):
  - **XMLTV** (`.xml` o `.xml.gz`), el estándar de facto para guías de
    programación por Internet — la mayoría de listas IPTV que ya traen
    `url-tvg` apuntan a uno de estos.
  - **JSON de listas públicas tipo tdtchannels.com**: un array raíz de
    canales, cada uno con su `name` (el mismo id que el `epg_id`/`tvgId`
    del canal en la lista) y sus `events` (inicio/fin en timestamp Unix
    más el título de cada programa).
- Si la descarga o el formato fallan, o el canal no tiene `tvg-id`, o no
  hay coincidencia en la guía, la app sigue funcionando exactamente igual,
  simplemente sin ese dato de más.
- Por dentro, `EpgRepository` descarga y parsea la guía una sola vez por
  URL (no vuelve a hacerlo si recargas la misma lista) y la deja en
  memoria mientras dure el proceso de la app.

## Estructura

```
app/src/main/java/com/example/superplayer/
  model/    Stream, Category, PlaylistData, DrmInfo
  data/     PlaylistRepository (parseo JSON/M3U/tdtchannels), EpgRepository
            (guía XMLTV o JSON), FavoritesStore, AppPrefs
  ui/       MainActivity (categorías + buscador), StreamListActivity
  player/   PlayerActivity (MediaController), PlaybackService (MediaSessionService
            + ExoPlayer real), StreamMediaSourceFactory (DASH/HLS/progresivo +
            DRM por canal), StreamMediaExtras, ClearKeyUtil
```

Funciones incluidas: categorías, buscador (filtra canales por nombre,
tanto en la portada como dentro de una categoría), favoritos persistentes
(categoría "⭐ Favoritos" arriba de todo cuando hay alguno), carga de
JSON (propio, "exolist" o listas públicas tipo tdtchannels.com)/M3U desde
el propio dispositivo o desde una URL, radio con "ahora suena" +
reproducción en segundo plano / pantalla de bloqueo, y guía EPG opcional
en XMLTV o JSON (programa actual en la lista de canales y como respaldo
en la pantalla de radio).

## Si la app se cierra sola

`SuperPlayerApp` captura cualquier error no controlado y, en vez de
cerrar la app en silencio, abre una pantalla de texto (`CrashActivity`)
con el error completo. Si te pasa, haz captura de esa pantalla — con eso
se puede diagnosticar exactamente qué falló, sin necesidad de `adb` ni
de un PC.

## Limitaciones conocidas / próximos pasos

- La navegación entre pantallas pasa la lista de canales en memoria
  (`companion object`) en vez de por el `Intent`, para no toparse con el
  límite de tamaño de Binder en playlists grandes. Si el sistema mata el
  proceso en segundo plano, al volver puede hacer falta reabrir la
  categoría. Para una app de producción, lo siguiente sería mover esto a
  un `ViewModel` compartido o a una base de datos local (Room).
- El parseo del JSON es síncrono; para playlists muy grandes (miles de
  canales) conviene moverlo a una corrutina en background.
- No hay pantalla de ajustes para editar cabeceras/DRM a mano: todo sale
  del JSON.
- Elegir un canal nuevo desde la lista siempre sustituye lo que estuviera
  sonando (incluida una radio en segundo plano): solo hay un reproductor
  real (dentro de `PlaybackService`) para toda la app.
- La guía EPG no tiene pantalla propia (no hay una parrilla con horarios):
  solo se usa para mostrar el programa actual en la lista de canales y en
  la pantalla de radio. Tampoco está disponible con la raíz del JSON en
  forma de array (formato "exolist"), solo con la raíz como objeto.

## Compilar sin PC (GitHub Actions)

El proyecto incluye `.github/workflows/build.yml`. Al subirlo a un repo de
GitHub, ese workflow compila un APK de depuración en la nube (JDK 17 +
Android SDK + Gradle) y lo deja descargable como "artifact" del run,
sin que tu teléfono tenga que instalar nada pesado. El workflow instala
Gradle 8.13 directamente (`gradle assembleDebug`) en vez de depender de un
`gradlew` incluido en el repo, así que no hace falta tocar nada de eso.

Pasos desde el móvil:

1. Crea una cuenta en GitHub (gratis) y un repositorio nuevo, p. ej.
   `SuperReproductor` (público o privado, ambos valen).
2. Instala **Termux** (desde F-Droid; la versión de Play Store está
   descontinuada) y dentro de él: `pkg install git`. Esto es solo para
   subir los archivos con git — Termux no compila nada aquí.
3. Copia esta carpeta al almacenamiento del teléfono, luego en Termux:
   `termux-setup-storage` y entra a la carpeta del proyecto.
4. Genera un token en GitHub: *Settings > Developer settings > Personal
   access tokens* (permiso "repo" / "Contents: read and write").
5. En la carpeta del proyecto:
   ```
   git init
   git add .
   git commit -m "primer commit"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/SuperReproductor.git
   git push -u origin main
   ```
   Cuando pida usuario/contraseña: usuario = tu usuario de GitHub,
   contraseña = el token del paso 4.
6. En GitHub, pestaña **Actions** del repo: el workflow arranca solo. Al
   terminar (unos minutos), abre el run y descarga el artifact
   `super-reproductor-debug-apk` — dentro está el `.apk` listo para
   instalar en el teléfono (activa "instalar apps de origen
   desconocido" para el navegador o la app de Archivos).

Es un APK de **debug** (firmado con la clave de depuración automática de
Gradle): perfecto para instalarlo en tu propio teléfono. Si algún día
quieres repartirlo fuera de tu dispositivo, hace falta configurar firma
de release aparte.

## Cambiar el nombre del paquete

Antes de publicar la app, cambia `com.example.superplayer` (en
`app/build.gradle.kts` → `namespace`/`applicationId`, y en las carpetas
`java/com/example/superplayer`) por tu propio dominio invertido.

## Icono de la app

El icono adaptativo (`mipmap-anydpi-v26/ic_launcher.xml`) usa tu propio
PNG en `app/src/main/res/drawable/ic_launcher_app.png`. Si ese archivo no
existe en tu copia del proyecto, colócalo ahí (cualquier PNG cuadrado,
idealmente 512×512 o más) antes de compilar — si ya lo subiste a tu
repositorio de GitHub en una entrega anterior, no hace falta volver a
hacerlo: este zip no lo incluye ni lo borra.
