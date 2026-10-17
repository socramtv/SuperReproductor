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

Desde la app, usa el botón "Archivo 📁" de la portada para elegir un
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

## Listas con formato "groups" / "stations"

Otro formato público que también se reconoce solo —mirando si el JSON
trae una clave `"groups"` en la raíz— es este, con canales agrupados en
`"stations"` dentro de cada grupo:

```json
{
  "name": "Nombre de la lista",
  "author": "Autor",
  "groups": [
    {
      "name": "Categoría",
      "stations": [
        {
          "name": "Canal demo",
          "image": "https://tu-servidor/logo.png",
          "url": "https://tu-servidor/index.m3u8",
          "referer": "https://tu-servidor/",
          "userAgent": "Mozilla/5.0 ..."
        }
      ]
    }
  ]
}
```

Cada `group` se trata como categoría y cada `station` como canal. Al no
traer un campo `type`/`extension`/`format` explícito, el tipo (HLS, DASH
o progresivo) se adivina a partir de la URL, igual que en las listas
M3U. Si la estación trae `referer` y/o `userAgent`, se mandan como
cabeceras HTTP `Referer` y `User-Agent` en cada petición al stream,
igual que con `referer` en el formato tdtchannels de más arriba.

Un aviso importante: esto solo reproduce streams directos (una URL de
vídeo/audio real que ExoPlayer pueda abrir tal cual). Algunas listas de
este estilo circulan con canales cuyo `"url"` no es un stream sino una
página web (a menudo un `.php`) que a su vez esconde el vídeo real
dentro —con distintos trucos según la página— para que solo la vea
quien la abra con un navegador normal. La app no trae ni traerá nada
pensado para detectar y saltarse ese tipo de protección y sacar el
vídeo escondido de una página cualquiera: es la técnica genérica que
usan estas listas para colarse en retransmisiones que no tienen
permiso para redistribuirse, y no es algo que quiera construir aunque
la petición en concreto no lo mencione así. Un canal de este tipo
aparecerá en la lista pero no arrancará al tocarlo. Si la página en
cuestión se limitara a redirigir por HTTP a la URL real del stream (sin
esconder nada), eso sí funcionaría ya hoy sin cambios, porque la app
sigue redirecciones normales.

## Listas remotas (TV / Lista 2 / Cine / TDT / Radio)

En la portada hay cinco botones (en dos filas) para cargar una lista
directamente desde una URL (por ejemplo, un enlace "raw" de un archivo en
GitHub), en vez de tener que elegir un archivo local cada vez. La primera
vez que tocas uno te pide la URL; a partir de ahí, tocarlo vuelve a
descargar y leer esa misma URL (para recoger cambios que hayas hecho en
el archivo remoto). Mantén pulsado el botón para cambiar la URL guardada
en ese hueco. Acepta tanto JSON como M3U, igual que la carga desde
archivo. Los cinco huecos son idénticos y genéricos (el nombre de cada
uno — TV, Lista 2, Cine, TDT, Radio — es solo una etiqueta orientativa):
ninguno trae una URL de fábrica, cada uno guarda la que tú le pongas.

Junto a esos cinco, el botón "Archivo 📁" de la segunda fila elige un
archivo JSON/M3U del propio dispositivo (no guarda ninguna URL): es la
única forma de cargar un archivo local, ya que no hay ningún icono
redundante para esto en la barra superior.

### Continuar viendo, recordatorios de la guía y orden de favoritos

**▶ Continuar viendo.** Al salir de una película o vídeo (no de un canal en
directo) a mitad, la app recuerda dónde te quedaste. Arriba de la portada
aparece la categoría **"▶ Continuar viendo"** con esos títulos (los más
recientes primero) y, debajo de cada uno, "Visto 45:10 de 1:55:00". Al abrirlo
sigue unos segundos antes del punto donde lo dejaste. Se guardan los últimos 30;
si lo viste casi entero (o apenas lo empezaste) desaparece solo. Solo cuenta
contenido con duración de 5 minutos o más. Menú de los tres puntos de esa
categoría → **"Vaciar «Continuar viendo»"**.

**⏰ Recordatorios de la guía EPG.** En la parrilla, toca un programa que
todavía no ha empezado: en el diálogo sale **"⏰ Recordarme"**. La app avisa con
una notificación **5 minutos antes** (o a la hora de inicio si falta menos);
tocarla abre el canal. Los programas con recordatorio llevan ⏰ junto al horario;
para quitarlo, vuelve a tocarlo → "Quitar recordatorio". En Android 13+ pide el
permiso de notificaciones la primera vez. Si el móvil no permite alarmas
exactas (Ajustes → Apps → Socram TV+ → "Alarmas y recordatorios"), el aviso
puede llegar con unos minutos de retraso. Los recordatorios se mantienen al
reiniciar el móvil.

**Ordenar favoritos.** Dentro de la categoría **⭐ Favoritos**, menú de los
tres puntos → **"Ordenar favoritos"**: arrastra una fila (pulsación larga) o usa
▲ ▼. El orden se aplica a la categoría Favoritos y a los accesos directos del
icono de la app, y entra en la copia de seguridad. Un favorito nuevo se añade
al final.

## Mini-guía en el reproductor, widget y foco en la guía (TV)

**Mini-guía.** En el reproductor, el botón de **guía** (icono de lista, en la fila
de abajo, entre formato de pantalla y el reloj) abre un panel a la derecha con los
canales de la lista que estás viendo: logo, nombre, el programa de **ahora** con
una barrita de lo que lleva, y el **siguiente** con su hora. El canal actual sale
resaltado. Tocar uno cambia a él sin salir del reproductor; Atrás o el mismo
botón cierran el panel. En la TV, el botón **GUÍA** del mando (o el de las **tres rayas ☰** del mando de Fire TV) lo abre y cierra, y
con el panel abierto las flechas mueven el foco por la lista (arriba/abajo) y OK
elige. Si el canal no tiene guía, sale "Sin datos de guía". Solo aparece cuando
hay más de un canal en la lista.

**Widget de favoritos.** Mantén pulsada la pantalla de inicio → Widgets →
Socram TV+. Muestra hasta **6 favoritos** (en el orden de "Ordenar favoritos") con
logo y nombre, y debajo el programa de ahora si la guía ya está cargada en ese
momento (si no, solo el nombre). Tocar uno abre el canal directamente; tocar el
título abre la app. Se actualiza cada 30 minutos y al instante cuando marcas,
quitas u ordenas favoritos. Sin favoritos, avisa de que los marques en la app.

**Guía EPG en la TV.** Al navegar la parrilla con el mando, el programa enfocado
lleva ahora un **marco naranja** y, abajo, una barra con "canal · programa · hora"
(y ⏰ si ya tiene recordatorio). Pulsa OK para ver los detalles y poner o quitar
el recordatorio.

## Copia de seguridad sin conexión

Cada uno de los cinco huecos guarda, además de la URL, una copia del
**último contenido que se descargó con éxito** de ahí. Al tocar un hueco,
la app sigue intentando descargar de la URL primero, como siempre (para
recoger cambios que haya habido en el archivo remoto); solo si en ese
momento no hay conexión (o el servidor no responde) se abre esa última
copia guardada en su lugar, en vez de quedarte sin nada — con un aviso
indicando que es una copia de una fecha/hora concreta, no la versión más
reciente. En cuanto vuelvas a tocar ese mismo hueco con conexión, se
actualiza solo. Si un hueco no se ha cargado nunca con éxito (o es la
primera vez que lo usas), no hay copia que abrir y el aviso de "sin
conexión" de siempre se queda igual que antes.

La copia se guarda únicamente cuando la descarga Y la lectura posterior
salen bien (nunca una respuesta a medias, ni la página de aviso de un wifi
público haciéndose pasar por la lista), así que la copia guardada siempre
es válida.

## Favoritos

El corazón de cada canal (en cualquier lista: portada, dentro de una
categoría, resultados de búsqueda) lo marca o desmarca como favorito;
mientras haya alguno, aparecen reunidos arriba del todo en una categoría
"⭐ Favoritos".

**En Android TV**, además de mover el mando hasta el corazón (D-pad
derecha desde el nombre del canal) y pulsar OK, también puedes **mantener
pulsado** el botón OK/centro con el foco en cualquier parte de la fila del
canal: alguno de los dos debería funcionarte bien con el mando, sin
depender de acertar exactamente en el icono pequeño. En el móvil no cambia
nada: tocar el corazón sigue siendo lo normal (mantener pulsada la fila
también funciona ahí, por si lo prefieres).

### Accesos directos manteniendo pulsado el icono de la app

Si tu lanzador lo admite (la mayoría desde Android 7.1), mantener pulsado
el icono de Socram TV+ en la pantalla de inicio muestra un menú con hasta
4 de tus canales favoritos (los primeros 4 por orden dentro de "⭐
Favoritos"); tocar uno abre directamente su reproductor, sin pasar por la
pantalla principal — incluso si la app no estaba abierta en segundo plano
(arranque en frío).

- Se actualizan solos al cargar una lista o marcar/desmarcar un favorito:
  no hace falta tocar nada a mano para refrescarlos.
- Sin ningún favorito marcado, ese menú no aparece (o aparece vacío,
  según el lanzador).
- El icono de cada acceso directo es siempre la misma estrella dorada de
  "favorito" de la app, no el logo del canal: así no depende de tener ya
  descargada la imagen del canal, ni de haber conexión en ese momento.
- Para que funcionen incluso tras un arranque en frío, los datos
  completos de cada canal favorito (nombre, URL, cabeceras, DRM si
  tiene...) se guardan junto con el favorito, no solo su identificador —
  así el acceso directo no depende de que ninguna lista esté cargada
  todavía.
- No he podido probar esto en un dispositivo real —si el menú no aparece
  al mantener pulsado el icono, o un acceso directo no abre el canal
  correctamente, avisa para revisarlo.

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
  notificación, con el nombre del canal y, si el canal trae `icon`/`logo`,
  también su imagen (se manda como `artworkUri` del `MediaMetadata`;
  Media3 la descarga solo, con el mismo mecanismo con el que descarga
  cualquier otra URL, así que vale tanto `http://` como `https://`). Si el
  canal no trae imagen, el sistema pone un icono genérico en su lugar.
  Volver a abrir la app y salir del reproductor (botón atrás) sí para la
  radio.
- Un canal de **vídeo/TV** también puede seguir sonando en segundo plano
  (solo el audio, como una radio): al bloquear el teléfono o cambiar de
  app deja de decodificar la imagen (ahorra batería) y mantiene el sonido
  con los controles en la notificación y en la pantalla de bloqueo. Al
  volver a la app, el vídeo se reactiva solo. Se activa/desactiva en el
  botón **Vídeo y audio** del reproductor, opción **"Audio en segundo plano: sí/no"**
  (por defecto, sí). Si lo pones en "no", al bloquear o cambiar de app se
  pausa como antes. Si el vídeo estaba en pausa al salir, se queda en
  pausa. Si cierras la ventanita de imagen en imagen, se pausa.
- La primera vez, Android puede pedir permiso de notificaciones (Android
  13 o superior); sin ese permiso la radio sigue sonando en segundo plano
  igual, pero no se ven los controles en la pantalla de bloqueo.

Para un canal de **vídeo/TV**, ese mismo texto (título dinámico o programa
de la guía, si no el nombre del canal) también está disponible, solo que
no permanente: un toque en la pantalla saca los controles normales de
reproducción (play/pausa, barra de progreso...) y, junto a ellos, un
cartel abajo con ese texto; ambos se ocultan solos a los pocos segundos.

Por dentro, esto lo gestiona `PlaybackService` (un `MediaSessionService`
de Media3): es quien tiene el ExoPlayer real y sigue vivo aunque
`PlayerActivity` se detenga. `PlayerActivity` solo se conecta a él como
`MediaController` para mandar "reproduce este canal" / "pausa" / "cambia
de pista", y todo lo que antes resolvía para construir el reproductor
(tipo DASH/HLS/progresivo, cabeceras propias, DRM ClearKey) viaja dentro
del `MediaItem` para que el servicio pueda construir el `MediaSource`
igual que antes.

## Cambiar de canal tocando la pantalla

Mientras se está reproduciendo algo, la pantalla del reproductor se
divide en tres franjas verticales invisibles:

- **Tercio izquierdo**: **toca** (sin arrastrar) para pasar al canal
  **anterior**.
- **Tercio derecho**: **toca** (sin arrastrar) para pasar al canal
  **siguiente**.
- **Tercio central**: muestra/oculta los controles normales de
  reproducción, igual que antes.

El "siguiente/anterior" recorre la misma lista de canales desde la que
abriste ese canal (la categoría, favoritos, o los resultados de una
búsqueda), en el mismo orden en que aparecen ahí, y da la vuelta al
llegar a un extremo (del último pasa al primero, y del primero al
último). Si esa lista solo tiene un canal (o no viene de ninguna lista),
tocar los lados no hace nada especial: toda la pantalla se comporta como
el tercio central.

El cambio de canal ocurre en la misma pantalla (no se cierra ni se
vuelve a abrir el reproductor): se resuelve el nuevo canal exactamente
igual que el primero al entrar (token si lo necesita, tipo DASH/HLS/
progresivo, cabeceras, DRM ClearKey), y el título/EPG/"ahora suena" se
actualizan solos.

Nota: el ancho exacto de las tres franjas (un tercio cada una) es una
primera aproximación sin poder probarla en un dispositivo real; si al
usarla el "centro" para mostrar/ocultar controles queda demasiado
estrecho (o demasiado ancho) se puede ajustar.

**Fallo corregido (solo se daba en Android TV):** al cambiar de canal con
el mando, la barra de controles (el engranaje de ajustes, el icono de
Chromecast y el cartel de "ahora suena" de arriba) a veces se quedaba en
pantalla aunque el canal ya estuviera sonando/viéndose de verdad, en vez de
ocultarse sola a los pocos segundos como de costumbre — antes solo se
quitaba pausando y volviendo a dar a play a mano. Que aparezcan un momento
al cambiar de canal es normal (el propio reproductor los saca solos con
cada cambio de canal); lo que fallaba es que luego no se ocultaban solos.
No se ha podido reproducir este fallo fuera de una TV real para dar con la
causa exacta (lo más probable es algún aviso de cambio de estado que no le
llega a tiempo al reproductor en ese tipo de dispositivo), así que el
arreglo es una comprobación periódica de respaldo en vez de una corrección
del origen: unos segundos después de cambiar de canal (más que de sobra
para el tiempo normal que tardan en ocultarse solos), si detecta que el
reproductor ya está reproduciendo de verdad mientras los controles siguen
visibles, los oculta a mano. Avisa si lo sigues viendo.

**Segundo fallo parecido (TV):** al entrar en un canal, el engranaje y el
botón de imagen en imagen se quedaban en pantalla hasta pausar y dar a play.
Causa: esos botones empiezan visibles en el diseño y solo se ocultaban cuando
los controles del reproductor avisaban de que se ocultaban; si al entrar ese
aviso no llegaba, se quedaban. Ahora se sincronizan con el estado real de los
controles desde el primer momento (ocultos al abrir el canal) y la comprobación
de respaldo también los esconde si ve alguno visible con los controles ocultos.

### Avanzar/retroceder sin perder el cambio de canal

Cambiar de canal es un **toque**; avanzar o retroceder el vídeo (en los
canales que lo permitan: un directo puro no tiene nada que avanzar) es
un **arrastre** — deslizar el dedo hacia la derecha o la izquierda, en
cualquier zona de la pantalla, sin soltar. Al ser dos formas de tocar
distintas (una sin apenas movimiento, la otra moviendo el dedo un buen
trecho), el sistema operativo ya las distingue solo, así que no hace
falta acertar en ningún sitio concreto ni renunciar al tercio
izquierdo/derecho para cambiar de canal: un toque rápido ahí cambia de
canal, y un arrastre ahí (o en el tercio central) avanza/retrocede.

**En una TV con mando** (sin pantalla táctil) es parecido pero con un
matiz, porque el mando no tiene "arrastrar": mientras los controles
están **ocultos** (el caso normal, viendo sin más), izquierda/derecha
del D-pad cambian de canal, igual que el toque en el móvil. En cuanto
sacas los controles en pantalla (con el botón central/OK del mando, o
el que los muestre en tu mando), izquierda/derecha dejan de cambiar de
canal y pasan a comportarse como en cualquier otra app de Media3: mover
el foco entre los controles, o -si el foco está en la barra de
progreso- avanzar/retroceder el vídeo. Si el mando tiene botones
dedicados de canal- /canal+, o de pista anterior/siguiente, esos
siempre cambian de canal, tengas los controles abiertos o no.

Los botones de "anterior/siguiente" y "retroceder/avanzar 10 s" que
Media3 pone por defecto en medio de los controles están desactivados
(`show_previous_button`/`show_next_button`/`show_rewind_button`/
`show_fastforward_button` a `false` en `activity_player.xml`): estaban
justo encima de estas zonas de toque y se quedaban con el toque antes
de que le llegara al gesto de cambiar de canal. La barra de progreso en
sí se deja tal cual (no se toca su visibilidad): Media3 ya la
deshabilita solo en los canales que son puro directo.

### Subir o bajar el volumen o el brillo deslizando

Deslizar el dedo verticalmente por la **mitad derecha** de la pantalla
sube o baja el **volumen**, igual que en YouTube: arriba sube, abajo
baja, y aparece el propio indicador de volumen del sistema (el mismo que
sale al usar los botones físicos), sin montar ningún indicador a medida.

Por la **mitad izquierda**, el mismo gesto ajusta el **brillo de la
pantalla** en vez del volumen: arriba más brillo, abajo menos, con un
indicador propio en el centro de la pantalla (en forma de porcentaje)
que se oculta solo un momento después de soltar el dedo. Nunca deja la
pantalla completamente a oscuras —se queda siempre con un mínimo
visible—, para no quedarte sin poder verla si arrastras hasta abajo del
todo. Este ajuste es solo para esta pantalla (como el de la mayoría de
reproductores de vídeo): no toca el brillo general del teléfono, y al
salir del reproductor vuelve a su valor normal.

Como con el arrastre horizontal, cada arrastre decide su modo una sola
vez (en cuanto el movimiento es claramente horizontal o claramente
vertical, y en qué mitad empezó) y se queda fijado así hasta soltar el
dedo, aunque el gesto tuerza por el camino: no cambia de "avanzar/
retroceder" a "volumen"/"brillo" ni al revés a media operación.

## Imagen en imagen (Picture-in-Picture)

Mientras se reproduce un canal de **vídeo** en el **móvil** (la radio no
lo necesita: ya sigue sonando en segundo plano sin más), puedes seguir
viéndolo en una ventana flotante mientras usas otra app o navegas el
menú:

- Tocando el botón de PiP (en la fila de botones de abajo, bajo la barra de
  progreso).
- O automáticamente, sin tocar nada, al salir de la app — con el botón
  de Inicio, cambiando a otra app, etc. Con el botón **Atrás** no: eso
  sigue cerrando el reproductor como siempre.

La ventana flotante usa la relación de ancho/alto real del vídeo en
curso (recortada al rango que admite Android, de 1:2.39 a 2.39:1, por si
algún vídeo fuera de lo normal se saliera de ahí).

**En Android TV esta función está desactivada del todo** (ni aparece el
botón ni se activa sola al salir de la app): en las pruebas no se
comportaba bien —ventana rota— y además interfería con cambiar de canal
con el D-pad del mando. En TV tampoco aporta demasiado (no existe el
mismo "cambiar de app en primer plano" que en móvil), así que no
compensaba intentar arreglarlo fino. Se detecta si el dispositivo es una
TV con la misma característica de Android que ya declara el manifiesto
(`android.software.leanback`).

## Chromecast

Mientras se reproduce un canal (vídeo o radio), el botón de "enviar"
(arriba a la derecha) deja mandarlo a un
Chromecast o cualquier otro dispositivo compatible con Google Cast que
esté en la misma red WiFi que el móvil:

- El botón solo aparece cuando hay algún dispositivo de este tipo visible
  en la red (lo gestiona él solo, sin comprobar nada a mano); si no hay
  ninguno, o el propio móvil no tiene Google Play Services, se queda
  oculto y la app sigue funcionando en local exactamente igual que
  siempre.
- Al tocarlo se abre el selector de dispositivos de Google; al elegir
  uno, el canal que estuviera sonando pasa a reproducirse en el
  Chromecast (play/pausa, barra de progreso... todo se sigue controlando
  desde el móvil) y la pantalla del móvil muestra un aviso de "Enviando a
  &lt;nombre del Chromecast&gt;" en vez del propio vídeo. Bloquear la
  pantalla, salir de la app o cambiar a otra no corta el envío: solo
  cambiar de canal o detener el envío desde el propio selector lo hace.
- **En Android TV el botón no aparece**: un Android TV ya es, de por sí,
  la pantalla grande a la que normalmente se envía algo desde el móvil,
  así que no tiene sentido ahí.
- **Limitación conocida**: el Chromecast recibe la URL del stream
  directamente (a través del receptor multimedia genérico de Google, ya
  que esta app no aloja uno propio), sin pasar por la lógica interna que
  añade las cabeceras HTTP propias del canal (`headers`/`referer` del
  JSON) o descifra el DRM ClearKey — eso solo lo sabe hacer el
  reproductor local. Un canal de IPTV normal, por URL directa y sin esas
  cosas (la gran mayoría), se envía sin problema; uno que sí las necesite
  puede fallar al enviarlo aunque reproduzca perfectamente en el móvil.
- No he podido probar esto en un Chromecast real —si algo no va como se
  espera, avisa para revisarlo.

## Reconexión automática

Si un canal de IPTV se corta del todo —no un simple parpadeo de red
momentáneo, que ExoPlayer ya reintenta por su cuenta sin que se note—,
antes había que salir del canal y volver a entrar a mano. Ahora, al
detectar un corte total, la app lo intenta arreglar sola: espera un poco
(2, 5 y luego 10 segundos) y vuelve a resolver y reproducir ese mismo
canal, hasta tres veces. Si para entonces sigue sin funcionar, se
muestra el aviso de error de siempre. En cuanto el canal vuelve a
reproducirse de verdad tras una reconexión, el cupo de reintentos se
restablece del todo para la próxima vez que se corte. Cambiar de canal a
mano, o salir del reproductor, cancela cualquier reintento pendiente.

## Modo claro/oscuro

El icono de sol/luna de la barra superior (en la pantalla principal)
cambia el aspecto de toda la app entre claro y oscuro:

- El icono muestra a qué modo se pasaría si lo tocas, no el modo actual:
  una luna 🌙 cuando ahora está en claro (toca para pasar a oscuro), un sol
  ☀️ cuando ahora está en oscuro (toca para pasar a claro).
- Se recuerda: la próxima vez que abras la app, arranca directamente en el
  modo que dejaste la última vez. Por defecto —la primera vez, o si
  actualizas desde una versión anterior— sigue siendo oscuro, como ha sido
  siempre la app hasta ahora.
- Afecta a toda la app **excepto la pantalla de reproducción**, que sigue
  siendo siempre negra con texto claro en los dos modos: para ver un vídeo
  o escuchar la radio, un fondo oscuro es mejor en cualquier caso, así que
  no tiene sentido que cambie con el resto.
- Es independiente del modo claro/oscuro del propio teléfono: aquí se
  elige a mano, sin seguir ningún ajuste del sistema.

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

### Vista de parrilla (franjas horarias)

Como complemento a las líneas de texto "Ahora/Después/Esta noche" de la
lista de canales, el icono de guía 🗓️ de la barra superior (junto al del
buscador 🔍) abre una **parrilla** al estilo de una guía de TV normal:
horas en horizontal, canales en filas, con el programa de cada hueco
dentro de su celda correspondiente.

- Reúne **todos** los canales de la lista actual que tengan `tvg-id` con
  datos de guía, sin importar de qué categoría sean (no hace falta entrar
  en una categoría primero).
- La franja visible es de **3 horas**; los botones "◀ 3 h" / "Ahora" /
  "3 h ▶" de arriba la desplazan. Se pensaron así —con botones en vez de
  solo un gesto de arrastrar— para que funcionen igual de bien con el
  mando de una TV que tocando en el móvil; dentro de cada franja de 3
  horas, en el móvil también puedes deslizar el dedo libremente si el
  contenido no cabe entero en la pantalla.
- El programa que está en emisión ahora mismo se resalta con el color de
  acento en cada fila (equivalente a la línea "Ahora" en negrita de la
  lista normal). Un hueco sin dato de guía para ese rato se deja en blanco
  en vez de inventar nada.
- Tocar el nombre de un canal (a la izquierda, siempre fijo aunque
  desplaces la parrilla) abre su reproductor, igual que en cualquier otra
  lista de la app.
- El **corazón ♥** de la barra superior (a la izquierda de la lupa) enseña
  solo los canales marcados como favoritos (con guía). Pulsado se pone
  dorado; otra vez vuelve a todos. Se combina con la lupa (busca solo entre
  tus favoritos) y el gesto de canal siguiente/anterior del reproductor
  recorre solo los favoritos mientras está activo.
- Las filas son más altas y cada programa muestra su **título** y, debajo,
  su **horario** ("06:00–10:55").
- El icono de lupa 🔍 de la barra superior busca a la vez entre el
  **nombre de los canales** y el **título de su programación**, en toda la
  guía ya descargada (no solo en la franja de 3 horas que se ve en ese
  momento). Al escribir, la parrilla se queda solo con los canales que
  coincidan; el nombre de cualquiera de ellos se pinta en dorado, tenga o
  no un programa suyo visible ahora mismo en pantalla. Si la coincidencia
  de título cae fuera de la franja de horas actual, la franja **salta
  sola** hasta ella (al tramo en emisión o al más próximo a "ahora" entre
  los que coincidan; si todos ya terminaron, al más reciente de esos), y
  además se resalta esa celda en concreto con un borde dorado para
  encontrarla de un vistazo. Una coincidencia solo por nombre de canal
  (sin ningún programa suyo que coincida) no mueve la franja, porque no
  hay un horario concreto al que saltar.
- Tocar una celda **con programa** (un hueco en blanco no tiene nada que
  mostrar) abre un diálogo con su horario completo y, si la guía XMLTV de
  esa lista incluye una imagen propia de ese programa (etiqueta `<icon>`
  dentro de `<programme>` en el XML), también su póster. No todas las
  guías XMLTV traen esa etiqueta —depende de quien la publique—, y la
  guía JSON de listas tipo tdtchannels.com nunca trae imagen por programa
  (solo el logo del canal, que ya se ve en la propia lista): en esos casos
  el diálogo se queda solo con el horario, sin póster.
- Si la lista no trae guía EPG, o todavía se está descargando, o ningún
  canal tiene coincidencia (de guía, o de una búsqueda en curso), se
  muestra un aviso en vez de una parrilla vacía — puede hacer falta volver
  a abrir esta pantalla unos segundos después de cargar la lista, si la
  guía tardó en descargarse.

**En Android TV**, el mando mueve el foco entre canales (arriba/abajo) y
hasta los tres botones de franja horaria con normalidad; las celdas de
programa aceptan el toque/clic (para abrir el diálogo de detalles/póster
de arriba) pero no se pueden seleccionar una por una ni enfocar con el
mando todavía. No he podido probar esta pantalla en una TV real —si el
mando se comporta raro aquí, avisa para revisarlo.

## Buscador global

La lupa de la portada busca un canal **en todas las listas a la vez**, no
solo en la que tienes abierta: incluye la lista cargada y las últimas copias
guardadas de los 5 huecos (TV / Lista 2 / Cine / TDT / Radio).

- No distingue mayúsculas ni tildes: "futbol" encuentra "Fútbol".
- Varias palabras, en cualquier orden: "futbol 1" encuentra "Fútbol 1 HD" y
  "1 Fútbol".
- Si un canal está en varias listas (misma URL), sale una sola vez.
- Los resultados se pueden abrir y recorrer con el cambio de canal
  habitual, aunque vengan de listas distintas.
- Las listas de los huecos se leen de su copia guardada (la que se hace al
  cargarlas con éxito), así que un hueco que nunca has cargado no aparece, y
  los archivos locales elegidos con "Cargar archivo" tampoco se guardan por
  hueco: solo se busca en ellos mientras están cargados.
- La primera vez que abres la lupa tras cargar una lista, el índice se
  prepara en segundo plano (un instante); mientras tanto ya busca en la
  lista actual.
- El buscador dentro de una categoría sigue igual (solo esa categoría).

## Filtro de categorías por lista

Debajo de los botones de listas hay una fila con un texto de estado a la
izquierda ("33 categorías · actualizada hace 2 min", o "7 de 33 categorías"
con filtro activo) y **tres botones redondos solo con icono** a la derecha (en
color naranja, para que no parezcan una categoría más): **filtro**, **ordenar ↕** y
**actualizar 🔄** (filtro y ordenar solo
si la lista cargada tiene más de una categoría; Actualizar solo en las 5 listas
remotas; si no hay ninguna, la fila no aparece).

**Categorías** abre una ventana con todas las categorías de esa lista y una
casilla en cada una: marcadas = se muestran, desmarcadas = se ocultan de la
portada.

- **Invertir** marca las desmarcadas y desmarca las marcadas de una vez;
  **Aceptar** guarda; **Cancelar** descarta los cambios. Hace falta dejar al
  menos una categoría marcada.
- **Cada lista tiene su filtro** (TV, Lista 2, Cine, TDT y Radio, más el
  archivo local): el de una no afecta a las otras, y se recuerda al cerrar
  la app. Lo que se guarda son las categorías ocultas, así que una categoría
  nueva que aparezca más adelante en la lista remota se ve por defecto.
- Con el filtro activo, el texto de estado dice "3 de 12 categorías" .
- **Favoritos** no depende del filtro: sus canales salen siempre, aunque su
  categoría esté oculta.
- El buscador global de la lupa sigue buscando en todos los canales, también
  en categorías ocultas.
- La ventana es una lista de casillas en una columna (sin agrupar por
  país/región como en otras apps), con el número de canales de cada una.

## Actualizar sin desinstalar

Las APK de depuración que compila GitHub Actions ahora se firman siempre con
la **misma clave** (`app/debug.keystore`, configurada en `signingConfigs` de
`app/build.gradle.kts`). Antes, cada compilación generaba una clave nueva y
Android la trataba como "otra app": había que desinstalar la anterior y se
perdían favoritos y ajustes. Con la clave fija, la APK nueva se instala
**encima** de la vieja y se conserva todo.

- **Una última desinstalación:** la primera APK con esta clave tampoco se
  puede instalar encima de las antiguas (tenían otra firma). Hay que
  desinstalar una vez más; a partir de ahí, ya no.
- No borres ni cambies `app/debug.keystore`: si cambia, vuelve a pasar lo de
  antes. Está en el repositorio a propósito; es una clave de uso personal
  (contraseña `android`) que solo sirve para que Android reconozca las
  actualizaciones como de la misma app, no protege nada.
- Si usas una APK ya instalada de otra fuente, no se podrá actualizar encima
  (firma distinta).

## Copia de seguridad

En el menú de los tres puntos de la portada:

- **Exportar copia de seguridad:** guarda un archivo `socram-tv-copia.json`
  donde elijas (el propio sistema te deja escoger la carpeta, sin permisos
  extra) con **todo lo tuyo**: favoritos (con su orden), las URLs de los 5
  huecos de lista, el filtro y el orden de categorías de cada lista, el modo
  claro/oscuro, los **canales ocultos y renombrados**, los **recordatorios**
  de la guía (los que aún no han pasado), **«Continuar viendo»** y los ajustes
  del reproductor (audio en segundo plano y formato de pantalla).
- **Importar copia de seguridad:** elige ese archivo. Los favoritos se
  **suman** a los que ya tengas (no se borra ninguno), igual que los canales
  ocultos, los nombres propios, los recordatorios (que se vuelven a programar
  solos) y «Continuar viendo» (si un título está en los dos sitios, gana el más
  reciente); las URLs, los filtros, el modo claro/oscuro y los ajustes del
  reproductor se sustituyen por los de la copia. Al terminar sale un aviso con
  lo que entró de cada cosa. Las copias hechas con versiones anteriores
  siguen funcionando (simplemente no traen esas partes).
- También guarda (y suma al importar, sin borrar nada): los **vídeos favoritos y
  carpetas de Telegram**, los **chats favoritos** de Telegram, los **marcadores
  del navegador** y los **equipos de los avisos de goles**. No guarda la sesión
  de Telegram ni el api_id/api_hash (cada uno inicia sesión y los introduce).
- No incluye las listas descargadas ni la guía de programación: se vuelven a
  bajar solas de sus URLs al abrir cada hueco. El archivo cargado con
  "Cargar archivo" tampoco se guarda (hay que volver a elegirlo).
- Guarda el archivo fuera de la app (Descargas, Drive...) para que sobreviva
  a una desinstalación.

## Ordenar categorías

El botón redondo **↕** (ver "Filtro de categorías por lista") es **"Ordenar"**: abre una ventana con
todas las categorías de la lista cargada para cambiar su orden en la portada.

- **Móvil:** mantén pulsada una fila y arrástrala. **TV (mando):** usa las
  flechas ▲ ▼ de cada fila.
- **Aceptar** guarda; **Cancelar** descarta; **Restablecer** vuelve al orden
  original de la lista.
- **Cada lista tiene su orden** (igual que el filtro), se recuerda al cerrar
  la app y va en la copia de seguridad.
- Las categorías ocultas por el filtro también salen en esta ventana (con
  "(oculta)") y conservan su sitio por si las vuelves a mostrar.
- Una categoría nueva que aparezca más adelante en la lista remota sale al
  final, en su orden original.
- **Favoritos** siempre va arriba del todo.
- El orden solo afecta a la portada; dentro de una categoría los canales
  siguen en el orden de la lista.

## Ocultar y renombrar canales, canales caídos, multivista y TV

**🚫 Ocultar y ✏️ renombrar canales.** Mantén pulsado un canal en cualquier lista
(o en los resultados de búsqueda): sale un menú con favorito, **Cambiar nombre**,
**Volver al nombre original** y **Ocultar canal**. Es un ajuste tuyo, no toca la
lista original: se guarda por la URL del canal, así que vale en todas las listas
donde salga y sobrevive a las actualizaciones. Para volver a mostrar canales:
menú ⋮ de cualquier categoría → **"Canales ocultos…"** → marca los que quieras
y **"Mostrar los marcados"** (vuelve a la portada para verlos). Una categoría
con todos sus canales ocultos desaparece de la portada.

**🩺 Comprobar canales caídos.** Menú ⋮ de una categoría → **"Comprobar canales
caídos"**. Prueba los canales de esa categoría (8 a la vez, con barra de
progreso; solo lee los primeros bytes) y al terminar enseña los que no
responden, todos marcados: desmarca los que quieras conservar y pulsa **"Ocultar
los marcados"**. Ojo: algún servidor que exija algo especial puede salir como
caído sin estarlo, por eso se puede desmarcar. Los canales con token no se
pueden probar desde fuera y se dan por buenos. Si te arrepientes, están en
"Canales ocultos…".

**🪟 Dos canales a la vez (multivista).** En el reproductor, botón **de las dos
pantallas** (junto a Guía). Abre el canal que veías y, a su lado, el siguiente de
la lista (en el móvil en vertical, uno encima del otro). Solo suena uno, el que
tiene el marco naranja; toca el otro (o pulsa su botón 🔊) para pasarle el sonido.
Con el mando de TV, el recuadro sobre el que estás (el que vas a elegir) se marca
en rojo. El botón ⇅ de cada recuadro cambia su canal: eliges grupo (todos los canales de la lista cargada, favoritos, la categoría de origen o cualquier categoría) y luego el canal, con buscador. Con la multivista abierta el reproductor normal se pausa y,
al volver, sigue solo. No hay segundo plano en multivista. Cada recuadro se limita a 720p y el segundo arranca un instante después del primero; si el aparato (algunas Google TV) no puede abrir un segundo decodificador de vídeo, la app reintenta con menos resolución y con el decodificador de software, y si aun así no puede lo avisa.

**📺 Continuar viendo en el inicio de Android TV.** En una TV con Android TV /
Google TV, las películas y vídeos que dejaste a medias salen también en la fila
**"Reproducir siguiente"** de la pantalla de inicio, con su barra de progreso;
al elegir uno se abre y sigue por donde lo dejaste. La fila se mantiene sola al
ver o terminar cosas. Solo funciona en dispositivos de TV que admitan esa fila
(en algunos Fire TV antiguos puede no aparecer; la app sigue igual sin ella).

## Búsqueda por voz

En la portada, el **micrófono** de la barra (junto a la lupa) abre el
reconocedor de voz del aparato. Entiende órdenes como **"pon La 1"**, **"abre
Eurosport"**, **"quiero ver Teledeporte"** o **"busca fútbol"**. Busca en
**todas las listas** (la cargada y las copias guardadas de los 5 huecos), sin
distinguir mayúsculas ni tildes, y con todas las palabras en cualquier orden:

- Si hay un canal claro (se llama exactamente así, o es el único que empieza o
  coincide), **lo abre directamente**.
- Si hay varios, o dices "busca…", enseña los resultados en la búsqueda para
  que elijas.
- Si no hay ninguno, te avisa de que no lo ha encontrado.

También responde al **asistente de voz** del sistema ("Ok Google, pon La 1 en
Socram TV"): la app declara que sabe atender esas órdenes (MEDIA_PLAY_FROM_SEARCH).
Que el asistente la elija depende de cada aparato (en Google TV suele funcionar).
Si el aparato no tiene reconocimiento de voz (algunos Fire TV), el micrófono avisa
y se sigue usando la lupa.

## Ahora en directo

Menú ⋮ de la portada → **"📺 Ahora en directo"**. Un mosaico con tus
**favoritos** y, en cada tarjeta, lo que emiten ahora mismo (programa, barra de
cuánto lleva y el siguiente, de la guía EPG). Arriba hay una **vista previa en
vivo y sin sonido** del canal seleccionado: toca una tarjeta (o enfócala con el
mando) para verla; toca otra vez la tarjeta, o la propia vista previa, para
abrir el canal entero. Solo hay una vista previa a la vez (varias a la vez no
funcionan en muchas teles). Se actualiza solo cada medio minuto. Si no tienes
favoritos, enseña los primeros canales de la lista cargada que tengan guía.

## Avisos de goles y eventos

Menú ⋮ de la portada → **"⚽ Avisos de goles…"**:

- **Activar avisos** y **Mis equipos:** eliges equipos de los partidos de hoy
  o escribes el nombre a mano (vale cualquiera: "Real Madrid", "Betis"...,
  sin tildes ni mayúsculas).
- La app recibe los marcadores de una **API pública de ESPN** (LaLiga,
  LaLiga Hypermotion, Copa del Rey, Champions, Europa League, Conference, Premier,
  Serie A, Bundesliga y Ligue 1) y lanza una notificación cuando: falta 15
  minutos para que empiece, empieza, **hay gol** (con quién y minuto), un gol se
  anula, hay **expulsión** y cuando termina.
- Al **tocar el aviso** la app busca en la guía de tus canales cuál está
  emitiendo ese partido y lo abre (si hay varios, te los enseña para elegir).
  Hace falta que tus canales tengan guía EPG y que el programa lleve los
  nombres de los equipos.
- **Partidos de hoy (comprobar)** enseña lo que devuelve el servicio ahora
  mismo (⭐ = tus equipos) y **Probar un aviso** lanza uno de ejemplo.
- Ritmo: comprueba cada minuto mientras un partido tuyo está en juego, y si no
  se despierta justo antes del siguiente (como mucho cada 30 min). Con el móvil
  quieto y la pantalla apagada Android puede retrasar las alarmas, así que
  puede llegar con algo de retraso. Necesita el permiso de notificaciones.
- Limitaciones: la API de ESPN **no es oficial** (puede cambiar o caerse sin
  aviso) y solo cubre fútbol. El nombre del equipo debe parecerse al que usa la
  API (en inglés a veces: "Atletico Madrid", "Sevilla").

## Botones del reproductor

Debajo de la barra de progreso, a la izquierda del engranaje de Media3 (el de
velocidad), hay una fila con varios botones:

1. **Vídeo y audio** (un solo botón): menú con *Vídeo*, *Audio* (para elegir
   calidad/pista) y *Audio en segundo plano: sí/no*.
2. **Subtítulos:** selector de subtítulos (avisa si el canal no trae).
3. **Formato de pantalla:** Ajustar / Estirar / Zoom.
4. **Temporizador de apagado** (icono de reloj): 30, 60, 90 o 120 minutos.
   Al acabar, la reproducción se para sola (también con la pantalla bloqueada o
   la app en segundo plano; si el reproductor está abierto, se cierra y vuelves
   a la lista). Mientras hay uno en marcha, el icono se pinta de naranja; al
   pulsarlo otra vez ves los minutos que quedan y puedes desactivarlo o poner
   otro tiempo. Funciona también con radio. Si sales del reproductor con el
   botón Atrás, el temporizador se cancela.
5. **Dos canales a la vez** (multivista, ver arriba).
6. **Imagen en imagen** (ventana flotante).

El botón de **Chromecast** se queda arriba a la derecha (algo separado del borde). Todos aparecen y
desaparecen junto con los controles. En radio no salen formato ni imagen en
imagen.

## Formato de pantalla

En el reproductor, el botón **Formato de pantalla** (fila de botones de abajo,
bajo la barra de progreso) cada vez que se pulsa pasa al siguiente:

- **Ajustar:** como siempre, el vídeo entero con barras negras si hace falta.
- **Estirar:** llena toda la pantalla deformando la imagen (para canales que
  salen "aplastados" o con barras de más).
- **Zoom:** llena la pantalla sin deformar, recortando los bordes.

Sale un aviso con el formato elegido. Es **uno solo para todos los canales** y
se recuerda al cerrar la app (por defecto, Ajustar).

## Actualizar listas

Las listas de los 5 huecos (TV, Lista 2, Cine, TDT, Radio) **no se descargan
cada vez**: se actualizan de dos formas.

- **El botón redondo 🔄** (en la fila de opciones, dentro de cada lista remota): vuelve a descargar la lista que estás viendo y la recarga. Si
  no hay red, se queda con la copia guardada y te lo avisa.
- **Una vez al día, solas:** al abrir la app, en segundo plano y sin avisos, se
  descargan las listas de los huecos cuya copia tenga más de 24 horas (o no
  tenga ninguna). No cambia la lista que estés viendo.

Si no pulsas el botón, una lista no se actualiza (más allá de esa puesta al día
diaria).

- **Pulsar el botón de un hueco** abre su copia guardada al instante, sin tocar
  la red. Si todavía no hay copia (primera vez), la descarga.
- Cada botón de hueco muestra debajo, en pequeño, cuándo se actualizó su copia
  ("hace 5 min", "hace 2 h", "hace 3 d").
- Una descarga que falle o una lista que no se entienda se ignora y se conserva
  la copia anterior.
- **Cambiar la URL** de un hueco (mantener pulsado el botón) sí descarga la
  lista nueva en el momento.
- La copia al día también la usan el buscador global y la apertura sin conexión.
- El botón "Actualizar" no sale en el archivo local ni en la lista de ejemplo, y
  los huecos sin URL guardada no se actualizan.

## Estructura

```
app/src/main/java/com/example/superplayer/
  model/    Stream, Category, PlaylistData, DrmInfo
  data/     PlaylistRepository (parseo JSON/M3U/tdtchannels), EpgRepository
            (guía XMLTV o JSON), FavoritesStore, AppPrefs, PlaylistCache
            (copia sin conexión de las listas remotas)
  ui/       MainActivity (categorías + buscador), StreamListActivity,
            EpgGridActivity + EpgGridAdapter + EpgGridMath (parrilla EPG)
  player/   PlayerActivity (MediaController), PlaybackService (MediaSessionService
            + ExoPlayer real, envuelto en CastPlayer para Chromecast),
            StreamMediaSourceFactory (DASH/HLS/progresivo + DRM por canal),
            StreamMediaExtras, ClearKeyUtil, CastOptionsProviderImpl
            (configuración mínima de Chromecast), ShortcutsHelper (accesos
            directos de favoritos en el lanzador)
```

Funciones incluidas: categorías, buscador (filtra canales por nombre,
tanto en la portada como dentro de una categoría), favoritos persistentes
(categoría "⭐ Favoritos" arriba de todo cuando hay alguno, con accesos
directos del lanzador manteniendo pulsado el icono de la app), carga de
JSON (propio, "exolist" o listas públicas tipo tdtchannels.com)/M3U desde
el propio dispositivo o desde una URL (con copia de respaldo sin conexión
para las cinco listas remotas), radio con "ahora suena" + reproducción en
segundo plano / pantalla de bloqueo, guía EPG opcional en XMLTV o JSON
(programa actual en la lista de canales, vista de parrilla por horas con
salto automático a la franja de una búsqueda y póster del programa si la
guía lo trae, y como respaldo en la pantalla de radio), imagen en imagen y
envío a Chromecast (ambos solo en móvil), reconexión automática para
canales de vídeo, modo claro/oscuro, y gesto de volumen/brillo deslizando
verticalmente (derecha/izquierda).

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
- La guía EPG (en cualquiera de sus dos formas: texto en la lista de
  canales, o la parrilla) no está disponible con la raíz del JSON en forma
  de array (formato "exolist"), solo con la raíz como objeto.
- En la parrilla EPG, las celdas de programa no se pueden seleccionar una
  por una con el mando de una TV (solo los canales y los tres botones de
  franja horaria); tampoco hay forma de navegar más allá del rango de
  horas que ya trae descargado el XMLTV/JSON de la lista.
- La copia sin conexión de las listas remotas (`PlaylistCache`) no tiene
  límite de antigüedad ni se borra sola: siempre es la última que se
  descargó bien, sin más gestión. Tampoco hay botón para borrarla a mano
  si alguna vez hiciera falta.
- Al enviar un canal a un Chromecast, los que necesiten cabeceras HTTP
  propias o DRM ClearKey pueden no funcionar (ver la limitación explicada
  en la sección "Chromecast" de arriba): el receptor genérico de Google
  solo recibe la URL del stream, no esa configuración extra.
- El póster del diálogo de la parrilla EPG depende de que la guía XMLTV de
  esa lista incluya `<icon>` por programa: no todas las guías lo traen, y
  la guía JSON de listas tipo tdtchannels.com nunca lo trae (solo logo de
  canal). Sin esa etiqueta, el diálogo se queda solo con el horario.
- Los accesos directos de favoritos (mantener pulsado el icono de la app)
  no se han probado en un dispositivo real: en teoría deberían abrir el
  reproductor directamente aunque la app esté totalmente cerrada, pero si
  el lanzador no los muestra, o uno de ellos no abre el canal
  correctamente, avisa para revisarlo. El icono de cada acceso directo es
  siempre la estrella dorada genérica, no el logo del canal.
- El modo claro se ha revisado leyendo el código de cada pantalla (colores
  de fondo/texto/bordes), pero no se ha visto renderizado en un móvil real
  todavía: si algún texto o borde se ve con poco contraste en alguna
  pantalla, avisa para ajustarlo.

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

## Logo de cabecera

En la pantalla principal (la "portada", donde se cargan las listas), arriba
del todo se ve el logo `header_logo.jpg`
(`app/src/main/res/drawable/header_logo.jpg`) a todo lo ancho, con su
proporción real (nunca recortado ni estirado — el alto sale solo de esa
proporción según el ancho de la pantalla). Justo debajo, la barra de
siempre con los iconos de buscador/parrilla EPG/claro-oscuro, ya sin
ningún texto de título (el logo ya lleva el nombre dibujado).

- **Medidas**: sirve cualquier imagen apaisada (más ancha que alta) con
  una proporción parecida a la actual — la que mandaste es de 2018×448 px
  (proporción 4,5:1). No hace falta un tamaño exacto: con que sea apaisada
  y de al menos unos 900-1000 px de ancho (para que no se vea borrosa en
  pantallas de alta densidad) basta; yo la ajusto al tamaño final al
  meterla en el proyecto. Esta la he guardado redimensionada a 1200×266 px
  — de sobra para cómo se ve en pantalla, y bastante más ligera que el
  original para no engordar la app sin necesidad.
- Los iconos van en su **propia barra sólida** debajo del logo, no encima
  de la imagen: se probó a ponerlos encima, pero al ser una foto tan
  variada (césped, camisetas blancas, escudos...) no había un solo color
  de icono que se viera bien en todas sus zonas. En su barra de siempre el
  contraste queda garantizado, con el mismo color de icono de toda la vida
  que cambia con el modo claro/oscuro.
- De paso se corrigió un fallo que ya existía desde el modo claro/oscuro:
  el icono de luna 🌙 (el que se ve estando en modo claro) tenía el color
  fijado en blanco, por lo que se perdía contra la barra blanca de ese
  modo. Ahora tanto el sol como la luna se tiñen al mismo color que cambia
  con el modo, igual que los otros dos iconos.
- Solo aparece en esta pantalla (la portada); las demás (categoría,
  parrilla EPG, reproductor) siguen con su barra superior normal de texto,
  para no repetir cabecera por todas partes.
- El fondo oscuro del logo es parte de la propia imagen (no es
  transparente), así que se ve igual en modo claro y en modo oscuro.
- **En Android TV** se usa una segunda imagen, más apaisada, guardada
  aparte en `app/src/main/res/drawable-television/header_logo.jpg` (mismo
  nombre que la del móvil, `header_logo.jpg`, pero en una carpeta con el
  calificador `-television`: Android elige sola cuál de las dos usar según
  el tipo de dispositivo, sin ningún código de por medio). Hacía falta
  porque en una TV la pantalla es mucho más ancha que alta, y el mismo
  logo a todo lo ancho —pensado para la proporción de un móvil— ocupaba
  muchísimo alto, media pantalla o más. Por si alguna TV en concreto aun
  así necesitara más alto del que debe, el logo también lleva un tope de
  alto (`android:maxHeight`, ver `values/dimens.xml` y
  `values-television/dimens.xml`): en el móvil el tope es tan alto que
  nunca llega a aplicarse de verdad, y en TV se queda en 100dp.

## Que aparezca en el menú principal de Android TV

El launcher de Android TV (la pantalla de inicio con filas de apps) no
busca la categoría normal de lanzador de móvil/tablet
(`android.intent.category.LAUNCHER`), sino otra aparte,
`android.intent.category.LEANBACK_LAUNCHER`. Una app que solo tiene la
primera se instala y funciona perfectamente —se puede abrir a mano desde
Ajustes > Aplicaciones, como pasaba antes de este cambio—, pero el
launcher de la TV ni se entera de que existe, porque solo lista
actividades con esa segunda categoría. `MainActivity` ya declara las dos
en el mismo `intent-filter`, así que debería aparecer en el menú
principal sin tener que entrar por Ajustes. El manifiesto también
declara `android.software.leanback` y `android.hardware.touchscreen`
(este último como no obligatorio, porque una TV no tiene pantalla
táctil); no son la causa de que no apareciera, pero es lo correcto
declararlos en una app pensada para los dos tipos de dispositivo, y hace
falta si algún día se publica en la Play Store para TV.

## Escudos y datos de partidos en la guía

En la guía (EPG), los programas de fútbol cuyo título contiene los dos equipos
muestran el marcador en la celda (`⚽ 1-0 · 23'`, `⚽ 2-1 · final`) y, al abrir
sus detalles, una tarjeta con los escudos, el resultado, el estado (en juego /
final / hora de inicio), la competición y los goles y tarjetas rojas.
Los datos salen de la API pública (no oficial) de ESPN, la misma de los avisos
de goles, y se refrescan cada minuto mientras haya partidos en juego.
Si el título del programa no lleva el nombre de ambos equipos, no se muestra.

## Pausa en directo (retroceder el directo)

Menú ⋮ de la portada → **"Pausa en directo…"** (o, dentro del reproductor, el botón de *Vídeo y audio* → "Pausa en directo…"):
eliges cuántos minutos guardar (10, 30 o 60; por defecto **desactivada**). Al abrir un canal, un hilo descarga el
directo a un buffer en disco (carpeta de caché, se borra al salir) y el reproductor lo ve a través de un mini servidor
local (`127.0.0.1`). Con los controles abiertos aparece una barra con lo que vas por detrás del directo y los botones
**⏪ 5 min, ⏪ 30 s, 30 s ⏩ y 🔴 Directo**; el play/pausa de siempre pausa el directo y el buffer sigue creciendo. En el mando,
las teclas de rebobinar/avanzar mueven 30 s.

- Funciona con streams TS directos y con HLS cuyos segmentos sean TS (el caso normal). No se usa con DRM, canales con token,
  DASH, vídeos/películas, HLS cifrado o con segmentos fMP4, ni con radios: en esos casos el canal se reproduce normal.
- Solo se puede retroceder hasta el momento en que abriste el canal (o hasta N minutos atrás). Cambiar de canal vacía el buffer.
- Con Chromecast se envía el canal normal (el buffer es local del móvil).
- Código: `player/TimeShiftSession.kt` (buffer + servidor + descarga), integración en `PlayerActivity`.

## Avisos por programa

Menú ⋮ → **"Avisos por programa…"**, o desde los detalles de un programa de la guía → **"🔔 Avisar siempre"**. Guardas un título o
parte de él ("El Hormiguero", "Champions", "Real Madrid"); cada vez que se carga la guía, los programas de los próximos 7
días que lo contengan reciben un recordatorio normal (aviso 5 minutos antes, al tocarlo abre el canal). Sin distinguir
mayúsculas ni tildes; un mismo programa en varios canales avisa una sola vez. Código: `reminder/ProgramAlerts.kt`.

## Perfiles

Menú ⋮ → **"Perfil: …"**: crear, cambiar, renombrar y borrar perfiles. Cada perfil tiene sus **favoritos (y su orden), canales
ocultos y renombrados, «Continuar viendo» y filtros/orden de categorías**. Las listas, el tema, los recordatorios, los avisos
de goles y los avisos por programa son comunes. El perfil «Principal» usa los datos de siempre (no se pierde nada). El widget, los
accesos directos y la fila «Continuar viendo» de la TV siguen al perfil activo. Código: `data/Profiles.kt`.

## Widget completo

Además del widget sencillo de favoritos, hay uno nuevo **"Socram TV+"** (4x4, redimensionable) con lista desplazable y
pestañas: **⭐ Favoritos** (con lo que echan ahora y barra de progreso, si la guía está cargada), **▶ Continuar viendo** (por dónde
vas) y **⚽ Partidos** (marcadores de hoy; tocar uno busca su canal). Botón ⟳ para refrescar; tocar el título abre la app.
Código: `widget/HomeWidgetProvider.kt`, `HomeWidgetService.kt`, `WidgetClickActivity.kt`.

## Cine (carátulas y sinopsis)

Botón **"Cine 🍿"** de la pantalla principal (abre directamente la parrilla, sin tocar la lista que estés viendo): parrilla de carátulas de las películas de tus listas, con buscador. Al tocar una sale su ficha (carátula
grande, año, género y sinopsis) con **▶ Ver** y favorito. Las categorías de cine se detectan por el nombre (película, cine, movie, film,
vod, estreno) y se eligen a mano con el botón **Categorías**. Carátula y sinopsis salen de la búsqueda de películas de iTunes (en
español de España) y, si no aparece, de TVMaze (series); sin clave de API. Si no se encuentra nada se usa el logo de la propia lista.
Lo encontrado (y lo no encontrado, 7 días) se guarda en el móvil. Código: `data/MovieInfo.kt`, `ui/CineActivity.kt`, `ui/CineAdapter.kt`.

**TMDb (ficha completa):** ya viene una clave incluida (solo la usa Cine); un desplegable bajo el buscador filtra por categoría (por defecto todas). Si quisieras otra clave, cámbiala en `AppPrefs.DEFAULT_TMDB_KEY`. Antes: pega tu API key (v3, gratis en themoviedb.org). Con ella la ficha muestra nota, duración, género, clasificación, frase, sinopsis, dirección/creador, reparto y botón de tráiler (YouTube). Sin clave (o si TMDb no encuentra el título) se usa iTunes/TVMaze. La clave se guarda solo en el móvil, no en el código.

**Estilo Netflix / Prime:** la pantalla de Cine abre con filas: una por cada categoría de la lista (Disney, Marvel…) y, debajo, filas automáticas por género (🎭 Acción, Animación, Comedia…) con las películas cuya ficha ya se conoce, que van apareciendo solas según se descubren. Al buscar o elegir una categoría en el desplegable se pasa a la parrilla de carátulas. Código: `ui/CineRowsAdapter.kt`.

## Móvil como mando de la tele

- **En la tele** (Fire TV / Google TV / Android TV): viene **activado**. Menú ⋮ → "Control remoto de esta pantalla…" enseña la dirección
  (IP:puerto), el nombre y un **código de 4 cifras**; se puede desactivar o cambiar el código. En un móvil viene desactivado.
- **En el móvil**: menú ⋮ → "📱 Usar este móvil como mando…". Busca las teles de la misma wifi (también se puede escribir la IP), pide el código
  y da: **canal −/+, pausa, ⏪/⏩ 30 s** (en la pausa en directo mueve el buffer), **volumen −/mute/+**, y **enviar un canal / un favorito** a la tele.
  Arriba se ve lo que está viendo la tele.
- Las órdenes viajan por HTTP dentro de tu red local, con el código en cada petición. Para enviar canales, la app tiene que estar abierta en la tele
  (portada o reproductor). Canal −/+ necesita haber abierto el canal desde una lista. Código: `remote/RemoteServer.kt`, `remote/RemoteClient.kt`,
  `ui/RemoteActivity.kt`.

## Mini marcador flotante

En el reproductor, arriba a la izquierda, sale el marcador (y minuto) de los partidos **en juego** de los equipos que sigues en "Avisos de goles",
aunque estés viendo otro canal. Se actualiza cada minuto (datos de ESPN, como los avisos) y se activa/desactiva en el botón *Vídeo y audio* →
"Marcador flotante". Si no sigues ningún equipo no sale nada.


## Telegram (con un bot)

Menú ⋮ → **"✈️ Telegram…"**. Crea un bot con @BotFather, pega su token en ⋮ → "Bot de Telegram…" y añade el bot a tu grupo, o como **administrador** a tu canal. Mientras la pantalla está abierta, la app recoge los mensajes nuevos (se guardan en el móvil) y los muestra por chat: vídeos y audios se reproducen, un archivo .m3u/.json se abre como lista de canales, y los enlaces de los mensajes (m3u8, mp4…) se reproducen al tocarlos.
Límites de la API de bots de Telegram: no se puede leer el historial anterior a añadir el bot ni grupos privados donde no esté el bot, y los archivos solo se pueden descargar hasta 20 MB (los enlaces no tienen límite). Código: `telegram/TelegramBot.kt`, `ui/TelegramActivity.kt`.

### Telegram con tu cuenta (TDLib)

En la pantalla de Telegram, ⋮ → **"Iniciar sesión con mi cuenta…"**: número, código y (si la tienes) contraseña de dos pasos; después ves tus canales y grupos (también privados) con sus vídeos, audios, listas .m3u y enlaces. Los archivos se descargan al móvil antes de reproducirlos.

Hace falta tu `api_id` y `api_hash` de https://my.telegram.org (API development tools); se piden en la app y se guardan solo en el dispositivo.

La librería oficial TDLib no está en el repositorio: se compila con el workflow **Build TDLib** (GitHub → Actions → Build TDLib → Run workflow; **una sola vez**, tarda de 30 min a más de 1 h) y queda en la caché de GitHub; el workflow normal "Build APK" la restaura y la incluye. Si no se ha compilado, la app se construye igual, con esta función desactivada. Si la caché se borra (GitHub la elimina tras 7 días sin usarse), basta con volver a lanzar Build TDLib. Código: `telegram/TgAccountBackend.kt`, `src/withTdlib` (TDLib real), `src/noTdlib` (sin TDLib), `ui/TelegramAccountActivity.kt`.

Telegram con cuenta, además: **🔍 Buscar** (⋮ → Buscar…) busca texto en todos tus chats —y por nombre de chat— o, si estás dentro de uno, solo en ese chat; dentro de un chat hay una barra para **escribir y enviar mensajes**; y los vídeos/audios se pueden **reproducir sin descargar** (un servidor local en 127.0.0.1 pide a TDLib solo los trozos que el reproductor necesita, en ventanas de 32 MB). Código: `telegram/TgStreamServer.kt`, `TdBackend.readBytes`.

Telegram con cuenta, secciones y bots: pestañas **⭐ Favoritos / Todos / Canales / Grupos / Bots / Personas** en la lista de chats; la ☆ de cada chat (o ⋮ → "Favorito" dentro del chat) lo marca como favorito. En un chat de **bot**: ⋮ → "Comandos del bot…" (los que el bot declara, se envían con un toque), ⋮ → "Teclado del bot…" (botones de respuesta) y, al tocar un mensaje del bot con botones, "🔘 Botones" (callbacks, enlaces y teclado inline). Los mensajes nuevos del chat abierto se actualizan solos.

Telegram con cuenta, v3: la **🔍 lupa está en la barra superior** (fuera del ⋮) y tiene dos búsquedas independientes: **Nombres** (tus chats y también canales, grupos y bots públicos de todo Telegram, con ☆ para favoritos) y **Mensajes** (texto en todos tus chats; dentro de un chat, solo en ese). En el chat: **responder** (pulsación larga), **copiar**, **eliminar** tus mensajes, **adjuntar** 📎 fotos y archivos, ver **fotos**, mensajes con el nombre del remitente, contador de **sin leer**, **unirse** a canales/grupos públicos y orden de chat (lo nuevo abajo). Las credenciales api_id/api_hash se guardan en el móvil y no hay que volver a pedirlas al actualizar; Telegram no permite entrar con usuario y contraseña: solo número + código + contraseña de dos pasos.

Telegram sin pedir claves: añade en GitHub → Settings → Secrets and variables → Actions los secretos **TG_API_ID** y **TG_API_HASH** (los de https://my.telegram.org → API development tools). El workflow "Build APK" los mete en la app al compilar (no quedan en el repositorio) y la app va directa a pedir teléfono → código → contraseña de dos pasos.

Listas desde Telegram (bot y cuenta): al tocar un archivo **.m3u / .m3u8 / .json / .txt** de un chat sale **"📥 Cargar como lista principal"** (la lista se abre en la pantalla principal con sus categorías, igual que una lista cargada de un archivo) o **"📋 Abrir lista de canales"** (elegir un canal suelto). Los **enlaces** del mensaje que apuntan a una lista (.m3u, .json, get.php…) tienen también "📥 Cargar lista del enlace". Código: `ui/TelegramImport.kt`.

App más ligera y caché de Telegram: las librerías nativas de TDLib van comprimidas y el build genera **una APK por arquitectura** (`app-arm64-v8a-debug.apk` para móviles/tablets modernos, la mitad de grande; `app-armeabi-v7a-debug.apk` para Fire TV/dispositivos de 32 bits; `app-universal-debug.apk` sirve en todos, como antes). Además, Telegram **borra solo los archivos descargados** (vídeos, fotos, subidas) al entrar y al salir de la pantalla de Telegram, respetando lo descargado en los últimos 15 minutos.

Telegram v4: **enlaces internos** (t.me/usuario, t.me/c/id/mensaje, t.me/+invitación, tg://…) se abren dentro de la app al tocar el mensaje ("✈️ Abrir en Telegram"; las invitaciones piden confirmar antes de unirse; un enlace a un mensaje salta a ese mensaje; ?start=x deja "/start x" escrito). **Foros** (supergrupos con temas): pestaña "Foros" en la lista; al abrir uno salen sus temas y dentro de cada tema se leen y envían mensajes. **Imágenes**: miniaturas en la lista y visor a pantalla completa al tocarlas (fotos y archivos de imagen). Botón **actualizar** (⟳) junto a la lupa; la lista de chats y el chat abierto también se refrescan solos al llegar mensajes. Los temas usan peticiones de TDLib que cambian entre versiones, por eso van por reflexión (`TdBackend.loadTopics`).

Telegram v5, **galería**: en canales, grupos y foros el chat se ve como una cuadrícula de **miniaturas** (solo fotos, vídeos, listas y enlaces de Telegram, cada uno con su texto debajo; los mensajes de texto no salen). Las listas (archivos .m3u/.m3u8/.json/.txt y enlaces a listas) llevan un icono de lista y la etiqueta LISTA; los enlaces de Telegram muestran la foto y el nombre del canal/grupo/bot al que llevan. Menú ⋮ → "Ver todos los mensajes" / "Ver solo fotos, vídeos y listas" para cambiar de vista (los chats privados y bots se abren con todos los mensajes). En la lista de chats cada uno lleva su foto de perfil. Si una tanda trae pocos medios, la app sigue cargando mensajes antiguos sola (hasta 6 veces); "Cargar más" al final.

## Navegación con mando (TV) en Telegram
- El foco se mantiene al refrescar (ya no salta al principio ni se pierde al bajar).
- Estrella de favorito más grande y con anillo de foco; mantener OK sobre un chat la marca/desmarca.
- Al volver de un chat, el foco regresa a ese chat.
- Filas de listas y botones de ranura muestran borde naranja con el foco.

## Telegram: galería con películas, listas y atrás
- Un vídeo y una imagen con el mismo nombre ("253-Peli (2025) -mkv" + "Peli (2025)") se unen: la imagen es la miniatura del vídeo.
- Los vídeos muestran título, año, nota y género de TMDb (carátula de TMDb si no hay imagen en el chat). Pulsación larga = ficha con sinopsis y reparto.
- Listas (m3u, json, txt…) y enlaces de Telegram sin foto tienen su propia imagen (morada / azul).
- Al abrir un enlace a otro chat, "atrás" vuelve al chat anterior, en el mismo sitio.

## Telegram: vídeos
- Pulsar un vídeo con datos de TMDb abre la ficha (carátula, nota, sinopsis, reparto) con "▶ Ver" debajo; sin datos se reproduce directo. Nunca se ofrece "descargar y reproducir".
- Pulsación larga (o mantener OK en la TV) guarda/quita el vídeo en favoritos; se ven en la pestaña "🎬 Vídeos" de Telegram.

## Telegram: listas subidas como archivo
- Un .m3u que Telegram marca como "audio/…" ya se reconoce como lista (antes se tomaba por vídeo). Si hay una imagen con el mismo nombre (aunque ponga "url=42"), es su miniatura.

## Widget y marcador
- Widget: iconos de pestaña y refrescar más grandes (≥46 dp) y nueva pestaña 🎬 con tus vídeos favoritos de Telegram (con carátula y nota de TMDb); al tocar uno abre Telegram y lo reproduce.
- Partidos: al tocar un partido (widget o aviso de gol) solo se abre un canal cuyo programa actual nombre a los DOS equipos en la guía; si no, avisa de que no lo encuentra.
- El marcador flotante del reproductor está abajo a la izquierda.

## Marcador flotante móvil y vídeos de Telegram
- El marcador del reproductor se arrastra por la pantalla (recuerda dónde lo dejas); al tocarlo va al canal que emite el partido (en la lista actual o buscando en todos los canales; si el partido es el que ves, avisa).
- Vídeos MP4 de Telegram que se quedaban "conectando": el servidor local abandona las conexiones antiguas del mismo archivo (peleaban entre sí por la descarga al pedir primero el final del MP4 y luego el principio) y el reproductor espera hasta 45 s al primer dato de un trozo lejano.
- Causa real de los MP4 de Telegram que no arrancaban: con la "pausa en directo" activada, el reproductor trataba el vídeo como un directo y lo grababa en un búfer (un MP4 con el índice al final no se puede reproducir así). Los vídeos de Telegram ya no entran en la pausa en directo.

## Telegram: varias versiones de un vídeo
- Los vídeos con el mismo nombre (mp4, mkv…) se juntan en una sola miniatura con "▶ ×N". La ficha trae un botón por versión ("Ver opción 1 · MP4 · 1080p · 1.4 GB"…); sin ficha de TMDb, una lista para elegir (o reproduce directo si solo hay una).
- La miniatura es la imagen del chat si existe; si no, la carátula de TMDb; si no, el fotograma del vídeo. Favoritos guarda todas las versiones a la vez.

## Telegram: series por capítulos
- Los vídeos "Serie 1x01", "Serie S01E02", "Serie T1 E03"… se juntan en una sola miniatura (con la imagen de la serie, o la de TMDb). La ficha muestra T1, T2… y un botón por capítulo (01, 02, 03…).

## Telegram: vídeos referenciados por enlace
- Una imagen (o un texto) con un enlace a otro mensaje de Telegram (t.me/c/<chat>/<mensaje>, t.me/c/<chat>/<tema>/<mensaje>, t.me/nombre/<mensaje>) se trata como un vídeo: la imagen es la miniatura, se agrupa con sus versiones o capítulos y, al pulsar Ver, la app busca el mensaje enlazado y reproduce su vídeo (hay que estar unido a ese canal o grupo).

### Telegram MP4: streaming más robusto
- `readBytes` ya no reinicia la descarga de TDLib en cada trozo de 512 KB (solo si la posición no está cubierta por la petición viva o ésta se paró), y si `GetFile` tarda no corta la conexión: reintenta.
- Si no llegan datos en 40 s se muestra un aviso "Telegram sin datos: pos/tam/off/pref" para diagnosticar.

### Telegram: chat estilo original
- Los mensajes (bots, chats privados, grupos y "ver todos los mensajes" de canales) salen como burbujas: los tuyos a la derecha, los demás a la izquierda, con nombre (en grupos), foto/miniatura dentro, archivo, texto con enlaces en azul y hora.
- Los botones en línea de los bots se ven directamente debajo del mensaje, en sus filas originales (`TgButton.row`); callback, enlaces (los de t.me se abren dentro) y los no compatibles avisan.
- El teclado de respuesta del bot sale encima de la caja de texto; el botón ⌨ lo oculta/muestra.
- Pulsar una burbuja con archivo o enlaces abre sus acciones (reproducir, ver foto…); mantener = responder/copiar/borrar.

### Telegram: reenviar, más controles y lista de chats completa
- **Lista de chats**: `TdBackend.loadChats` ahora pide a TDLib toda la lista (`LoadChats` hasta el final) de la lista principal y de los archivados (antes solo salía lo que ya estaba cargado, por eso faltaban canales/foros). Tiene un tope de 25 s para no dejar la pantalla vacía.
- **Favoritos**: se guarda la ficha (nombre, tipo, foto) de cada chat favorito; si Telegram no lo trae en la lista, se muestra igualmente y se pide aparte (`loadChat`).
- **Búsqueda de chats**: además de SearchChats/SearchPublicChats, SearchChatsOnServer y un filtro local sobre tu lista.
- **Chat que no carga**: `loadHistory` reintenta (hasta 4 veces) cuando el primer pedido a un chat sin caché devuelve pocos mensajes.
- **Reenviar** (`forwardMessage`): mantener pulsado un mensaje → "↪ Reenviar a…", o botón en la ficha del vídeo / lista de versiones. Selector con filtros (favoritos, canales, grupos y foros, personas y bots), buscador y, para foros, elección de tema.
- Mantener pulsado un mensaje también: copiar enlace del mensaje y editar (si es tuyo). Menú del chat: marcar como leído y salir del canal/grupo.

### Widget y "Continuar viendo" sin Telegram
- El widget solo tiene dos pestañas: ⭐ favoritos y ⚽ fútbol (quitadas "seguir viendo" y la de vídeos de Telegram; una pestaña vieja guardada vuelve a favoritos).
- `ContinueWatching` ignora lo que viene de Telegram (categoría "Telegram" o URL local `127.0.0.1/tg/`): no se guarda y lo ya guardado deja de mostrarse (app, Android TV y copia de seguridad).

### Siguiente capítulo automático y carpetas propias (Telegram)
- **Siguiente capítulo**: al reproducir un capítulo desde la ficha de una serie, los capítulos (uno por número, en orden de temporada y capítulo) pasan al reproductor como lista (`pendingChannelList`). Al acabar uno (`STATE_ENDED`, solo streams de categoría "Telegram"), aparece un cuadro abajo a la derecha con cuenta atrás de 10 s ("Ver ya" / "Cancelar") y salta al siguiente con `switchToIndex`. Cualquier cambio de capítulo o volver atrás en el vídeo cancela la cuenta atrás.
- **Carpetas propias**: la pestaña 🎬 Vídeos ahora empieza con la lista "⭐ Favoritos", tus carpetas y "➕ Nueva carpeta" (mantener pulsada una carpeta = cambiar nombre / borrar). Dentro de una carpeta, mantener pulsado un vídeo lo quita de ella. Para meter un vídeo: botón "📁 Añadir a carpeta…" en la ficha, o mantener pulsado un mensaje de vídeo en "ver todos los mensajes". Datos en `TelegramAccountPrefs` (`video_folders`).

## Navegador web integrado
Menú ⋮ de la pantalla principal → "🌐 Web" (`ui/WebActivity`): navegador con barra de direcciones (si escribes palabras, busca en Google), atrás/adelante/recargar, marcadores propios (mantener pulsado = borrar), versión de escritorio, abrir en otro navegador y borrar datos. Si una página carga un vídeo (.m3u8, .mpd, .mp4…), aparece el botón 🎬 con los vídeos detectados y se pueden reproducir en el reproductor de la app (con el Referer de la página). Los enlaces directos a vídeo preguntan si abrirlos en el reproductor. El vídeo de la página se ve a pantalla completa.

### Telegram: webs en el apartado Web y directos
- Las páginas web que aparezcan en Telegram (mensajes, botones de bots) se abren en el apartado Web de la app (`WebActivity`); los enlaces internos (t.me, tg://) siguen abriéndose con Telegram (`isTgLink`). Los enlaces directos a vídeo (.m3u8, .mp4…) siguen ofreciendo "▶ reproducir"; para reproducir en el reproductor una página web: mantener pulsado el mensaje → "▶ Reproducir el enlace".
- Directos: los chats con una llamada de vídeo/directo activa salen con "🔴 En directo" en la lista y en el menú del chat hay "🔴 Ver el directo en Telegram", que abre ese chat en la app oficial. Ver el directo dentro de esta app no está soportado (necesita la librería de llamadas de Telegram, que TDLib no incluye).

## Prueba: librería de llamadas de Telegram (ntgcalls)

Se ha añadido la dependencia `io.github.pytgcalls:ntgcalls:3.0.2` solo para comprobar que compila junto al resto (ABIs, TDLib). Aún no se usa: el siguiente paso sería recibir los directos de Telegram (la librería tiene recepción de fotogramas y de "broadcast parts").

Menú de Telegram → «Diagnóstico de llamadas (ntgcalls)»: muestra (y permite copiar) la lista real de clases y métodos de la librería, para escribir después la recepción de directos con los nombres exactos.

## Ver directos de Telegram dentro de la app (experimental)

En un chat con directo activo, menú ⋮ → «Ver el directo aquí (prueba)» (`LiveActivity`). TDLib se une al chat de vídeo y sirve los trozos del directo; ntgcalls los decodifica y la app pinta el vídeo (I420 → imagen) y reproduce el audio (`AudioTrack`). Toca la imagen para ocultar/mostrar el registro de depuración de abajo.

### Directo: unirse desde el chat

En un chat con directo activo aparece arriba el aviso rojo «🔴 Directo en vivo · Unirme» (como en Telegram). La pantalla del directo es a pantalla completa, gira con el móvil (botón ⟳ para forzarlo), muestra una barra superior (cerrar, título) al tocar y oculta el registro de depuración (pulsación larga sobre la imagen para verlo).

### Directo: GPU y mando de TV

El vídeo del directo se pinta con OpenGL (`YuvView`, shader YUV→RGB) en vez de convertir en la CPU: más fluido y menos batería. En TV, el aviso «Unirme» recibe el foco al abrir el chat y se resalta con borde blanco; dentro del directo, cualquier tecla del mando muestra la barra (✕ cerrar, 🔊 silenciar, ⟳ girar), con la selección resaltada; play/pausa o mute del mando silencian.

## Corrección: cierre por `startForeground()` en TV

`PlaybackService` ahora llama siempre a `startForeground()` con una notificación mínima al recibir `startForegroundService()` (y la quita si no hay reproducción), evitando el `RemoteServiceException` en Fire TV / Android TV.

## Directos de Telegram: sesión más estable

`LiveActivity` vigila la sesión: si pasan 12 s sin trozos, si varios fallan seguidos o si un sondeo cada 8 s responde `GROUPCALL_JOIN_MISSING`, se vuelve a unir solo (primero directo, luego salir y entrar con reintentos) con el mismo payload, sin reiniciar ntgcalls. El estado muestra el número de reconexiones.

## Corrección: Telegram a veces se quedaba "conectando"

Carrera al iniciar TDLib: la primera actualización podía llegar antes de que el cliente estuviera asignado y se perdían los parámetros de inicio. Ahora `send()` espera a que `start()` termine, y un vigilante pregunta el estado de autorización (`GetAuthorizationState`) si tras 5 s sigue en "conectando".

## Telegram: pósters con otro título

Si el póster dice "Serie (Título original)" y los capítulos "Serie - 1x01", ahora se asocian igualmente (se prueba el título sin paréntesis y el de dentro). Si aun así no coincide, se usa la imagen publicada justo antes de los capítulos.

## Telegram: enlaces con texto en mensajes de bots

Los enlaces ocultos tras un texto ("🔗 Opción 1 en reproductor") ahora se ven en azul y se pueden pulsar directamente dentro de la burbuja: se reproducen en el reproductor principal, con las demás opciones del mismo evento como lista para cambiar. Al pulsar el mensaje (o con el mando) se elige primero el evento (cada párrafo del mensaje) y después la opción.

## Ajustes TV y Telegram (favoritos ordenables, carpetas)

- **Android TV:** al salir de la app (Inicio, otra app) el reproductor se pausa siempre; ya no sigue sonando la radio ni el audio en segundo plano (eso queda para el móvil).
- **Telegram, volver de un chat:** el foco vuelve a la misma fila donde estabas (con reintentos hasta que la lista está pintada), en vez de saltar a la lupa.
- **Favoritos ordenables:** en la pestaña ⭐ Favoritos, pulsación larga (mantener OK en el mando) en un chat → mover arriba/abajo, al principio o al final, o quitarlo. El orden se guarda y va en la copia de seguridad.
- **📁 Carpetas:** nueva pestaña con tus carpetas de chats de Telegram (las compartidas por enlace salen primero, marcadas con 🔗). Al abrir una se ven sus chats.

## TV: el foco no salta a la lupa al volver

En la portada, al volver de una categoría (o del reproductor) el foco del mando se queda en la misma fila (`FocusKeeper`): se recuerda la última categoría abierta y se reintenta el foco hasta que la lista está pintada. Lo mismo dentro de la lista de canales de una categoría.

## Portada de bienvenida

Mientras no hay ninguna lista cargada (solo la lista de ejemplo "Demo"), el espacio de las categorías muestra un resumen de lo que hace la app, con el logo (`ic_launcher_app` recortado en **gota de agua**) fijo al fondo, detrás del texto (las tarjetas son algo transparentes).

**Dónde editar el texto:** `app/src/main/res/values/strings.xml`, las entradas `home_subtitle`, `home_start_hint` y, para cada tarjeta, `home_t1`…`home_t9` (título) y `home_d1`…`home_d9` (descripción). Para añadir o quitar tarjetas, la lista está en `setupHomeInfo()` de `MainActivity.kt`; el diseño en `res/layout/activity_main.xml` (`homePanel`) y `res/layout/item_home_info.xml`. Contenido: listas IPTV, reproductor, favoritos, guía y avisos, radio y cine, Telegram, navegador, móvil/TV y copia de seguridad. En cuanto cargas una lista (o buscas), se ven las categorías de siempre. En TV el panel se desplaza con las flechas.

## Portada de bienvenida: siempre al abrir, y detalle del programa en el reproductor

- La portada de bienvenida sale **siempre al abrir la app**, también con una lista cargada. Se cierra al pulsar un botón de lista (TV, Lista 2…), al buscar o al abrir un archivo. Tocar el logo de la cabecera la vuelve a mostrar. No reaparece al girar la pantalla ni al cambiar de modo claro/oscuro.
- En el reproductor, el cartel de abajo (al tocar la pantalla) ahora muestra, si la guía EPG trae datos del canal: el programa y el canal, el horario (ej. 15:30 — 17:00) con **barra de progreso** y debajo **«Después · 17:00–18:30 Título»** del siguiente programa. Se actualiza cada minuto y al aparecer.

## Póster del programa en el reproductor
Si la guía EPG (XMLTV) trae `<icon src="...">` para el programa actual, se muestra un pequeño póster a la derecha de la barra "ahora en emisión" del reproductor. Si no lo trae (o falla la carga) se oculta. Código: `PlayerActivity.applyEpgDetail()` y `videoNowPlayingPoster` en `activity_player.xml`.

## Botón de VPN gratuita
La app no incluye VPN propia (necesitaría servidores). El botón de VPN (portada y fila de botones de la lista) abre una VPN gratuita ya instalada (Proton VPN, Cloudflare WARP, WireGuard, Windscribe); si no hay ninguna, ofrece instalar Proton VPN o WARP (Play Store / tienda de Amazon) o abrir los ajustes de VPN del sistema. El icono/texto se pone verde cuando hay una VPN conectada. Para añadir otra app: `VpnHelper.KNOWN` y `<queries>` en `AndroidManifest.xml`.

Ajustes posteriores: la portada ya no tiene botón «Entrar a mis listas» (la última línea quedaba bajo la barra del móvil; ahora hay margen inferior). El orden de VPN es Cloudflare WARP, Windscribe, Proton VPN, WireGuard, y el diálogo explica abajo para qué sirve una VPN (texto `vpn_explain`). El icono de la fila muestra un escudo con la palabra VPN (`ic_vpn_label`).
