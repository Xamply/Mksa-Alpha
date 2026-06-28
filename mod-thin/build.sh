#!/usr/bin/env bash
# Compila mksa-thin.jar (mod Fabric, target Java 21 — MC 1.21.11 runtime).
#
# Sin Gradle/Loom: javac directo contra los jars de lib/ (Fabric loader,
# fabric-screen-api, client-intermediary y mksa-bridge).
#
# Recursos:
#  · fabric.mod.json en src/main/resources/
#  · Sprite: convierte ../dogito.jpeg → assets/mksa/textures/gui/sprites/dogito.png
#    via javax.imageio (tools/ConvertImage). Idempotente (skip si esta al dia).
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out dist
mkdir -p out dist

SPRITE_DST="src/main/resources/assets/mksa/textures/gui/sprites/dogito.png"
SRC_JPEG="../dogito.jpeg"

# 1) Convertir el JPEG al PNG en el path del sprite atlas.
mkdir -p "$(dirname "$SPRITE_DST")"
mkdir -p out-tools
javac -encoding UTF-8 -d out-tools tools/ConvertImage.java
java -cp out-tools ConvertImage "$SRC_JPEG" "$SPRITE_DST"

# 2) Compilar fuentes Java contra los jars de lib/.
#    --release 21: MC 1.21.11 corre en Java 21 y nuestras llamadas (records,
#    pattern-matching de instanceof) son 1.8+ pero el bytecode target debe
#    matchear el del runtime para usar APIs nuevas (TagValueInput aparece en
#    1.20.5+, etc. — aqui no las tocamos pero el class file version debe ser >= 21).
#    Usamos wildcard "lib/*" (javac la expande, OS-agnostico — el separador
#    de classpath difiere entre Linux ':' y Windows ';').
javac --release 21 -encoding UTF-8 -cp "lib/*" -d out \
    $(find src/main/java -name '*.java')

# 3) Empaquetar.
#    Las clases compiladas en out/, los recursos (fabric.mod.json + assets) en src/main/resources/.
jar cf dist/mksa-thin.jar -C out . -C src/main/resources .

# 4) Checks.
echo "--- checks ---"
jar tf dist/mksa-thin.jar | grep -q '^fabric.mod.json$' || { echo "FALLO: falta fabric.mod.json"; exit 1; }
jar tf dist/mksa-thin.jar | grep -q '^assets/mksa/textures/gui/sprites/dogito.png$' || { echo "FALLO: falta sprite dogito.png"; exit 1; }
jar tf dist/mksa-thin.jar | grep -q '^dev/mksa/modthin/ModThinClient.class$' || { echo "FALLO: falta ModThinClient"; exit 1; }
jar tf dist/mksa-thin.jar | grep -q '^dev/mksa/modthin/ModsScreen.class$' || { echo "FALLO: falta ModsScreen"; exit 1; }
# Que NO empaqueta clases de MC/Fabric (esos son solo compile-time).
if jar tf dist/mksa-thin.jar | grep -qE '^net/(minecraft|fabricmc)/'; then
    echo "FALLO: clases de MC/Fabric empaquetadas (deberian ser solo compile-time)"; exit 1
fi
if jar tf dist/mksa-thin.jar | grep -q '^dev/mksa/bridge/'; then
    echo "FALLO: clases del bridge empaquetadas (deberian venir del agente en runtime)"; exit 1
fi
echo "OK checks"
echo "OK -> $(pwd)/dist/mksa-thin.jar"
