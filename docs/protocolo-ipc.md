# Protocolo IPC — Launcher ↔ Agente

**Versión 1 — CONGELADO (junio 2026).** Segundo contrato fundacional, junto a [`modelo-de-datos.md`](modelo-de-datos.md). Define cómo se comunican el launcher (Rust/Tauri) y el agente (Java, dentro del proceso de Minecraft). Contexto del proyecto en [`proyecto.md`](proyecto.md). Cambiarlo a partir de ahora implica una versión nueva bajo las reglas de evolución de §2.1.

**Fuera de alcance:** la comunicación mod thin ↔ agente. Viven en el mismo proceso; son llamadas directas en memoria, no necesitan protocolo.

---

## 1. Transporte y descubrimiento

### 1.1 Transporte: TCP sobre loopback

`127.0.0.1`, puerto efímero. Razones frente a las alternativas:

- Unix domain sockets requieren Java 16+ (`AF_UNIX` en NIO). Las instancias viejas de Minecraft corren Java 8. La universalidad manda.
- Named pipes de Windows no son portables.
- TCP loopback funciona en los tres sistemas operativos y en cualquier Java desde 8.

### 1.2 Quién escucha: el agente

El **agente escucha**, el **launcher conecta**. La razón es la asimetría de ciclos de vida: el launcher puede cerrarse y reabrirse mientras el juego sigue corriendo. Si el launcher escuchara, un launcher reiniciado tendría un puerto nuevo y el agente no sabría encontrarlo. Con el agente escuchando, cualquier launcher futuro puede redescubrir el juego en marcha.

### 1.3 Descubrimiento: archivo de contacto

1. Antes de lanzar, el launcher genera un **token** aleatorio (256 bits) y lo pasa al agente vía propiedad del sistema: `-Dmksa.token=<token>`. El token nunca toca el disco.
2. El agente, en `premain`, abre su puerto efímero y escribe el **archivo de contacto** `<instancia>/mksa/agent.json`:

```json
{ "v": 1, "pid": 12345, "port": 49152, "started": "2026-06-12T18:00:00Z" }
```

3. El launcher observa la aparición del archivo, conecta al puerto y se autentica con el token (§3.1). El archivo **no contiene el token** — solo dice dónde llamar, no da derecho a entrar.
4. Al morir el proceso del juego, el archivo queda huérfano; el launcher valida `pid` antes de fiarse, y el agente lo sobreescribe en cada arranque.

Esto da gratis: reconexión tras reinicio del launcher, varias instancias simultáneas (un archivo por instancia), y detección de juego-ya-corriendo.

### 1.4 Seguridad

- Bind exclusivamente a `127.0.0.1`.
- Primer mensaje obligatorio: `hello` con el token (§3.1). Token incorrecto o timeout de 5 s sin `hello` → el agente cierra la conexión. Tres fallos → el agente deja de aceptar conexiones durante 60 s.
- Una sola conexión de launcher activa a la vez; una nueva **autenticada** desplaza a la anterior. Esto cubre el caso de un launcher crasheado cuya conexión TCP queda medio abierta: el launcher reabierto debe poder reconectar sin esperar a que la conexión zombi expire. Nota: esto regula *conexiones de observación* al juego, no procesos del juego — la unicidad del proceso la regula §1.5.

### 1.5 Instancia única: el juego nunca se abre dos veces

Lanzar una instancia que ya está corriendo está **prohibido por contrato**. La secuencia obligatoria de "Jugar" en el launcher:

1. **Leer `<instancia>/mksa/agent.json`.** Si existe, validar vida real: ¿el `pid` corresponde a un proceso vivo? Si además responde a `ping` por el puerto, la instancia está corriendo sin duda.
2. **Instancia viva → no lanzar.** El launcher reconecta al agente (desplazando la conexión anterior si la hubiera, §1.4) y trae la ventana del juego al frente si el SO lo permite. Para el jugador, pulsar "Jugar" sobre un juego abierto significa "llévame a él", nunca "ábrelo otra vez".
3. **Archivo huérfano (pid muerto o puerto sin respuesta) → sobreescribir y lanzar.** Un crash del juego no debe bloquear el siguiente lanzamiento.
4. **Ventana ciega:** entre el spawn de la JVM y la escritura de `agent.json` por el agente en `premain` pasan unos segundos donde el archivo aún no existe. Para cubrirla, el launcher escribe `<instancia>/mksa/launching.lock` (su pid + timestamp) **antes** del spawn y lo borra cuando aparece `agent.json` o el lanzamiento falla. Otro intento de lanzar — desde la misma ventana, otra ventana del launcher u otro proceso del launcher — encuentra el lock, valida su pid y timestamp (huérfano si el pid está muerto o han pasado >60 s sin `agent.json`), y se niega.

La fuente de verdad de "¿está corriendo?" es siempre la vida del proceso (`pid` + `ping`), nunca la mera existencia de archivos: los archivos localizan y serializan, los procesos prueban.

## 2. Encuadre y codificación

**NDJSON**: un mensaje = un objeto JSON = una línea terminada en `\n`, UTF-8. Sin prefijos de longitud, sin binario en el marco.

- Depurable con netcat y legible en los logs de observabilidad.
- Los payloads binarios escasos (iconos PNG) van como base64 dentro del JSON. El NBT pesado (archivos de restauración) **nunca cruza el socket** — se queda en el lado del agente; el protocolo mueve metadata.
- Límite duro de 4 MiB por línea; lo que no quepa se pagina (§4.6).

### 2.1 Sobre de mensaje

Tres formas, discriminadas por `t`:

```json
{ "t": "req", "id": 7, "m": "tx.preview", "p": { ... } }
{ "t": "res", "id": 7, "ok": { ... } }
{ "t": "res", "id": 7, "err": { "code": "TX_CONFLICT", "msg": "...", "data": { ... } } }
{ "t": "evt", "m": "tx.progress", "p": { ... } }
```

- `req`/`res`: petición con `id` (entero, monótono por emisor) y exactamente una respuesta con el mismo `id`. **Ambos lados** pueden iniciar peticiones.
- `evt`: notificación sin respuesta. El receptor ignora eventos cuyo `m` no conoce.
- Reglas de evolución (las mismas del modelo de datos): campos desconocidos se ignoran; método desconocido → `err METHOD_UNKNOWN`; añadir métodos, eventos o campos opcionales no rompe la versión; cambiar semántica o eliminar, sí.

### 2.2 Hilos

El agente atiende el socket desde un hilo de IO dedicado. **El hilo del juego nunca bloquea en el socket**: las peticiones que necesitan el estado del juego se encolan al hilo del servidor y responden asíncronamente (por eso todo es `id`-correlacionado, no orden-correlacionado).

## 3. Ciclo de vida de la sesión

### 3.1 Handshake

```json
→ { "t": "req", "id": 1, "m": "hello",
    "p": { "proto": 1, "side": "launcher", "version": "0.3.0", "token": "<token>" } }
← { "t": "res", "id": 1, "ok": { "proto": 1, "side": "agent", "version": "0.3.0",
    "session": "a1b2c3", "boot": "game-ready" } }
```

- `proto` es la versión del protocolo (entero). Cada lado anuncia la suya; rige la **menor**. Si la menor es inferior a la mínima soportada por el otro → `err PROTO_INCOMPATIBLE` y cierre.
- `session` identifica el arranque del juego: cambia en cada proceso nuevo. El launcher lo usa para distinguir reconexión (misma sesión → pedir solo deltas) de juego nuevo (pedir snapshot completo).
- `boot` es la etapa de arranque actual (§3.2), para que un launcher que conecta tarde sepa dónde está el juego sin esperar eventos.

### 3.2 Etapas de arranque

El agente emite `evt boot.stage` al cruzar cada etapa, en orden:

`premain` → `loader-detected` → `mappings-resolved` → `registries-frozen` → `wcg-ready` → `game-ready`

Cada evento lleva `{ "stage": "...", "detail": { ... } }` (p. ej. `loader-detected` incluye `{ "loader": "neoforge", "loaderVersion": "..." }`). Si una etapa falla, `evt boot.failed` con la etapa y el error — es la señal del launcher para mostrar diagnóstico en vez de esperar.

### 3.3 Mantenimiento y cierre

- `ping` ↔ `ok {}` — keepalive a discreción del launcher (sugerido: 10 s). Dos pings sin respuesta → reconectar.
- `evt shutdown { "reason": "user-quit" | "crash" | "relaunch" }` — cortesía del agente al salir, cuando alcanza a emitirla. El launcher nunca depende de recibirla (valida `pid`).

## 4. Catálogo de métodos

Convención de nombres: `dominio.acción`. Dominios v1: `hello/ping`, `state`, `wcg`, `tx`, `obs`, `relaunch`, `game`.

### 4.1 `state` — estado de la instancia

| Método | → params | ← ok | Notas |
|---|---|---|---|
| `state.snapshot` | `{}` | versión MC, loader, época actual, lista de mods | El "estado inicial" de la Fase 2 |
| `state.mods` | `{}` | `[ { id, version, name, namespaces, tier, deps, enabled } ]` | `tier` ∈ 0–3 (§proyecto 3.4) |
| `state.epoch` | `{}` | `{ epoch, hash, parent, diff }` | Espejo de la tabla `epochs` |

### 4.2 `wcg` — World Contribution Graph

| Método | → params | ← ok | Notas |
|---|---|---|---|
| `wcg.definitions` | `{ ns, reg?, page? }` | definiciones del namespace, paginado | Plano A |
| `wcg.counts` | `{ ns }` | `{ blocks, entities, containerItems, chunks, chunksLoaded }` | Lectura del agregado SQLite — O(1), la base del translator |
| `wcg.refs` | `{ ns }` | aristas entrantes: qué definiciones ajenas referencian a este ns | Para el preview fino de cascada |
| `wcg.icon` | `{ id }` | `{ png: "<base64>", size: 32 }` | Renderizado por el juego; cacheable por `id` + época |

### 4.3 `tx` — transacciones de modset

| Método / evento | Dirección | Contenido |
|---|---|---|
| `tx.preview` | L→A req | `p: { disable: [ns…], enable: [ns…] }` → `ok: { txId, cascade: [ns…], brokenRefs: [...], counts: {…}, mechanism: "in-process" \| "relaunch", guarantee: "t0"…"t3" }` |
| `tx.apply` | L→A req | `p: { txId }` → `ok: { status }`. Solo válido sobre un preview vigente; el preview caduca si la época cambia |
| `tx.progress` | A→L evt | `{ txId, phase: "snapshot" \| "sweep" \| "reload" \| "restore", done, total }` |
| `tx.status` | A→L evt | `{ txId, status: "APPLIED" \| "APPLIED_PARTIAL", pendingChunks, conflicts: [...] }` — terminal |
| `tx.list` | L→A req | historial de transacciones (espejo de la tabla `tx`) |

El mod thin dispara transacciones por su vía in-process; el agente **emite los mismos `tx.progress`/`tx.status` al launcher** sea quien sea el iniciador. El launcher es siempre observador completo.

### 4.4 `obs` — observabilidad (modelo de datos §4.5)

| Método / evento | Contenido |
|---|---|
| `obs.enable` / `obs.disable` | `p: { sink: "buffer" \| "file" }` — activa el ring buffer o el log JSONL |
| `obs.tail` | `p: { n }` → últimos `n` eventos drenados del ring buffer |
| `obs.subscribe` / `obs.unsubscribe` | tras subscribe, el agente emite `evt obs.event` por cada evento drenado |
| `evt obs.dropped` | `{ count }` — el agente **descarta antes que bloquear**: si el launcher no consume a tiempo, se pierden eventos de observabilidad y se informa cuántos. La observabilidad jamás ejerce contrapresión sobre el juego |

### 4.5 `relaunch` — coordinación del relaunch caliente

Reservado en v1 con el flujo mínimo; los detalles finos (presupuesto de RAM, traspaso de ventana) se especifican cuando se implemente la capa:

1. `relaunch.request` (A→L req): el agente decide que la transacción requiere relaunch (Tier 3 / fallo del camino in-process). `p: { txId, modset, playerState: { dim, pos, … } }`.
2. El launcher arranca la segunda JVM (mismo protocolo, token nuevo), espera su `boot.stage: game-ready`.
3. `relaunch.handoff` (L→A req, al proceso viejo): orden de guardar mundo y salir. El proceso nuevo carga y el launcher intercambia ventanas.
4. Si la segunda JVM falla en arrancar, el proceso viejo sigue intacto: el relaunch es **abortable hasta el handoff**.

### 4.6 Paginación

Toda respuesta potencialmente grande (`wcg.definitions`, `tx.list`, `obs.tail`) acepta `page: { cursor?, limit? }` y devuelve `{ items: […], next: "<cursor>" | null }`. Cursor opaco; `limit` máximo 1000.

## 5. Errores

`err.code` es un string de un conjunto cerrado (ampliable por versión):

| Código | Significado |
|---|---|
| `AUTH_FAILED` | token incorrecto en `hello` |
| `PROTO_INCOMPATIBLE` | sin versión común utilizable |
| `METHOD_UNKNOWN` | método no reconocido (regla de evolución) |
| `BAD_PARAMS` | params inválidos para el método |
| `NOT_READY` | el método requiere una etapa de arranque posterior (`data.requires: "wcg-ready"`) |
| `TX_CONFLICT` | preview caducado, época cambiada o transacción concurrente |
| `TX_UNKNOWN` | `txId` desconocido |
| `BUSY` | el agente rechaza temporalmente (recarga en curso) |
| `INTERNAL` | fallo interno; `data` lleva detalle para diagnóstico |

## 6. Mapa protocolo ↔ fases

| Fase | Subconjunto necesario |
|---|---|
| F1 launcher | descubrimiento, `hello`, `ping`, `boot.stage`, `shutdown` |
| F2 agente/WCG | + `state.*`, `wcg.definitions`, `obs.*` |
| F3 hot reload | + `tx.*`, `relaunch.*` |
| F4 translator | + `wcg.counts`, `wcg.refs`, `wcg.icon` |

## 7. Invariantes del contrato

1. El hilo del juego nunca bloquea en el socket; toda respuesta dependiente del juego es asíncrona vía `id`.
2. El token nunca se escribe a disco; el archivo de contacto solo localiza, no autoriza.
3. El launcher nunca depende de recibir `shutdown`; la verdad sobre la vida del proceso es el `pid`.
4. El NBT pesado no cruza el socket; el protocolo mueve metadata y agregados.
5. La observabilidad descarta antes que bloquear, e informa de lo descartado (`obs.dropped`).
6. Campos desconocidos se ignoran; métodos desconocidos devuelven `METHOD_UNKNOWN`; eventos desconocidos se ignoran. Añadir es gratis; cambiar o quitar es versión nueva.
7. El launcher es observador completo: toda transacción emite su progreso por el socket, la inicie quien la inicie.
8. Un preview de transacción caduca cuando cambia la época; `tx.apply` sobre un preview caducado falla con `TX_CONFLICT`, nunca aplica a medias un plan obsoleto.
9. Una instancia corriendo nunca se lanza por segunda vez: "Jugar" sobre un juego vivo significa reconectar y enfocar, jamás un proceso nuevo (§1.5). La vida se prueba con `pid` + `ping`, no con la existencia de archivos.
