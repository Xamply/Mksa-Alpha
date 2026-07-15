const fs = require("fs");
const txt = fs.readFileSync("C:/Users/Aly/AppData/Local/Temp/tier3-e2e-regen-live.txt", "utf8");
const lines = txt.split("\n");
let parsedCount = 0, demixCount = 0;

function extractJson(line, startIdx) {
  let depth = 0, inStr = false, esc = false;
  for (let i = startIdx; i < line.length; i++) {
    const ch = line[i];
    if (inStr) {
      if (esc) { esc = false; }
      else if (ch === '\\') { esc = true; }
      else if (ch === '"') { inStr = false; }
      continue;
    }
    if (ch === '"') { inStr = true; continue; }
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return line.slice(startIdx, i + 1);
    }
  }
  return null;
}

for (const line of lines) {
  const idx = line.indexOf('{"id":');
  if (idx === -1) continue;
  const jsonStr = extractJson(line, idx);
  if (!jsonStr) continue;
  let obj;
  try { obj = JSON.parse(jsonStr); } catch(e) { continue; }
  parsedCount++;
  const ok = obj.ok;
  if (!ok || !ok.demixPlan) continue;
  demixCount++;
  const tps = (ok.demixPlan.targetPlans)||[];
  for (const tp of tps) {
    if (tp.configPluginPresent) {
      const owners = new Set();
      for (const c of (tp.removeMixins||[]).concat(tp.replayMixins||[])) {
        if (c.configPlugin) owners.add(c.ns+":"+c.configPlugin);
      }
      console.log(tp.target, "coOwners=", JSON.stringify(tp.coOwners), "pluginOwners=", JSON.stringify([...owners]), "configPluginSimple=", tp.configPluginSimple);
    }
  }
}
console.error("parsedCount=", parsedCount, "demixCount=", demixCount);
