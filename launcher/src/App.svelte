<script>
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import { open } from "@tauri-apps/plugin-dialog";
  import { onMount } from "svelte";

  let root = $state(localStorage.getItem("mksa.root") || "");
  let instances = $state([]);
  let error = $state("");
  // estado por instancia: { phase, detail } — phase: idle|launching|boot|running|closed|error
  let status = $state({});

  async function rescan() {
    error = "";
    if (!root) return;
    try {
      instances = await invoke("scan_instances", { root });
    } catch (e) {
      error = String(e);
      instances = [];
    }
  }

  async function pickRoot() {
    const dir = await open({ directory: true, title: "Carpeta de instancias" });
    if (dir) {
      root = dir;
      localStorage.setItem("mksa.root", dir);
      await rescan();
    }
  }

  async function play(inst) {
    setStatus(inst.path, { phase: "launching", detail: "preparando…" });
    try {
      const result = await invoke("launch_instance", { path: inst.path });
      if (result.already_running) {
        setStatus(inst.path, { phase: "running", detail: "ya estaba corriendo (pid " + result.pid + ")" });
      }
    } catch (e) {
      setStatus(inst.path, { phase: "error", detail: String(e) });
    }
  }

  function setStatus(path, s) {
    status = { ...status, [path]: s };
  }

  function statusText(inst) {
    const s = status[inst.path];
    if (!s || s.phase === "idle") return "";
    if (s.phase === "launching") return "⏳ " + s.detail;
    if (s.phase === "boot") return "🔄 arrancando: " + s.detail;
    if (s.phase === "running") {
      // el handshake ocurre al nacer la JVM; "corriendo" de verdad es game-ready (§3.2)
      if (s.stage && s.stage !== "game-ready")
        return "🟡 cargando Minecraft… (" + s.stage + ") · " + (s.detail || "agente conectado");
      return "🟢 " + (s.detail || "corriendo");
    }
    if (s.phase === "closed") return "⚪ cerrado";
    if (s.phase === "error") return "🔴 " + s.detail;
    return "";
  }

  onMount(() => {
    rescan();
    // Eventos del backend: progreso de lanzamiento y vida del agente (IPC).
    const un = listen("mksa://game", (e) => {
      const p = e.payload;
      if (p.kind === "spawning") setStatus(p.instance, { phase: "launching", detail: p.detail || "lanzando JVM…" });
      else if (p.kind === "boot") {
        const cur = status[p.instance];
        // si el agente ya conectó, la etapa de arranque es detalle, no sustituye al estado
        if (cur?.phase === "running") setStatus(p.instance, { ...cur, stage: p.stage });
        else setStatus(p.instance, { phase: "boot", detail: p.stage });
      }
      else if (p.kind === "connected") setStatus(p.instance, { phase: "running", detail: "agente conectado (sesión " + p.session + ")" });
      else if (p.kind === "closed") setStatus(p.instance, { phase: "closed" });
      else if (p.kind === "error") setStatus(p.instance, { phase: "error", detail: p.detail });
    });
    return () => un.then((f) => f());
  });
</script>

<header>
  <h1>MKSA</h1>
  <div class="root-row">
    <span class="root-path">{root || "elige la carpeta de instancias"}</span>
    <button class="ghost" onclick={pickRoot}>Carpeta…</button>
    <button class="ghost" onclick={rescan} disabled={!root}>Refrescar</button>
  </div>
</header>

{#if error}
  <p class="error">{error}</p>
{/if}

<main>
  {#each instances as inst (inst.path)}
    <div class="card">
      <div class="info">
        <div class="name">{inst.name}</div>
        <div class="meta">
          {#if inst.mc_version}<span class="badge">MC {inst.mc_version}</span>{/if}
          {#if inst.loader_name}<span class="badge loader">{inst.loader_name} {inst.loader_version}</span>{/if}
          <span class="dim">{inst.mod_count} mods</span>
        </div>
        <div class="status">{statusText(inst)}</div>
      </div>
      <button
        class="play"
        onclick={() => play(inst)}
        disabled={status[inst.path]?.phase === "launching" || status[inst.path]?.phase === "boot"}
      >Jugar</button>
    </div>
  {:else}
    {#if root && !error}
      <p class="dim">No hay instancias en esta carpeta.</p>
    {/if}
  {/each}
</main>

<style>
  header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
  }
  h1 {
    margin: 0;
    font-size: 22px;
    letter-spacing: 2px;
    color: var(--accent);
  }
  .root-row {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .root-path {
    color: var(--fg-dim);
    font-size: 12px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 360px;
  }
  .ghost {
    background: transparent;
    border: 1px solid var(--border);
    color: var(--fg);
    padding: 6px 12px;
    font-size: 13px;
  }
  .ghost:hover { border-color: var(--accent-dim); }
  .card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px 20px;
    margin-bottom: 12px;
  }
  .card:hover { background: var(--bg-card-hover); }
  .name { font-size: 16px; font-weight: 600; }
  .meta { display: flex; gap: 8px; align-items: center; margin-top: 6px; }
  .badge {
    background: #30304e;
    border-radius: 6px;
    padding: 2px 8px;
    font-size: 12px;
  }
  .badge.loader { color: var(--accent); }
  .dim { color: var(--fg-dim); font-size: 12px; }
  .status { margin-top: 8px; font-size: 12px; color: var(--fg-dim); min-height: 14px; }
  .play {
    background: var(--accent);
    color: #10241c;
    font-weight: 700;
    padding: 10px 26px;
    font-size: 14px;
  }
  .play:hover { background: #62dbb4; }
  .play:disabled { background: var(--accent-dim); cursor: default; }
  .error { color: var(--danger); }
</style>
