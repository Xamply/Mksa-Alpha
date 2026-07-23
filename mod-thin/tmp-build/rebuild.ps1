$ErrorActionPreference = "Stop"
$root = "E:\Users\Aly\Desktop\MKSA\mod-thin"
Set-Location $root

Remove-Item -Recurse -Force out, dist, out-tools -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path out, dist, out-tools | Out-Null

$spriteDst = "$root\src\main\resources\assets\mksa\textures\gui\sprites\dogito.png"
$srcJpeg = "$root\..\dogito.jpeg"

if (-not (Test-Path (Split-Path $spriteDst))) {
    New-Item -ItemType Directory -Force -Path (Split-Path $spriteDst) | Out-Null
}

javac -encoding UTF-8 -d out-tools tools\ConvertImage.java
java -cp out-tools ConvertImage "$srcJpeg" "$spriteDst"

$javaFiles = Get-ChildItem -Recurse -Path "$root\src\main\java" -Filter *.java | ForEach-Object { $_.FullName }
$javaFiles | Set-Content -Encoding ASCII "$root\modthin.sources"

javac --release 21 -encoding UTF-8 -cp "lib\*" -d out "@$root\modthin.sources"
if ($LASTEXITCODE -ne 0) { throw "javac mod-thin fallo" }

jar cf dist\mksa-thin.jar -C out . -C src\main\resources .
Write-Host "OK -> $root\dist\mksa-thin.jar"
