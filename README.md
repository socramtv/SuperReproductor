# Súper Reproductor

App Android nativa (Kotlin) que lee una playlist en JSON y reproduce cada
canal con **ExoPlayer / Media3**, con soporte para **DASH (.mpd)**,
**HLS (.m3u8)** y vídeo progresivo (mp4, etc.), cabeceras HTTP propias y
DRM **ClearKey** opcional.

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
          "drm": { "keyId": "base64url...", "key": "base64url..." }
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

Alias aceptados por campo: `url`/`uri`, `icon`/`image`, `type`/`extension`.
Para DRM ClearKey se acepta cualquiera de estas tres formas: `drm: {keyId,
key}`, `kid`+`key` sueltos, o `license_key` con el JSON de ClearKey ya
armado (`{"keys":[{"kty":"oct",...}],"type":"temporary"}`). `kid`/`key`
pueden venir en base64url o en hexadecimal — se normalizan solos.

- `type`/`extension`: `"DASH"`/`"mpd"`, `"HLS"`/`"m3u8"`, o cualquier otro
  valor → se trata como progresivo (URL directa a un archivo de vídeo).
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

Desde la app, usa el icono de carpeta (barra superior) para elegir un
archivo `.json` con cualquiera de estos formatos desde tu dispositivo. La
app recuerda el último archivo cargado y lo reabre al iniciar; si no hay
ninguno, carga una lista de ejemplo (`app/src/main/assets/sample_playlist.json`)
con dos streams públicos de prueba (Big Buck Bunny en DASH y el stream de
ejemplo de Apple en HLS).

## Estructura

```
app/src/main/java/com/example/superplayer/
  model/    Stream, Category, PlaylistData, DrmInfo
  data/     PlaylistRepository (parseo JSON), FavoritesStore, AppPrefs
  ui/       MainActivity (categorías + buscador), StreamListActivity
  player/   PlayerActivity (ExoPlayer/Media3)
```

Funciones incluidas: categorías, buscador (filtra canales por nombre,
tanto en la portada como dentro de una categoría), favoritos persistentes
(categoría "⭐ Favoritos" arriba de todo cuando hay alguno) y carga de
JSON desde el propio dispositivo.

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

## Compilar sin PC (GitHub Actions)

El proyecto incluye `.github/workflows/build.yml`. Al subirlo a un repo de
GitHub, ese workflow compila un APK de depuración en la nube (JDK 17 +
Android SDK + Gradle) y lo deja descargable como "artifact" del run,
sin que tu teléfono tenga que instalar nada pesado.

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
