# Google Drive para Mihon

Extensión para [Mihon](https://mihon.app) que abre una o varias carpetas de Google Drive
compartidas por enlace y busca mangas en todas sus subcarpetas. Lee archivos **CBZ** (y ZIP)
y **PDF**.

## Cómo organiza tus carpetas

| En Drive | En Mihon |
| --- | --- |
| Una carpeta con archivos CBZ/PDF (`Berserk/Tomo 01.cbz`, `Tomo 02.cbz`…) | Un manga; cada archivo es un capítulo |
| Un CBZ/PDF suelto en la carpeta principal, junto a otras carpetas | Un manga de un solo capítulo |
| La carpeta principal solo tiene archivos (sin subcarpetas) | Un único manga con todos esos capítulos |

Las subcarpetas se recorren hasta 15 niveles. Como portada se usa una imagen llamada
`cover`, `folder`, `poster` o `portada` si existe en la carpeta, si no la primera imagen,
y si no la primera página del primer PDF. Los mangas que solo tienen CBZ aparecen sin portada
hasta que se abren.

## Uso

1. Instala la extensión (ver abajo) y confía en ella cuando Mihon lo pida.
2. En **Explorar → Fuentes → Google Drive → ⚙️**, pega el enlace de tu carpeta
   (`https://drive.google.com/drive/folders/...`). Puedes poner varias, una por línea.
3. Abre la fuente. La primera vez recorre todas las carpetas; el resultado se guarda y se
   vuelve a escanear solo cada 24 horas (configurable) o cuando marques el filtro
   *Volver a escanear*.

Al abrir un capítulo, el CBZ o PDF se descarga entero a la caché del teléfono y luego se
leen sus páginas. Los PDF se convierten a imagen página por página en el propio teléfono.

### Clave de API (recomendada)

Sin clave, la extensión lee la vista pública de cada carpeta, que puede no mostrar todos los
archivos en carpetas muy grandes. Con una clave gratuita de la Drive API el listado es completo:

1. Entra en <https://console.cloud.google.com/>, crea un proyecto y activa **Google Drive API**.
2. En **APIs y servicios → Credenciales → Crear credenciales → Clave de API**.
3. Pega la clave en los ajustes de la extensión.

## Instalar

Cada push a `main` compila la extensión con GitHub Actions y publica un repositorio de
extensiones en la rama `repo`. En Mihon: **Más → Ajustes → Explorar → Repositorios de
extensiones → Añadir** y pega:

```
https://raw.githubusercontent.com/<usuario>/<repositorio>/repo/index.min.json
```

El APK también queda como artefacto en cada ejecución de Actions.

### Firma

Mihon solo actualiza una extensión si cada versión está firmada con la misma clave. El
workflow necesita estos secretos del repositorio (**Settings → Secrets and variables → Actions**):

- `KEYSTORE_BASE64`: el archivo `.jks` en base64
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Para crear una clave: `keytool -genkeypair -v -keystore firma.jks -alias mihon -keyalg RSA -keysize 2048 -validity 10000`
y luego `base64 -w0 firma.jks`.

## Compilar en local

Requiere JDK 17 y el SDK de Android:

```
./gradlew :extension:assembleRelease
```

## Notas técnicas

- Se compila contra extensions-lib 1.4 (versión `1.4.x`), que cargan tanto Mihon estable
  como las versiones nuevas. Las interfaces de la librería están copiadas en `stubs/`
  (Apache 2.0) y no se empaquetan en el APK.
- Las páginas se sirven con URLs internas (`http://gdrive.local/...`) que un interceptor de
  OkHttp responde sin salir a la red, extrayendo la imagen del CBZ o renderizando el PDF con
  `PdfRenderer` de Android.
