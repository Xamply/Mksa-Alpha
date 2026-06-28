#!/usr/bin/env bash
# Compila fable-agent.jar (target Java 8).
#
# Tres artefactos; dos van ANIDADOS como recurso dentro del agent jar:
#   · mksa-bridge.jar — solo dev.mksa.bridge.*. El agente lo añade al bootstrap
#     classloader en premain (visibilidad del Bridge para el bytecode inyectado).
#   · mksa-ledger.jar — dev.mksa.agent.ledger.* + ASM. Se carga en un URLClassLoader
#     AISLADO (child-first) en runtime. ASM NO puede ir en el classpath del sistema:
#     el JVM mete el javaagent jar ahí y los loaders abortan al detectar ASM duplicado
#     (Fabric LoaderUtil.verifyClasspath). El transformer solo maneja byte[] y llama
#     al Bridge por string → no necesita ver clases de MC ni estar en el CL del sistema.
#   · fable-agent.jar — el agente (dev.mksa.agent.* SIN ledger), SIN ASM, SIN bridge.
#     Carga ledger y bridge por reflexión desde los jars anidados.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out out-bridge out-ledger dist
mkdir -p out out-bridge out-ledger dist

# 1) Bridge → jar independiente (bootstrap).
javac --release 8 -encoding UTF-8 -d out-bridge $(find src -path '*dev/mksa/bridge/*.java')
jar cf dist/mksa-bridge.jar -C out-bridge dev

# 2) Ledger → jar aislado con ASM dentro. Compila contra ASM (lib/*). No referencia
#    clases del agente ni del bridge (el Bridge se invoca por string en el bytecode).
javac --release 8 -encoding UTF-8 -cp "lib/*" -d out-ledger \
    $(find src -path '*dev/mksa/agent/ledger/*.java')
for j in lib/*.jar; do (cd out-ledger && jar xf "../$j"); done
rm -rf out-ledger/META-INF   # descartar manifests/firmas de ASM
jar cf dist/mksa-ledger.jar -C out-ledger .

# 3) Agente → SIN ledger, SIN bridge, SIN ASM (los carga por reflexión).
#    Compila contra out-bridge para que Ledger implemente LedgerSink; las clases
#    del bridge NO se empaquetan (se leen del cp, no se copian a out).
javac --release 8 -encoding UTF-8 -cp "out-bridge" -d out \
    $(find src -name '*.java' -not -path '*dev/mksa/bridge/*' -not -path '*dev/mksa/agent/ledger/*')

# 3b) TwelveMonkeys WebP ImageIO plugin → empaquetado en el agent jar.
#     Modrinth sirve iconos en WebP; NativeImage de Minecraft solo lee PNG. La
#     decodificación corre en el agente: WebP → ImageIO → PNG → cache.
#     SPI auto-registrado por META-INF/services. Las jars de TwelveMonkeys
#     pesan ~580KB combinadas y no colisionan con ASM/MC/Fabric.
for j in lib/imageio-webp-*.jar lib/imageio-core-*.jar lib/imageio-metadata-*.jar \
         lib/common-lang-*.jar lib/common-io-*.jar lib/common-image-*.jar; do
    (cd out && jar xf "../$j")
done
# Descartar MANIFEST y firmas; mantener META-INF/services (auto-merge si no hay conflictos).
rm -f out/META-INF/MANIFEST.MF out/META-INF/*.SF out/META-INF/*.RSA out/META-INF/*.DSA
rm -rf out/META-INF/maven

# 4) Los dos jars anidados como recurso en la raíz del agent jar.
cp dist/mksa-bridge.jar out/mksa-bridge.jar
cp dist/mksa-ledger.jar out/mksa-ledger.jar

cat > out/MANIFEST.MF <<'EOF'
Premain-Class: dev.mksa.agent.Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF

jar cfm dist/fable-agent.jar out/MANIFEST.MF -C out .

# Checks: ni ledger ni ASM como clases sueltas del agent jar; ambos jars anidados presentes.
echo "--- checks ---"
if jar tf dist/fable-agent.jar | grep -q '^org/objectweb/asm/'; then
    echo "FALLO: ASM presente como clase del agent jar (rompería LoaderUtil)"; exit 1
fi
if jar tf dist/fable-agent.jar | grep -q '^dev/mksa/agent/ledger/'; then
    echo "FALLO: clases del ledger sueltas en el agent jar"; exit 1
fi
if jar tf dist/fable-agent.jar | grep -q '^dev/mksa/bridge/'; then
    echo "FALLO: clases del bridge sueltas en el agent jar"; exit 1
fi
jar tf dist/fable-agent.jar | grep -q '^mksa-ledger.jar$' || { echo "FALLO: falta mksa-ledger.jar anidado"; exit 1; }
jar tf dist/fable-agent.jar | grep -q '^mksa-bridge.jar$' || { echo "FALLO: falta mksa-bridge.jar anidado"; exit 1; }
jar tf dist/mksa-ledger.jar | grep -q '^org/objectweb/asm/ClassReader.class$' || { echo "FALLO: ASM no está en mksa-ledger.jar"; exit 1; }
echo "OK checks"

echo "OK -> $(pwd)/dist/fable-agent.jar"
echo "     bridge -> $(pwd)/dist/mksa-bridge.jar"
echo "     ledger -> $(pwd)/dist/mksa-ledger.jar"
