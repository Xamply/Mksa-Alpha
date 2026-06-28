# Modelo de datos — Ledger y World Contribution Graph

**Versión 1 — CONGELADO (junio 2026).** Todo componente posterior (agente, launcher, translator, transacciones de activación/desactivación) se escribe contra este documento. Cambiarlo a partir de ahora implica migración y una nueva versión.

---

## 1. Principio rector: no duplicar lo que Minecraft ya codifica

La identidad del contenido ya es intrínseca al almacenamiento de Minecraft:

- Las **palettes** de cada sección de chunk listan los blockstates distintos presentes. Saber si una sección contiene bloques de `create:` es O(palette) (~decenas de entradas), no O(4096).
- Cada entidad serializa su `EntityType` (un `ResourceLocation`).
- Cada `ItemStack` serializa su item id.

Por tanto el ledger **no almacena qué bloques existen** — eso ya lo dice el chunk. El ledger almacena únicamente lo que Minecraft *no* codifica:

1. **Agencia** — qué namespace ejecutó cada mutación (el chunk sabe que hay cobblestone; no sabe que lo colocó una máquina de Create).
2. **Índices y agregados** — dónde buscar sin recorrer el mundo: qué chunks contienen contribuciones de qué namespace, qué contenedores guardan items modded, conteos globales.
3. **Sellos de época** — bajo qué conjunto de mods fue procesado cada chunk por última vez.
4. **Contribuciones de runtime** — handlers, canales, mixins, attachments: cosas que no se persisten en el mundo.

Esto mantiene el ledger pequeño por construcción y elimina toda posibilidad de contradicción entre ledger y mundo respecto a identidad: la identidad siempre se lee del dato real.

## 2. Identidad

| Concepto | Forma | Notas |
|---|---|---|
| Mod | `modId` + versión + hash del jar | Del metadata del loader (`fabric.mod.json`, `mods.toml`, …) |
| Namespace | string (`create`, `alexscaves`, `minecraft`) | Atribución de contenido. Relación namespace→mod es n:1, capturada al interceptar registros durante init |
| Definición | `ResourceLocation` (`namespace:path`) + tipo de registro | Bloque, item, entidad, dimensión, bioma, receta, feature, … |
| Instancia | referencia a definición + ubicación | Nunca es un nodo individual persistido; vive en índices agregados |
| Época (`epoch`) | contador monótono `u64` + hash del modset | Ver §6 |
| Actor | namespace, o los especiales `#player`, `#worldgen`, `#unknown` | Resuelto por classloader de la clase llamante, cacheado por clase |

## 3. Los tres planos del World Contribution Graph

El WCG no es una estructura única: son tres planos con ciclos de vida distintos, unidos por aristas.

### Plano A — Definiciones (qué añade cada mod)

Construido una vez por época, durante la inicialización, interceptando los registros. Inmutable mientras la época no cambia.

- Nodos: `Mod`, `Namespace`, `Definición` (tipada).
- Aristas: `Mod —declara→ Namespace`, `Namespace —posee→ Definición`, `Mod —dependeDe→ Mod` (dura/blanda, del metadata del loader).
- **Aristas de referencia entre definiciones**: `Receta —usa→ Item`, `LootTable —suelta→ Item`, `Tag —contiene→ Bloque`, `Feature —coloca→ Bloque`, `Bioma —genera→ Feature`. Se extraen parseando los datapacks tras el init. Son las que permiten el preview de cascada fino: *"desactivar Create rompe 14 recetas de Farmer's Delight que usan sus items"* — dependencia real que el metadata del loader no declara.

### Plano B — Runtime (qué hace cada mod en el proceso)

Construido en vivo en cada arranque, interceptando los puntos de suscripción. **No se persiste nunca** — se reconstruye solo.

- Suscripciones a event buses: (bus, referencia al listener, namespace propietario).
- Canales de red registrados.
- Tick handlers y tareas programadas.
- Mixins aplicados: (clase objetivo, config de mixin, propietario) + referencia al bytecode pre-mixin conservado.
- Tipos de attachment/capability declarados.

Este plano es el insumo de la desactivación Tier 2/3: desuscribir, cerrar canales, re-tejer clases vanilla.

### Plano C — Instancias (dónde existe cada contribución en el mundo)

Mantenido por el ledger (§4). Es la unión de:

- Índice por chunk (sección NBT `mksa:prov`, §5.1) — fuente de verdad.
- Índice por jugador (§5.2) — fuente de verdad.
- Agregado global SQLite (§5.3) — caché reconstruible.

Aristas implícitas: `Definición —existeEn→ Chunk (conteo)`, `Contenedor —contiene→ Items de Namespace (conteo)`.

## 4. El ledger en runtime

### 4.1 El evento (transitorio, nunca persistido tal cual)

```
LedgerEvent {
  kind:      BLOCK_SET | BLOCK_REMOVE | ENTITY_ADD | ENTITY_REMOVE
           | ITEM_DELTA | ATTACH | DETACH | CHUNK_GEN
  content:   ResourceLocation        // identidad de la cosa
  actor:     namespace | #player | #worldgen | #unknown
  dim:       ResourceLocation
  where:     BlockPos | entityUUID | ContainerRef
  delta:     int                     // para ITEM_DELTA (puede ser negativo)
  gameTime:  long                    // solo para ordenación/debug
}

ContainerRef = BlockPos(BE) | entityUUID | playerUUID, slot opcional
```

Camino caliente: bitset por raw ID ("¿es modded?") construido al congelar el registro; comprobación O(1) en cada mutación. Si contenido y actor son vanilla → no se emite evento (el caso del 99%). Los eventos se encolan lock-free y se drenan una vez por tick en el hilo del servidor.

### 4.2 Compactación inmediata

Los eventos **no se acumulan como historial**. Al drenar, cada evento se aplica al conjunto vivo y se descarta:

- `BLOCK_SET(create:gear)` + posterior `BLOCK_REMOVE` en la misma posición → se anulan; el índice queda como si nada.
- `ITEM_DELTA` se suma al conteo del contenedor; conteo 0 → la entrada del contenedor desaparece.
- El ledger persiste **estado actual de procedencia**, jamás un log de sucesos. Su tamaño es proporcional al contenido modded vivo, no al tiempo de juego.

### 4.3 Qué entra en el conjunto vivo

| Mutación | ¿Se indexa? | Qué se guarda |
|---|---|---|
| Bloque modded colocado/quitado | Sí | Marca el chunk como sucio para `mksa:prov`; actualiza agregado |
| Bloque vanilla por actor modded | Solo agencia (§4.4) | Entrada esparcida pos→actor |
| Bloque vanilla por actor vanilla | No | — |
| Entidad modded añadida/quitada | Sí | Conteo por chunk; el UUID y tipo ya viven en el chunk de entidades |
| Item modded entra/sale de contenedor | Sí | `ContainerRef` → conteo por namespace |
| Attachment modded sobre objeto vanilla | Sí | pos/UUID → namespace del attachment |
| Chunk generado con features modded | Sí | namespaces de features que participaron (del Plano A) |

### 4.4 Agencia: política por defecto

La agencia se **registra siempre** que actor ≠ propietario del contenido y el actor es modded, pero la desactivación por defecto **no la consume**: contenido vanilla causado por un mod permanece en un mundo sin ese mod. El mapa de agencia existe para habilitar en el futuro un modo "limpieza profunda" opcional. Registrar ambos ejes ahora es barato; reconstruirlos después es imposible.

**Límite estricto: agencia = metadata mínima.** Por posición/referencia se guarda a lo sumo **un namespace de actor** (último escritor gana). Sin timestamps, sin cadenas causales, sin historial de actores, sin identidad de jugador. La agencia responde una sola pregunta — "¿qué namespace causó el estado actual de esto?" — y nada más. El ledger no es un sistema de auditoría; cualquier necesidad de auditoría se resuelve en la capa de observabilidad (§4.5), nunca ampliando este esquema.

### 4.5 Observabilidad: fuera del modelo persistente

Los eventos del ledger son transitorios (§4.2) y eso no cambia. Para depurar comportamiento, el agente expone una **capa de observabilidad separada**, con tres propiedades duras:

1. **Nunca se persiste con el mundo** ni dentro de `mksa:prov` ni en `index.db`.
2. **Nunca se lee de vuelta** por ningún flujo del sistema — es solo-escritura hacia fuera; no es fuente de verdad de nada.
3. **Apagada por defecto**; coste cero cuando está apagada (la emisión cuelga del mismo drenado por tick, detrás de un flag).

Formas concretas: un ring buffer en memoria con los últimos N eventos drenados (inspeccionable por el socket del launcher), y opcionalmente un log de texto/JSONL en la carpeta de la instancia cuando el modo debug está activo. Si la observabilidad y el conjunto vivo discrepan, la observabilidad miente por definición.

## 5. Persistencia

### 5.1 Sección por chunk: `mksa:prov` (fuente de verdad)

Escrita por el hook del serializador de chunks — se persiste en la **misma operación atómica** que los datos del chunk; no puede divergir de ellos.

```
mksa:prov: {
  v:      1                      // versión del esquema de esta sección
  epoch:  long                   // época bajo la que se procesó por última vez
  agency: [                      // esparcido; solo actor≠propietario, actor modded
    { pos: packed_int, actor: "create" }, ...
  ]
  containers: {                  // contenedores con items modded
    "<packedPos|uuid>": { "create": 12, "alexscaves": 3 }, ...
  }
  attachments: [ { ref: ..., ns: "..." }, ... ]
  gen: [ "alexscaves", ... ]     // namespaces que participaron en la generación
}
```

Nótese lo que **no** está: lista de bloques modded (la palette ya la implica), lista de entidades (el chunk de entidades ya la implica). Un chunk sin contribuciones modded no lleva sección, o lleva solo `{v, epoch}`.

### 5.2 Por jugador

Misma técnica: sección `mksa:prov` en el NBT del jugador vía hook del serializador, con el índice de items modded de su inventario/ender chest por namespace. Cubre jugadores offline sin tocar SQLite.

### 5.3 SQLite: agregado global (caché, jamás fuente de verdad)

Archivo `mksa/index.db` junto al mundo. **Invariante: borrable y reconstruible en cualquier momento** desde las secciones `mksa:prov` + escaneo perezoso. Si está corrupto o desincronizado, se descarta y reconstruye.

```sql
CREATE TABLE meta        (key TEXT PRIMARY KEY, value TEXT);          -- versión esquema, época actual
CREATE TABLE epochs      (id INTEGER PRIMARY KEY, hash TEXT, ts TEXT,
                          parent INTEGER, diff_json TEXT);            -- mods añadidos/quitados vs parent
CREATE TABLE chunk_agg   (dim TEXT, cx INT, cz INT, ns TEXT,
                          blocks INT, entities INT, container_items INT,
                          epoch INTEGER,
                          PRIMARY KEY (dim, cx, cz, ns));
CREATE TABLE player_agg  (uuid TEXT, ns TEXT, items INT,
                          PRIMARY KEY (uuid, ns));
CREATE TABLE definitions (ns TEXT, path TEXT, reg TEXT, raw_id INT,
                          PRIMARY KEY (reg, ns, path));               -- Plano A cacheado
CREATE TABLE refs        (src_reg TEXT, src_id TEXT,
                          dst_reg TEXT, dst_id TEXT, kind TEXT);      -- aristas entre definiciones
CREATE TABLE tx          (id INTEGER PRIMARY KEY, epoch_from INT, epoch_to INT,
                          ns_json TEXT, status TEXT, ts TEXT);        -- transacciones (§7)
```

Responde en O(1): "¿cuántos bloques de Create hay?" (translator, Fase 4), "¿qué chunks debo visitar para desactivar `create`?" (transacción), "¿cuántos de esos no están cargados?" (preview honesto para el jugador).

## 6. Épocas y migración perezosa

- Una época = un modset concreto: hash ordenado de (modId, versión, hash de jar) + loader + versión de MC. Contador monótono; la tabla `epochs` guarda el **diff** contra la época padre.
- Todo chunk/jugador se sella con la época vigente al serializarse tras ser procesado.
- Al cargar un chunk con sello ≠ época actual (o sin sello — mundo pre-MKSA): se calcula el diff de namespaces entre ambas épocas, se barre el chunk **solo respecto a ese diff** (palettes primero: si ninguna sección menciona los namespaces del diff, el barrido es casi gratis), se aplican las transacciones pendientes que le afecten (§7), se re-sella.
- El escaneo, por tanto, no desaparece del sistema: queda confinado a una migración perezosa, una sola vez por chunk y por cambio de época.

## 7. Transacciones y archivo de restauración

Desactivar/activar es una **transacción** sobre la época, nunca una operación suelta.

```
Tx {
  id, epoch_from → epoch_to,
  namespaces: [...],            // cascada completa ya resuelta (Planos A+B)
  status: PREVIEW | APPLYING | APPLIED_PARTIAL | APPLIED | ROLLED_BACK
}
```

`APPLIED_PARTIAL` es el estado normal tras aplicar: los chunks cargados ya migraron; los no cargados migrarán perezosamente (§6). La transacción se considera completa de cara al jugador desde ese momento.

**Archivo de restauración** — separado del ledger, creado durante el barrido de la transacción: aquí sí se guarda NBT completo, porque su propósito es reconstruir, no indexar.

```sql
CREATE TABLE restore_blocks   (tx INT, dim TEXT, pos BLOB, state TEXT, be_nbt BLOB);
CREATE TABLE restore_entities (tx INT, dim TEXT, uuid BLOB, nbt BLOB);
CREATE TABLE restore_items    (tx INT, container BLOB, slot INT, stack_nbt BLOB);
```

Reactivación: consume el archivo en orden inverso con política de conflicto explícita — posición ocupada por algo nuevo → se omite, se reporta, no se sobreescribe. Los archivos de restauración de transacciones revertidas o antiguas son podables por el usuario (es el único componente que crece).

> **Estado de implementación (interim, F3 corte 3, junio 2026).** El *productor* del archivo de restauración existe (estreno del ledger como mecanismo): el barrido dirigido captura, antes de cerrar el proceso, los bloques de la víctima (`pos` + NBT de la entrada de palette + `be_nbt` best-effort) y sus items del playerdata (`uuid` + `slot` + NBT del stack). Interim, se persiste en un **formato binario propio** (`MKSAR1`: cabecera `{ns, epoch}` + registros `'B'`/`'I'` con NBT completo vía `Tag.write(DataOutput)`), no en SQLite — el esquema de esta sección sigue siendo el contrato congelado y se adopta con el motor de restauración. El *consumidor* (reactivación con la política de conflicto de arriba) es el corte 4: requiere escritura al mundo vivo, que el agente aún no tiene. La ruta del archivo la provee el launcher por IPC (el NBT pesado no cruza el socket, §invariante 4 del protocolo).
>
> **Actualización (F3 corte 4, junio 2026): el consumidor existe para bloques.** La reactivación se implementó **sin** handle al mundo vivo: por **patcheo de la deserialización**, simétrico inverso de la captura (proyecto.md §3.16). Al leer un chunk (hook en la entrada de `parse()`), el agente re-inyecta los bloques de la víctima en el `CompoundTag` —deserializando cada entrada de palette con `TagType.load` (inverso de `Tag.write` usado en la captura), re-codificando el array empaquetado, y aplicando la **política de conflicto** (celda no-aire → omitir + reportar)— antes de que MC reconstruya el chunk. Verificado (`--fase3-restore`, ciclo A captura → B destruye → C restituye): la huella vuelve en vivo y reaparece en disco, 0 corrupción. Pendientes: **items** (reconstrucción de `ItemStack` opaco, corte 4b) y `restore_entities`. El esquema SQLite de esta sección sigue siendo el contrato congelado; el binario `MKSAR1` es el interim de productor y consumidor hasta adoptarlo.

## 8. Invariantes del contrato

1. **La identidad se lee siempre del dato real** (palette, entity type, item id); el ledger nunca la afirma por su cuenta.
2. La sección `mksa:prov` se persiste atómicamente con su chunk/jugador; **no existe estado de procedencia válido fuera de su portador**.
3. SQLite es reconstruible desde cero en cualquier momento; ningún flujo depende de su corrección, solo de su velocidad.
4. Una sección `mksa:prov` jamás referencia otros chunks; toda reparación de invariantes es local.
5. Los eventos del ledger son transitorios; lo persistido es siempre el conjunto vivo compactado.
6. Las épocas son monótonas; un sello de época nunca retrocede.
7. El Plano B (runtime) se reconstruye en cada arranque; persistirlo es un bug.
8. Toda mutación del modset pasa por una transacción con preview de cascada (dependencias del loader + aristas de referencia del Plano A).
9. El camino caliente con contenido y actor vanilla no asigna memoria ni emite eventos.
10. Semántica de desactivación: **eliminar contribuciones actuales + reparar invariantes locales** — nunca replay contrafactual del historial.
11. La agencia es metadata mínima: a lo sumo un namespace de actor por posición/referencia, último escritor gana, sin historial ni identidad de jugador. El ledger nunca evoluciona hacia un sistema de auditoría.
12. La observabilidad es solo-escritura, no persistente y apagada por defecto; ningún flujo del sistema lee de ella.
