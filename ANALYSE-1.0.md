# SmoothBlocks: Analyse vor Version 1.0

Stand: 7. Oktober 2026. Untersucht: lokaler Quellstand 1.6.3, Minecraft-JAR 26.1.2 und vorhandene Prüfprogramme. Keine Änderungen am Mod-Code. Keine Ingame-Reproduktion oder GPU-Messung durchgeführt.

## Ergebnis und empfohlener Umfang

Der Terrain-Ansatz ist sinnvoll: Klassifikation beim Atlasaufbau, kompakte RG8UI-Metadaten und Rekonstruktion im Fragmentshader. Die vorhandenen Probleme erfordern keinen grundlegenden Austausch des xBRZ-Kerns.

Empfehlung für 1.0: Minecraft **26.1.2 + Fabric + Sodium**, Iris optional und explizit kompatibel, sowohl mit deaktiviertem als auch aktiviertem Shaderpack. Iris ist bereits keine Pflichtabhängigkeit in fabric.mod.json; die Iris-Mixins sind @Pseudo. Das ist eine gute Ausgangslage, aber noch kein bestandener Laufzeittest ohne Iris.

Nur Fabric ohne Sodium ist technisch möglich, aber nicht bereits abgedeckt: Terrain-Patching und Metadatenbindung hängen an Sodium. Für Vanilla-Terrain sind zusätzliche Shader- und Draw-Hooks nötig. Das würde ich aus 1.0 heraushalten. Iris allein als Pflicht zu deklarieren behebt den Entity-Pfad bei abgeschaltetem Shaderpack nicht.

## Priorisierte Befunde

### P1: Standard-Entity-Shader werden beim Vorabkompilieren nicht gepatcht

**Belegt im Code und im Bytecode der lokalen Minecraft-26.1.2-JAR.** ShaderManagerMixin injiziert in ShaderManager.getShader. ShaderManager.apply erzeugt hingegen einen neuen CompilationCache und übergibt dessen getShaderSource direkt an GpuDevice.precompilePipeline. Der untersuchte MethodHandle bestätigt das ausdrücklich. Die Standard-Pipelines können somit ungepatcht im GPU-Cache landen, ohne den äußeren getShader-Hook zu durchlaufen.

Der Patcher selbst funktioniert für die tatsächliche core/entity.fsh: der neue ShaderCoverageAudit meldet `entity: selected=true patched=true`. Das Problem liegt damit nachweisbar in der Abdeckung des Ladewegs, nicht schon im Erkennen dieses Shadertexts. Ob daneben weitere Laufzeitprobleme bestehen, muss Ingame geprüft werden.

**Fixrichtung:** den gemeinsamen tatsächlichen Shader-Quellzugriff einschließlich CompilationCache abdecken, doppelte Injektion vermeiden und anschließend die Uniform-Aktivierung prüfen. Initialstart, Ressourcenreload und Shaderpack-Umschalten testen. require=0 am GlCommandEncoder-Hook kann Ausfälle verbergen; fehlende Hook-Abdeckung sollte sichtbar diagnostiziert werden.

Fundstellen: src/main/java/de/oai/smoothblocks/mixin/ShaderManagerMixin.java:18; SmoothBlocksShaderPatch.java:143; mixin/GlCommandEncoderMixin.java:13.

### P1: Eigener Item-Shader fehlt in der Auswahl

**Belegt mit Originalressourcen.** Minecraft 26.1.2 enthält core/item.fsh. isEntityShaderName akzeptiert entity, armor, eyes, hand usw., aber nicht item. Der Audit meldet `item: selected=false patched=false`. Auch die Pipeline-Kategorisierung kennt keine allgemeine Item-Kategorie.

**Fixrichtung:** Items explizit behandeln. Nicht einfach global `contains("item")` ergänzen: derselbe Shader wird auch für UI-Items verwendet. Der Modus muss pro tatsächlichem World-/GUI-Draw gesetzt werden, damit die zugesagte GUI-Ausnahme bestehen bleibt. Das heutige Entity-Uniform wird allein aus dem globalen Modus gesetzt.

Fundstellen: SmoothBlocksShaderPatch.java:150; SmoothBlocksClient.java:325; SmoothBlocksEntityXbrzGpuBridge.java:20.

### P1: Entity-xBRZ ist nicht atlas-/sprite-sicher

**Belegter algorithmischer Unterschied; Anteil am beobachteten Itemfehler noch Ingame zu isolieren.** smoothblocks_EFetch begrenzt seine Zugriffe ausschließlich auf die gesamte Textur. EStates liest bis zwei Texel über das Zentrum hinaus. Bei atlasbasierten Items können Klassifikation und Rekonstruktion fremde Sprite-/Padding-Pixel einbeziehen. Der Terrain-Pfad besitzt dagegen explizite Sprite-Grenzen. Bei Entity-Skins werden auch Modell-UV-Inseln nicht getrennt.

**Fixrichtung:** Sprite-Zugehörigkeit und Grenzen in den Item-Pfad bringen; bei passendem Atlas die vorberechneten Metadaten nutzen oder eine gleichwertige sprite-lokale Lösung einsetzen. Nicht nur die zentrale UV klemmen: auch alle Nachbarschaftszugriffe müssen innerhalb der richtigen Region bleiben. Animierte Sprites separat berücksichtigen.

Fundstellen: SmoothBlocksShaderPatch.java:796, :806, :942.

### P1: Geglättete Alpha-Kontur und Item-Seitengeometrie können auseinanderlaufen

**Plausible zweite Ursache, noch keine bestätigte visuelle Diagnose.** Der originale ItemModelGenerator erzeugt Seitenflächen anhand der Sprite-Transparenzübergänge. Der Mod rekonstruiert RGBA im Fragmentshader, ändert aber diese Geometrie nicht. Dünne Seitenflächen und Frontkontur können daher auseinanderfallen. Auf stark gestreckten Seiten-UVs ist außerdem die Ableitung der xBRZ-Skalierung besonders empfindlich. Der originale item.fsh verwirft Fragmente über ALPHA_CUTOUT.

**Prüfung/Fixrichtung:** Front und Seiten getrennt visualisieren; xBRZ-Farbe mit ursprünglicher Alpha-Abdeckung als Diagnose vergleichen; Sprite-Clamping unabhängig davon prüfen. Für 1.0 kann ein gezielter Seitenflächen-Sonderpfad sinnvoll sein. Vollständig glatte räumliche Silhouetten können eine Anpassung der Item-Geometrie erfordern. Ein Fragmentshader kann keine außerhalb der gerasterten Geometrie liegenden Flächen hinzufügen.

SmoothBlocksAlphaEdgeRepair betrifft einzelne Entity-/Armor-Texturen, keine Item-Atlanten; es behebt weder Sprite-Grenzen noch die extrudierte Geometrie. Premultipliziertes RGBA ist bereits implementiert, daher wäre ein erneuter allgemeiner „Alpha-Fix“ allein keine belastbare Diagnose.

### P2: Sampler-Ersetzung betrifft sämtliche Bindings einer Entity-Pipeline

**Belegt.** RenderPassMixin erhält beim Beobachten den Binding-Namen, gibt ihn aber nicht an chooseWorldEntitySampler weiter. Der Ersatz wird allein anhand der Pipeline gewählt. Dadurch können neben der Farbetextur auch Hilfstexturen betroffen sein. Die Ersatzsampler ändern außerdem mehr als den Filter: Wrap-/LOD-/Anisotropieeigenschaften des Originals werden nicht allgemein erhalten.

**Fixrichtung:** Ersatz auf die identifizierte diffuse Textur begrenzen, übrige Bindings erhalten. GUI-/Welt-Unterscheidung für Items dabei zusammenhängend lösen.

### P2: Iris-Patcher deckt Shaderpacks nur heuristisch ab

Terrain patcht direkte main()-Samples, Entity patcht texture/texture2D auch in Helfern, aber keine textureGrad-/textureLod-Aufrufe. Damit können Hilfsabfragen verändert und tatsächliche Albedoabfragen verpasst werden. Die Terrain-Regel „direkt in main“ garantiert ebenfalls nicht, dass jede Abfrage finale Albedo ist. Ableitungen in materialabhängigen Kontrollflusszweigen sind zusätzlich zu prüfen.

Für 1.0 eine konkrete getestete Shaderpack-Matrix veröffentlichen und übersprungene/fehlende Pfade sichtbar machen. Langfristig strukturierte Transformation und eindeutigere semantische Auswahl bevorzugen. Keine universelle Shaderpack-Kompatibilität aus erfolgreichen Text-Ersetzungen ableiten.

## Performance

Keine gemessene FPS-Regression festgestellt, weil kein Ingame-Profiling durchgeführt wurde. Die folgenden Punkte sind aus dem Code abgeleitete Kandidaten:

1. **Entity-Klassifikation pro Fragment:** 21 Nachbarschaftslesevorgänge in EStates plus fünf im Rekonstruktionspfad, zusätzlich zahlreiche Farbdistanzen. Das sind Quellcode-Aufrufe, keine garantierten 26 physischen GPU-Zugriffe; Compiler und Texture-Cache können redundante Arbeit einsparen. Viele große Entities oder bildfüllende Hand-Items sind der relevante Belastungsfall. Statische Texturen möglichst vorab klassifizieren; dynamische Skins/Uploads sauber invalidieren.
2. **Nur Mip-Level 0 im xBRZ-Pfad:** Terrain und Entities rekonstruieren auch entfernte Texturen aus Level 0. Das kann Aliasing/Flimmern und unnötige Arbeit verursachen. Einen gesonderten Minifikationspfad entwerfen und dessen Stil visuell abstimmen; nicht unbemerkt die dokumentierte Static-xBRZ-Invariante brechen.
3. **GL-Abfragen im Renderpfad:** Entity-Setup fragt bei jedem trySetup das aktive Programm ab und schreibt das Uniform erneut. Terrain liest Samplerbelegungen und GL-Zustand pro Pass. Hardwarelimits cachen, Diagnostik bedarfsgesteuert machen und Uniformschreibvorgänge reduzieren, wo Lebenszyklus und Iris-Zustandsänderungen das sicher erlauben. glGet-Aufrufe sind Aufwand, aber nicht jeder ist zwangsläufig ein GPU-Stall.
4. **Dauerdiagnostik:** observeBind erzeugt auch ohne verbose Filterbeschreibungen, wertet Pipeline-Namen mehrfach aus und aktualisiert atomare Zähler. Kategorien cachen; ausführliche Beschreibungen erst beim Debug-Abruf erzeugen.
5. **Reload-Kosten:** Atlas-Readback, zweite ARGB-Kopie, spriteweise Klassifikation, Vergleich mit SpriteContents und erneuter Metadaten-Readback. Das betrifft Ladezeit und temporären Speicher, nicht direkt jeden Frame. Allein zwei int-Atlaskopien plus RG8UI-CPU-Puffer benötigen bei 4096² etwa 160 MiB, weitere Daten und GPU-Speicher kommen hinzu. Nur diagnostische Vollvergleiche optional machen. Pixelweise Reflection in AlphaEdgeRepair ebenfalls durch direkten typisierten Zugriff ersetzen.

Messplan: gleiche Kamera/Szene, gleiche Auflösung und Sichtweite; Mod aus/NEAREST/XBRZ; wenige/viele Entities; Item groß im Bild; Shader aus/an; 16x und hochauflösendes Pack. Framezeiten und Ausreißer, CPU-/GPU-Zeiten sowie Reload-Dauer messen, nicht allein den FPS-Zähler vergleichen.

## Release-Voraussetzungen

- Versionsstand 1.6.3 in Build und Dokumentation bewusst auf die geplante 1.0.0 umstellen; vorhandene lokale Entwicklungs-JARs nicht mit dem Release verwechseln.
- Minecraft-Abhängigkeit derzeit `~26.1`, also breiter als die gewünschten 26.1.2. Auf den tatsächlich unterstützten Zielbereich eingrenzen.
- Sodium `>=0.9.1` ist bei internen Mixins eine sehr breite Zusage. Getestete Sodium-/Iris-Versionen festhalten und Kompatibilitätsgrenzen bewusst setzen.
- Gradle-Wrapper-JAR fehlt. Shell-Wrapper ohne JAVA_HOME würde `java/bin/java` aufrufen. Funktionierenden vollständigen Standardwrapper einchecken; Loom-SNAPSHOT für reproduzierbare Releases ersetzen.
- Kein Git-Metadatenverzeichnis, keine .gitignore oder CI-Konfiguration im gelieferten Projekt. Build-/Cache-Verzeichnisse vor GitHub-Veröffentlichung ausschließen.
- Tastenkürzel sind hart codiert, nicht als umbelegbare Keybindings registriert; Einstellungen werden nicht persistiert. Für öffentliche Nutzung sinnvoll nachziehen.
- Autorenangabe derzeit OpenAI, keine Kontakt-/Issue-/Source-Links. Echte Projektmetadaten und reproduzierbare Build-Anleitung ergänzen.
- Third-party notices nennen Referenzfamilien, aber keine exakten referenzierten Revisionen. Herkunft, übernommene Bestandteile und erforderliche Lizenz-/Copyright-Hinweise konkret dokumentieren; dies ist keine rechtliche Prüfung.
- Bestehende Markdown-Buildhinweise behaupten eine JDK-21-Umgebung ohne vollständigen Build. Diese Beschreibung trifft auf die hier geprüfte Umgebung nicht mehr zu.
- Gradle meldet `test NO-SOURCE`: vorhandene Harnesses werden nicht automatisch durch `build` geprüft. Sie in eine reproduzierbare Check-/CI-Aufgabe aufnehmen; Originalshader-Fixtures und echte Laufzeitchecks ergänzen.

## Durchgeführte Validierung

- Java 25, installiertes Gradle 9.8.0, `gradle --offline build`: **BUILD SUCCESSFUL**, Loom löst lokal auf 1.18.2 auf. Deprecated API-/Gradle-Hinweise vorhanden.
- PatchHarness: **PASS**, `chars=11706 stats=s=gtexture main=2/1/1 aux=1/0`.
- AtlasMappingHarness: **PASS**, 500 Durchläufe, 727506 Checks.
- Neuer ShaderCoverageAudit mit Original-JAR: Entity ausgewählt/gepatcht; Item nicht ausgewählt/nicht gepatcht.
- Bytecode geprüft: ShaderManager.apply ruft CompilationCache.getShaderSource beim Vorabkompilieren; ItemModelGenerator erzeugt transparente Übergänge und Seitenflächen.
- Keine GLSL-Treiberkompilierung, keine Ingame-Abnahme, keine neue Prüfung der historischen Opaque-Paritätsbehauptung. Erfolgreicher Java-Build bestätigt insbesondere keine Mixin-Injektion zur Laufzeit.

Empfohlene Reihenfolge: Shader-Ladeweg reparieren; World-Items explizit erfassen; Sprite-Grenzen absichern; Seitenflächen/Alpha getrennt reproduzieren und beheben; Shader-/Reload-Matrix testen; erst danach Performance-Optimierungen und Release-Verpackung.

Externe Primärquellen zur Einordnung:

- Minecraft 26.1 Release Notes, unter anderem Aufteilung in core/entity und core/item: https://feedback.minecraft.net/hc/en-us/articles/44551668333837-Minecraft-Java-Edition-26-1
- Iris-Projekt und Zweck als Shaderpack-Unterstützung: https://github.com/IrisShaders/Iris
- Fabric 26.1 und nicht-remappendes Loom: https://www.fabricmc.net/2026/03/14/261.html
