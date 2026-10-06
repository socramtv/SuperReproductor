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

### Copia de seguridad sin conexión

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
  extra) con tus favoritos, las URLs de los 5 huecos de lista, el filtro y
  el orden de categorías de cada lista y el modo claro/oscuro.
- **Importar copia de seguridad:** elige ese archivo. Los favoritos se
  **suman** a los que ya tengas (no se borra ninguno); las URLs, los
  filtros y el modo claro/oscuro se sustituyen por los de la copia. Al
  terminar sale un aviso con cuántos favoritos nuevos, URLs y filtros entraron.
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

## Botones del reproductor

Debajo de la barra de progreso, a la izquierda del engranaje de Media3 (el de
velocidad), hay una fila con cuatro botones:

1. **Vídeo y audio** (un solo botón): menú con *Vídeo*, *Audio* (para elegir
   calidad/pista) y *Audio en segundo plano: sí/no*.
2. **Subtítulos:** selector de subtítulos (avisa si el canal no trae).
3. **Formato de pantalla:** Ajustar / Estirar / Zoom.
4. **Imagen en imagen** (ventana flotante).

El botón de **Chromecast** se queda arriba a la derecha. Todos aparecen y
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
