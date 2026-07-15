# MKSA
Documento de lectura rapida y memoria historica.
No reemplaza a [proyecto.md](proyecto.md) ni al contrato tecnico de [modelo-de-datos.md](modelo-de-datos.md). Los complementa: uno explica la arquitectura, este cuenta como llegamos aqui.
Escrito como un mini libro tecnico: causal, cronologico y con las decisiones que hicieron falta para llegar hasta hoy.

Ultima revision: julio 2026

---

## Mapa
- [0. Lectura rapida](#0-lectura-rapida)
- [1. De Donde Venimos](#1-de-donde-venimos)
- [2. Tres Grandes Etapas](#2-tres-grandes-etapas)
- [3. Cronologia De Cortes](#3-cronologia-de-cortes)
- [4. Glosario Vivo](#4-glosario-vivo)
- [5. Estado Hoy](#5-estado-hoy)
- [6. Futuro](#6-futuro)

---

## 0. Lectura rapida
MKSA intenta hacer algo que Minecraft no hace por defecto: permitir que un mod desaparezca y reaparezca sin reiniciar el juego, sin mentirle al mundo y sin borrar lo que no le pertenece.

Antes de convertirse en una arquitectura, esto fue un deseo muy simple: probar mods sin tener que reiniciar el mundo cada vez que algo no gustaba. Era un problema de tiempo, de friccion y de iteracion, muy parecido a lo que Live Server cambio para quien edita y quiere ver resultados sin romper el ritmo de trabajo.

Las 3 grandes etapas del proyecto no son fases administrativas. Son tres preguntas historicas que hubo que contestar en orden:
- primero, demostrar que el cambio era posible con dos JVMs;
- despues, demostrar que el mismo cambio podia ocurrir en caliente, dentro del proceso;
- solo entonces, pensar en la vision futura de un sistema que ademas mida, explique y administre el coste residual de cada mod.

La secuencia importa porque cada paso resolvio una duda distinta:
- si el problema era reversible;
- si la reversibilidad podia ocurrir sin reiniciar;
- si la idea podia crecer hasta convertirse en plataforma.

---

## 1. De Donde Venimos
Al principio la hipotesis era simple: si un mod esta en `mods/`, el launcher lo pone o lo quita y el juego ya se encargara del resto.

Pero el motivo real era mas pequeno y mas humano: hacer que probar mods fuera comodo. Quitar el costo de cerrar, abrir, esperar y volver a cargar para decidir si algo valia la pena. En ese sentido, el proyecto nacio como una version de hot reload para Minecraft modded, no como una plataforma abstracta.

Ese modelo se rompio rapido.

Lo que se descubrio, una y otra vez, fue esto:
- los chunks no consultan el registro cuando cargan su contenido, ya guardan referencias directas;
- los inventarios no son solo datos, sino estado vivo con objetos reales;
- las suscripciones runtime siguen existiendo aunque el jar ya no se vea;
- los mixins dejan huella en clases vanilla;
- y el mod puede estar ausente del loader, pero seguir vivo por dentro en una copia nested o por una referencia ya capturada.

Desde ahi, la pregunta cambio.
Ya no era "como ocultamos un mod".
Era "como desmontamos sus contribuciones sin inventarnos el mundo".

Eso empujo el proyecto hacia el WCG, el ledger y la idea de que desactivar no es borrar un jar, sino resolver una transaccion de estado.
Fabric fue el frente de avance donde se probo gran parte de esto primero, pero nunca fue el limite conceptual del proyecto: la meta siempre fue que el mismo criterio llegara a Forge y NeoForge.
Ese origen importa porque explica por que no tiene sentido pensar el proyecto solo como "switch de mods": lo que se queria resolver era el costo de experimentar. Primero como comodidad, despues como control, y mas tarde como medicion.

---

## 2. Tres Grandes Etapas
Estas son las tres grandes etapas que has definido para el proyecto. No son fases administrativas: son tres preguntas distintas que hubo que contestar en orden.

### Etapa I. Hacerlo posible con 2 JVMs
Objetivo:
- demostrar que un modset puede cambiarse sin romper el mundo usando dos procesos separados;
- conseguir que el launcher abra la instancia correcta y mantenga el hilo de control;
- identificar loader, version y modset;
- encontrar una forma de salir y volver a entrar sin corromper el save.

Resultado:
- el launcher quedo capaz de resolver Fabric y tambien Forge/NeoForge;
- el relaunch entre dos modsets sobre el mismo mundo quedo demostrado;
- el sistema aprendio que la reversibilidad dependia de capturar el estado antes de cerrarlo;
- el cierre del harness se fue endureciendo para evitar corrupcion al apagar.

Progreso:

```text
Lanzamiento y control      [██████████] 100%
Relaunch entre 2 JVMs      [██████████] 100%
Deteccion de loader        [██████████] 100%
Cierre robusto             [███████░░░]  70%
```

### Etapa II. Hacerlo en caliente, sin reiniciar
Objetivo:
- hacer que la misma idea funcione sin abrir una segunda JVM;
- construir el WCG;
- guardar procedencia;
- distinguir contenido, agencia y epoca;
- saber que pertenece a quien antes de mutar nada.

Resultado:
- `ledger` dejo de ser idea y paso a ser mecanismo;
- se sellan epoch y procedencia;
- el proyecto aprendio a seguir contribuciones vivas en bloques, items, recetas, tags, loot y runtime refs;
- SQLite quedo como caché reconstruible, no como verdad primaria.

Progreso:

```text
WCG y procedencia         [██████████] 100%
Epoch y agregado          [██████████] 100%
SQLite como caché         [██████████] 100%
Runtime observable        [███████░░░]  70%
```

### Etapa III. La vision futura
Objetivo:
- que desactivar un mod no sea solo ocultarlo, sino medir y reducir su coste residual;
- que el jugador vea una decision clara, no una caja negra;
- que el sistema sepa cuando puede ser `auto`, cuando debe preguntar y cuando no debe inventar nada;
- que Fabric no sea el techo, sino el punto donde se demostro el camino;
- que Forge y NeoForge lleguen al mismo nivel de cobertura real.

Estado:
- aun no es la etapa operativa principal;
- existe como direccion del proyecto, pero depende de tener bien cerradas las etapas 1 y 2;
- el trabajo actual ya prepara el terreno para llegar ahi sin inventar soluciones de emergencia.
- esta etapa no puede arrancar sola: necesita primero la prueba de reversibilidad de la etapa 1 y el mecanismo hot de la etapa 2.

Progreso:

```text
Vision definida            [██████████] 100%
Base tecnica previa        [██████████] 100%
Cobertura real futura      [███░░░░░░░]  30%
Paridad multi-loader       [███░░░░░░░]  30%
```

### Por que el orden importa
La etapa 3 no podia empezar primero porque dependia de dos cosas que no existian al inicio:

1. una forma de demostrar que el mundo podia sobrevivir al cambio, aunque fuera con dos JVMs;
2. una forma de hacer el mismo cambio dentro del proceso, sin reiniciar y sin mentirle al estado vivo.

Sin la primera etapa no habia prueba de reversibilidad.
Sin la segunda no habia mecanismo real de ejecucion.
Sin ambas, la vision futura era solo una idea elegante.

---

## 3. Cronologia De Cortes
Esta es la version compacta de lo que ya se registro en `docs/log.txt`.

### Fase 2
- **inc.2**: deteccion de loader y enumeracion de mods en Fabric y NeoForge.
- **inc.3**: clasificacion por intrusividad con tiers.
- **inc.4**: refinamiento de T1/T2 por capacidad estatica.
- **inc.5 P1**: primer ledger con procedencia dentro del chunk.
- **inc.5 P2**: epoch real y agregado de presencia.
- **inc.5 P3**: desambiguacion de getter NBT por comportamiento, evitando corrupcion.

### Fase 3
- **corte 1**: relaunch entre dos modsets sobre el mismo mundo.
- **corte 2**: se confirmo que la huella puede perderse y que el ledger ya no es solo optimizacion.
- **corte 3**: sweep dirigido por ledger y archivo de restauracion.
- **corte 4**: re-inyeccion viva de bloques.
- **corte 4b**: re-inyeccion viva de items de jugador.

### In-process
- **corte 1**: el agente pudo mutar el mundo vivo sin reiniciar.
- **corte 2**: se introdujo el observador del ARETURN y el agregado byChunk.
- **corte 3**: veto vivo de colocaciones nuevas.
- **corte 4**: reload dirigido de datapacks.
- **corte 5**: DisablePlan y decision explicita.
- **corte 5b**: restauracion viva de bloques.

### Runtime / UI
- **Tier 2 corte 1**: captura de Fabric Event.register.
- **Tier 2 corte 2**: disable/restore de refs de evento.
- **Tier 2 corte 3-A**: Fabric networking global.
- **Tier 2 corte 3-B**: Fabric networking por conexion.
- **Tier 2 corte 5**: flujo completo por la UI.
- **F4 explorer semantico**: perfil por mod conectado al WCG, visible en `ModsScreen`, con rol/superficies/riesgos/evidencia e impacto dinamico por deps/cascada.

### Correcciones relevantes recientes
- bug de inventario de `artifacts` resuelto;
- cierre del harness endurecido;
- el proyecto ya no depende de `taskkill /F` como camino normal.
- la narrativa del proyecto ya queda anclada a la secuencia correcta: 2 JVMs, hot reload, vision futura.

---

## 4. Glosario Vivo

**Agent**
: El jar cargado con `-javaagent`. Vive dentro del proceso del juego y hace descubrimiento, captura y control del lado del servidor integrado.

**Bridge**
: La capa bootstrap que recibe los hooks inyectados y delega al ledger sin acoplarse a clases de Minecraft.

**Ledger**
: El cerebro de procedencia y estado. No adivina el mundo: registra contribuciones, epoch, runtime refs, snapshots y restore data.

**WCG**
: `World Contribution Graph`. El grafo de contribuciones del mundo: que aporto cada mod, donde, y como se relaciona con el resto.

**Smoke**
: Verificacion ejecutable corta. No es un test de biblioteca; es un recorrido por el juego real o por el harness que valida una hipotesis concreta.

**Epoch**
: Huella del modset actual. Sirve para saber que mundo fue procesado con que conjunto de mods.

**Tier**
: Clasificacion de un mod por intrusividad y mecanismo de desactivacion posible.

**Restore file**
: Archivo de restauracion que guarda lo capturado para poder devolverlo luego.

**DisablePlan**
: Respuesta previa a una desactivacion. Dice si se puede hacer, que se tocara, que queda residual y si hace falta preguntar.

**Corte**
: Una unidad de avance cerrada dentro de una fase. Cada corte marca una hipotesis, una implementacion y una verificacion.

**Quiesce**
: Espera a que el mundo deje de escribir antes de cerrar, para no forzar un kill destructivo.

**WM_CLOSE**
: Cierre amable del proceso. En este proyecto se trata como la ruta normal; `/F` es ultimo recurso.

**Taskkill**
: Herramienta de cierre de Windows. Aqui representa la diferencia entre salir limpio y cortar en seco.

**Nested / jar-in-jar**
: Mod embebido dentro de otro jar. Puede seguir vivo aunque el jar externo ya no este en `mods/`.

**Hard-deps**
: Dependencias declaradas por el loader. Si un mod depende de otro, no basta con esconder el proveedor.

**Cascada**
: Efecto indirecto de desactivar un mod sobre recetas, tags, loot o runtime refs ajenas.

**RestoreMiss / RestoreConflict**
: Fallo al restaurar por ausencia del destino o por conflicto con trabajo nuevo del jugador.

---

## 5. Estado Hoy
Hoy MKSA ya no esta en la etapa de "se puede enganchar al juego".
Tampoco esta todavia en la vision futura completa. Esta en la etapa 2: hacerlo en caliente, sin reiniciar.

Ya sabe:
- arrancar instancias reales;
- detectar loader;
- clasificar mods;
- construir procedencia;
- hacer sweep y restore;
- y exponer un panel de desactivacion con decision explicita.

Lo que ya esta solido:
- bloques Tier 1;
- datapacks filtrables;
- runtime refs Fabric;
- UI funcional de mods;
- explorador semantico por mod basado en evidencia local del WCG + impacto dinamico (`deps`/`wcg.refs`);
- correccion del bug de inventario;
- cierre del harness bastante menos propenso a `/F`.

Lo que sigue siendo parcial:
- paridad real de Forge y NeoForge en las superficies hot;
- runtime completo fuera de Fabric;
- conflictos mas sofisticados en entidades, contenedores y saved data;
- automatizacion de regresiones para evitar recaidas silenciosas.

---

## 6. Futuro
El proyecto sigue apuntando a lo mismo, pero con menos ilusion y mas estructura. La etapa 3 no arranca porque si: se construye sobre lo que ya demostro la etapa 1 y la etapa 2.

- que desactivar un mod no sea "quitar un jar", sino resolver estado;
- que el jugador vea una decision clara, no una caja negra;
- que el sistema sepa cuando puede ser `auto`, cuando debe preguntar y cuando no debe inventar nada;
- que Fabric no sea el techo, solo el punto donde se avanzo primero;
- que Forge y NeoForge lleguen al mismo nivel de cobertura real.
- que el juego no solo "parezca" no tener el mod, sino que tambien deje de pagar su costo: menos callbacks, menos hooks, menos memoria, menos ticks, menos render y menos ruido por owner.
- que el panel deje de ser solo una lista de mods y se convierta en una lectura del modpack: que hace cada mod, con que se relaciona y cuanto cuesta.
- que existan vistas o receptores distintos por mundo o servidor, siempre bajo autoridad del servidor y con colas de transicion si varios tienen permiso.

En corto:
- el presente ya funciona;
- el futuro es volverlo equivalente en todos los loaders relevantes;
- y el siguiente trabajo serio es cerrar las superficies que todavia solo estan resueltas en Fabric.

Entre la etapa 2 y la 3 aparece un puente importante: usar MKSA para ocultar o aislar mods por mundo o por contexto, sin fingir que el problema ya desaparecio. Eso puede ser util para testeo, para servidores con mundos distintos y para experiencia guiada, pero solo mientras la politica siga siendo honesta sobre lo que es auto, ask o blocked.
