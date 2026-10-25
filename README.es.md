[English](README.md) · **Español**

# Extensiones para Mihon: Google Drive y Nyaa

Dos extensiones para [Mihon](https://mihon.app), publicadas en el mismo repositorio de extensiones:

- **Google Drive**: abre carpetas compartidas por enlace y busca mangas en todas sus subcarpetas.
- **Nyaa (Literature)**: busca en la categoría Literature de nyaa.si y lee los torrents con un
  cliente torrent integrado.

Las dos leen archivos **CBZ** (y ZIP) y **PDF**; Nyaa también lee carpetas de imágenes sueltas.
Todos los textos se pueden ver en español o en inglés (ajuste **Idioma** de cada fuente; por
defecto sigue el idioma del teléfono).

## Instalar

**Con un toque:** abre <https://kirougc.github.io/Mihon-GDrive-Extension/> en el teléfono y elige
*Abrir en Mihon*.

**O a mano:** en Mihon ve a **Más → Ajustes → Explorar → Repositorios de extensiones → Agregar** y pega:

```
https://raw.githubusercontent.com/KirouGC/Mihon-GDrive-Extension/repo/index.min.json
```

Después instala las extensiones desde **Explorar → Extensiones** y confía en ellas cuando Mihon lo pida.

## Google Drive

| En Drive | En Mihon |
| --- | --- |
| Una carpeta con archivos CBZ/PDF (`Berserk/Tomo 01.cbz`, `Tomo 02.cbz`…) | Un manga; cada archivo es un capítulo |
| Un CBZ/PDF suelto en la carpeta principal, junto a otras carpetas | Un manga de un solo capítulo |
| La carpeta principal solo tiene archivos (sin subcarpetas) | Un único manga con todos esos capítulos |

1. En **Explorar → Fuentes → Google Drive → ⚙️**, pega el enlace de tu carpeta
   (`https://drive.google.com/drive/folders/...`). Puedes poner varias, una por línea.
2. Abre la fuente. La primera vez recorre todas las carpetas; el resultado se guarda y se vuelve a
   escanear cada 24 horas (configurable) o cuando marques el filtro *Volver a escanear*.

Las subcarpetas se recorren hasta 15 niveles. Como portada se usa una imagen llamada `cover`,
`folder`, `poster` o `portada` si existe en la carpeta, si no la primera imagen, y si no la primera
página del primer PDF. Al abrir un capítulo el archivo se descarga entero a la caché del teléfono;
los PDF se convierten a imagen página por página en el propio teléfono.

**Clave de API (recomendada).** Sin clave, la extensión lee la vista pública de cada carpeta, que
puede no mostrar todos los archivos en carpetas muy grandes. Con una clave gratuita de la Drive API
el listado es completo: crea un proyecto en <https://console.cloud.google.com/>, activa
**Google Drive API**, crea una **Clave de API** en *Credenciales* y pégala en los ajustes.

## Nyaa (Literature)

- Busca solo en Literature, con filtros por categoría (traducido al inglés, a otro idioma o raw),
  remakes/trusted y orden. *Populares* ordena por seeders.
- Cada torrent es un manga. Sus capítulos salen del archivo `.torrent`: cada CBZ/PDF es un capítulo,
  y también cada carpeta con imágenes sueltas.
- Al abrir un manga el torrent ya se agrega (sin bajar nada) para ir encontrando peers.
- Los CBZ se leen **en streaming**: primero se baja solo el final del archivo (el índice del ZIP),
  después cada página pide con prioridad máxima las piezas que la contienen, y el resto del capítulo
  sigue bajando en orden de fondo. Las carpetas de imágenes funcionan igual, imagen por imagen. Los
  PDF sí se bajan enteros antes de abrirse.
- Solo hay un torrent activo y solo se piden los archivos de los capítulos abiertos. Tras 5 minutos
  sin leer, el torrent sale de la sesión y el cliente se apaga.
- Mientras baja, el teléfono también sube datos a otros (así funcionan los torrents). En los ajustes
  se puede limitar la subida (100 KB/s por defecto).
- Si la descarga no avanza durante 3 minutos (configurable) se cancela con un aviso; suele pasar con
  torrents viejos sin seeders.
- Los capítulos bajados se guardan en la caché (2 GB por defecto) y se borran los más viejos.

## Compilar

Cada push a `main` compila las dos extensiones con GitHub Actions y publica el repositorio de
extensiones en la rama `repo`. Los APK también quedan como artefacto en cada ejecución.

**Firma.** Mihon solo actualiza una extensión si cada versión está firmada con la misma clave. El
workflow necesita estos secretos del repositorio: `KEYSTORE_BASE64` (el `.jks` en base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`. Para crear una clave:
`keytool -genkeypair -v -keystore firma.jks -alias mihon -keyalg RSA -keysize 2048 -validity 10000`
y luego `base64 -w0 firma.jks`.

**Página de un toque.** La página *Agregar a Mihon* se publica con GitHub Pages: en
**Settings → Pages** elige *Source* = **GitHub Actions** una sola vez.

**En local** (JDK 17 y el SDK de Android):

```
./gradlew :src:googledrive:assembleRelease :src:nyaa:assembleRelease
```

## Notas técnicas

- Se compila contra extensions-lib 1.4 (versión `1.4.x`), que cargan tanto Mihon estable como las
  versiones nuevas. Las interfaces de la librería están copiadas en `stubs/` (Apache 2.0) y no se
  empaquetan en el APK.
- `lib/archive` tiene el lector de CBZ/PDF y la ayuda de traducciones que comparten las dos extensiones.
- Nyaa incluye libtorrent4j con sus librerías nativas (arm64, arm y x86_64). Como Mihon carga las
  extensiones sin ruta de librerías nativas, `NativeLoader` busca `libtorrent4j.so` (o lo extrae del
  APK) y se lo indica a libtorrent4j.
- Las páginas se sirven con URLs internas (`http://gdrive.local/...`, `http://nyaa.local/...`) que un
  interceptor de OkHttp responde sin salir a la red.
