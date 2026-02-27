# Sync Core

## Descripción

**Sync Core** es una utilidad backend para ejecutar y gestionar un servidor Java (por ejemplo, Hytale). Su objetivo es **sincronizar y automatizar** el manejo de mods y assets, vigilar cambios en directorios, monitorear el TPS del servidor y descargar actualizaciones desde GitHub, todo desde una consola central.

Este README explica **cómo funciona y cómo usarlo**, no cómo compilarlo.

---

## Requisitos

- **Java 25** (versión usada para el proyecto).
- Conexión a internet **solo si** se usan actualizaciones desde GitHub.

---

## ¿Qué hace Sync Core?

Al iniciar:

1. Carga `config.yml`.
2. Determina la ruta del servidor (pregunta por consola si no es válida).
3. Crea carpetas locales:
   - `mods/`
   - `assets/`
4. Inicia el proceso del servidor (JAR configurado).
5. Activa watchers de carpetas.
6. Inicia el monitor de TPS.
7. Arranca una consola backend interactiva.

Al cerrar:

- Detiene el servidor.
- Sincroniza `mods/` local → `Server/mods`.
- Cierra watchers y el monitor TPS de forma segura.

---

## Componentes principales

- **Main**
  - Punto de entrada del sistema.
  - Inicializa configuración, watchers, monitor TPS y consola.

- **BackendConsole**
  - Consola interactiva para controlar el sistema en caliente.

- **JarProcessManager**
  - Inicia y controla el proceso Java del servidor.
  - Permite enviar comandos directamente al servidor.

- **WatcherRegistry / AssetsWatcher**
  - Observan carpetas locales o externas.
  - Sincronizan cambios sin reiniciar el servidor.

- **TpsMonitor**
  - Supervisa el TPS del servidor usando `monitor.yml`.

- **GithubService** (opcional)
  - Descarga mods/assets desde releases de GitHub.
  - Gestionado por `updates.yml`.

---

## Configuración básica

### `config.yml`

```yaml
server:
  basePath: "/ruta/al/servidor"
  jarName: "server.jar"
  args: "--assets ../Assets.zip --backup --backup-dir backups --backup-frequency 30"

watchers:
  - path: "/ruta/a/carpeta"
```

**Campos clave:**

- `server.basePath` → Ruta donde existe la carpeta `Server/`.
- `server.jarName` → JAR del servidor que se ejecutará.
- `server.args` → Argumentos pasados al servidor.
- `watchers` → Carpetas que se vigilan automáticamente.

---

### `monitor.yml`

Controla el comportamiento del monitor de TPS.

Puede recargarse en caliente desde la consola con:

```
backend reload-config
```

---

### `updates.yml`

Define repositorios de GitHub a comprobar y descargar.

Usado únicamente si `GithubService` está habilitado.

---

## Consola backend

Una vez iniciado Sync Core, puedes usar los siguientes comandos:

```
watch add <ruta>           Añade un watcher y lo guarda en config.yml
watch remove <ruta>        Elimina un watcher
watch list                 Lista watchers activos

backend send <comando>     Envía un comando al servidor
backend reload-config      Recarga monitor.yml

updates list               Lista repos configurados
updates check              Comprueba y descarga actualizaciones
updates download <repo>    Descarga un repo específico
updates download all       Descarga todos los repos

exit / quit                Cierre limpio del sistema
```

---

## Notas importantes

- Sync Core **no recompila** ni modifica el servidor, solo lo gestiona.
- Los watchers permiten desarrollo rápido sin reinicios constantes.
- Si `updates.yml` no existe, el sistema sigue funcionando normalmente.

---

## Licencia

MIT License