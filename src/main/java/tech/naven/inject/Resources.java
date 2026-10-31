package tech.naven.inject;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hot-injection replacement for the two "load time" resource facilities FML normally provides for a regular mod
 * jar:
 *
 * <ul>
 *    <li>{@code Xxx.class.getResourceAsStream("/assets/heypixel/...")} - works for a mod jar on the game class
 *        path, but our classes are defined by the agent into the NeoForge game class loader, so the resource
 *        lookup is routed through {@link #open(String)} / {@link #file(String)} which read the jar directly.</li>
 *    <li>The built-in resource pack FML registers for a mod jar - without it
 *        {@code ResourceLocation("heypixel", ...)} / {@code ResourceLocation("naven", ...)} and
 *        {@code ShaderManager} (post_effect json + glsl) cannot see our {@code assets/} tree.</li>
 * </ul>
 *
 * <p>All methods are thread safe and idempotent: {@link #init(Path, Path)} may be called again with the same
 * arguments, {@link #registerPack()} may be called any number of times from any thread and never throws.</p>
 */
public final class Resources {
   private static final Logger LOGGER = LogManager.getLogger("Naven/Resources");

   /** Pack id used for the hot-injected resource pack. */
   private static final String PACK_ID = "naven_inject";

   /** Namespaces the mod ships assets for; used as a fallback when the extracted tree cannot be listed. */
   private static final Set<String> FALLBACK_NAMESPACES = Set.of("heypixel", "minecraft");

   /** Jar prefixes that are unpacked into the extract directory when a resource pack is built. */
   private static final String[] EXTRACT_ROOTS = {"assets/", "pack.mcmeta", "META-INF/neoforge.mods.toml"};

   private static final Object LOCK = new Object();
   private static final Set<String> EXTRACTED = ConcurrentHashMap.newKeySet();

   private static volatile Path jarPath;
   private static volatile Path extractDir;

   private static volatile PackResources pack;
   private static volatile boolean reloadHookInstalled;
   private static volatile boolean shaderRefreshedOnce;
   private static volatile Set<ResourceLocation> postEffectIdCache;

   private Resources() {
   }

   /**
    * Wires the jar up.
    *
    * @param jarPath    the mod jar the assets are read from (may be {@code null}, then only the extract directory
    *                   is used)
    * @param extractDir directory the jar is unpacked into so that Minecraft can read it as a resource pack
    * @throws IOException when the extract directory cannot be created
    */
   public static void init(Path jarPath, Path extractDir) throws IOException {
      Path jar = jarPath == null ? null : jarPath.toAbsolutePath().normalize();
      Path base = extractDir == null ? null : extractDir.toAbsolutePath().normalize();

      if (jar == null) {
         LOGGER.warn("[Naven] Resources.init() without a jar path; only the extract directory will be searched");
      } else if (!Files.isRegularFile(jar)) {
         LOGGER.warn("[Naven] Resources.init(): {} is not a regular file; only the extract directory will be searched", jar);
      }

      if (base != null) {
         Files.createDirectories(base);
      }

      synchronized (LOCK) {
         boolean sameSource = Objects.equals(jar, Resources.jarPath) && Objects.equals(base, Resources.extractDir);
         Resources.jarPath = jar;
         Resources.extractDir = base;
         if (!sameSource) {
            // A different jar/directory invalidates everything derived from the previous one.
            EXTRACTED.clear();
            pack = null;
            reloadHookInstalled = false;
            shaderRefreshedOnce = false;
            postEffectIdCache = null;
         }
      }

      LOGGER.info("[Naven] Resources initialised (jar={}, extractDir={})", jar, base);
   }

   /** @return {@code true} when {@link #init(Path, Path)} has been called. */
   public static boolean isAvailable() {
      return jarPath != null || extractDir != null;
   }

   /** @return the directory the jar is unpacked into, or {@code null}. */
   public static Path baseDir() {
      return extractDir;
   }

   /** @return the mod jar, or {@code null}. */
   public static Path jarPath() {
      return jarPath;
   }

   /**
    * Opens a resource of the mod jar. The jar is consulted first, then the extract directory (the pack the game
    * reads), then - for a non injected run - the class loader that defined this class.
    *
    * @param path e.g. {@code "/assets/heypixel/VcX6svVqmeT8/shader/text.vert"}
    * @throws FileNotFoundException when the resource exists in none of those places
    */
   public static InputStream open(String path) throws IOException {
      String clean = normalize(path);
      if (clean.isEmpty()) {
         throw new FileNotFoundException("empty resource path");
      }

      byte[] fromJar = readFromJar(clean);
      if (fromJar != null) {
         return new ByteArrayInputStream(fromJar);
      }

      Path base = extractDir;
      if (base != null) {
         Path candidate = base.resolve(clean);
         if (Files.isRegularFile(candidate)) {
            return Files.newInputStream(candidate);
         }
      }

      ClassLoader own = Resources.class.getClassLoader();
      if (own != null) {
         InputStream in = own.getResourceAsStream(clean);
         if (in != null) {
            return in;
         }
      }

      throw new FileNotFoundException(
         "Naven resource not found: " + clean + " (jar=" + jarPath + ", extractDir=" + base + ")"
      );
   }

   /** @return {@code true} when {@link #open(String)} would find the resource. */
   public static boolean exists(String path) {
      String clean = normalize(path);
      if (clean.isEmpty()) {
         return false;
      }

      Path jar = jarPath;
      if (jar != null && Files.isRegularFile(jar)) {
         try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(clean);
            if (entry != null && !entry.isDirectory()) {
               return true;
            }
         } catch (IOException ignored) {
         }
      }

      Path base = extractDir;
      if (base != null && Files.isRegularFile(base.resolve(clean))) {
         return true;
      }

      ClassLoader own = Resources.class.getClassLoader();
      return own != null && own.getResource(clean) != null;
   }

   /** Reads a resource of the mod jar completely. */
   public static byte[] read(String path) throws IOException {
      try (InputStream in = open(path)) {
         return in.readAllBytes();
      }
   }

   /** Reads a resource of the mod jar as UTF-8 text. */
   public static String readString(String path) throws IOException {
      return new String(read(path), StandardCharsets.UTF_8);
   }

   /**
    * Unpacks a single resource on demand and returns it as a {@link File}.
    *
    * @throws IOException when the resource does not exist or cannot be written
    */
   public static File file(String path) throws IOException {
      String clean = normalize(path);
      Path base = extractDir;
      if (base == null) {
         throw new IOException("Naven Resources were not initialised with an extract directory (path: " + clean + ")");
      }

      Path target = base.resolve(clean);
      if (!Files.isRegularFile(target)) {
         synchronized (LOCK) {
            if (!Files.isRegularFile(target)) {
               extractEntry(clean, target);
            }
         }
      }

      return target.toFile();
   }

   /**
    * Makes the mod's {@code assets/} tree visible to the vanilla resource stack.
    *
    * <p>The jar is unpacked into {@link #baseDir()} and turned into a {@link PathPackResources}. That pack is pushed
    * into the live {@link FallbackResourceManager} of every namespace it provides ({@code heypixel} and
    * {@code minecraft}); {@code FallbackResourceManager#getResource} walks its {@code fallbacks} list
    * <em>backwards</em>, so a pushed pack has the highest priority - exactly the slot FML gives a mod's built-in
    * pack. No resource reload is needed and none is triggered.</p>
    *
    * <p>A {@link SimplePreparableReloadListener} is registered as well so the pack is re-injected after every future
    * resource reload (F5, resource pack change, ...), which replaces the {@link MultiPackResourceManager} instance.</p>
    *
    * <p>Never throws; every failure is logged. Idempotent - repeated calls only re-check the live managers.</p>
    */
   public static void registerPack() {
      try {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft == null) {
            LOGGER.warn("[Naven] registerPack(): Minecraft is not initialised yet, deferring");
            return;
         }

         if (jarPath == null && extractDir == null) {
            LOGGER.error("[Naven] registerPack(): Resources.init(...) was never called");
            return;
         }

         PackResources resources = pack();
         if (resources == null) {
            LOGGER.error("[Naven] registerPack(): the mod assets could not be turned into a resource pack");
            return;
         }

         boolean changed = injectInto(minecraft.getResourceManager(), resources);
         installReloadHook(minecraft);
         if (changed) {
            refreshShaderManager();
         }
      } catch (Throwable t) {
         LOGGER.error("[Naven] registerPack() failed; resources may stay invisible to the ResourceManager", t);
      }
   }

   /**
    * Rebuilds the {@code ShaderManager} snapshot (shader sources + {@code post_effect} configs) against the live
    * {@link ResourceManager}.
    *
    * <p>{@code ShaderManager#prepare(ResourceManager, ProfilerFiller)} turns the resource tree into an immutable
    * {@code ShaderManager$Configs} record ({@code shaderSources}, {@code postChains}) and
    * {@code ShaderManager#apply(Configs, ResourceManager, ProfilerFiller)} installs it as the new
    * {@code CompilationCache} while compiling every registered {@code RenderPipeline}. Both run once per resource
    * reload only - so a post effect that lives in our jar ({@code minecraft:post_effect/motion_blur.json}) is
    * unknown to {@code ShaderManager#getPostChain} until that snapshot has seen it. Hot-injecting the pack fixes
    * {@code ResourceManager#getResource} but not this snapshot, hence this method.</p>
    *
    * <p>The work is skipped when the snapshot already knows every {@code post_effect} json we ship. Never throws.</p>
    */
   public static void refreshShaderManager() {
      try {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft == null) {
            return;
         }

         if (!RenderSystem.isOnRenderThread()) {
            minecraft.execute(Resources::refreshShaderManager);
            return;
         }

         ShaderManager shaderManager = minecraft.getShaderManager();
         if (shaderManager == null) {
            LOGGER.warn("[Naven] refreshShaderManager(): no shader manager yet; skipping");
            return;
         }

         if (!shaderSnapshotIsStale(shaderManager)) {
            return;
         }

         ResourceManager resourceManager = minecraft.getResourceManager();
         ProfilerFiller profiler = Profiler.get();
         Method prepare = ShaderManager.class.getDeclaredMethod("prepare", ResourceManager.class, ProfilerFiller.class);
         prepare.setAccessible(true);
         Object configs = prepare.invoke(shaderManager, resourceManager, profiler);
         Method apply = ShaderManager.class.getDeclaredMethod("apply", configs.getClass(), ResourceManager.class, ProfilerFiller.class);
         apply.setAccessible(true);
         apply.invoke(shaderManager, configs, resourceManager, profiler);
         shaderRefreshedOnce = true;
         LOGGER.info("[Naven] ShaderManager snapshot rebuilt from the live ResourceManager (post effects {} are now known)", postEffectIds());
      } catch (Throwable t) {
         LOGGER.error("[Naven] refreshShaderManager() failed; post effects shipped in the jar may stay unavailable", t);
      }
   }

   // ------------------------------------------------------------------ internals

   private static String normalize(String path) {
      if (path == null) {
         return "";
      }

      String clean = path.replace('\\', '/');
      while (clean.startsWith("/")) {
         clean = clean.substring(1);
      }

      while (clean.startsWith("./")) {
         clean = clean.substring(2);
      }

      return clean;
   }

   private static byte[] readFromJar(String entryName) {
      Path jar = jarPath;
      if (jar == null || !Files.isRegularFile(jar)) {
         return null;
      }

      try (ZipFile zip = new ZipFile(jar.toFile())) {
         ZipEntry entry = zip.getEntry(entryName);
         if (entry == null || entry.isDirectory()) {
            return null;
         }

         try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
         }
      } catch (IOException e) {
         LOGGER.warn("[Naven] could not read {} from {}", entryName, jar, e);
         return null;
      }
   }

   private static void extractEntry(String entryName, Path target) throws IOException {
      Path jar = jarPath;
      if (jar != null && Files.isRegularFile(jar)) {
         try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null && !entry.isDirectory()) {
               Files.createDirectories(target.getParent());
               try (InputStream in = zip.getInputStream(entry)) {
                  Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
               }

               EXTRACTED.add(entryName);
               return;
            }
         }
      }

      // Falls back to the extract directory / class path form (never to the jar, it was just checked).
      try (InputStream in = open(entryName)) {
         Files.createDirectories(target.getParent());
         Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
         EXTRACTED.add(entryName);
      }
   }

   /** Unpacks every interesting entry of the jar into the extract directory. */
   private static void extractAll(Path base) throws IOException {
      Path jar = jarPath;
      if (jar == null || !Files.isRegularFile(jar)) {
         return;
      }

      Files.createDirectories(base);
      try (ZipFile zip = new ZipFile(jar.toFile())) {
         Enumeration<? extends ZipEntry> entries = zip.entries();
         while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
               continue;
            }

            String name = entry.getName();
            if (!isExtractable(name)) {
               continue;
            }

            Path target = base.resolve(name).normalize();
            if (!target.startsWith(base)) {
               LOGGER.warn("[Naven] refusing to unpack suspicious jar entry {}", name);
               continue;
            }

            if (EXTRACTED.contains(name) && Files.isRegularFile(target)) {
               continue;
            }

            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
               Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            EXTRACTED.add(name);
         }
      }

      LOGGER.info("[Naven] unpacked {} asset entries into {}", EXTRACTED.size(), base);
   }

   private static boolean isExtractable(String name) {
      for (String root : EXTRACT_ROOTS) {
         if (name.startsWith(root)) {
            return true;
         }
      }

      return false;
   }

   private static PackResources pack() {
      PackResources existing = pack;
      if (existing != null) {
         return existing;
      }

      synchronized (LOCK) {
         if (pack != null) {
            return pack;
         }

         Path base = extractDir;
         if (base == null) {
            LOGGER.error("[Naven] no extract directory configured; cannot build a resource pack");
            return null;
         }

         try {
            Files.createDirectories(base);
            extractAll(base);
            writePackMeta(base);

            PackLocationInfo location = new PackLocationInfo(
               PACK_ID, Component.literal("Naven (injected)"), PackSource.BUILT_IN, Optional.empty()
            );
            pack = new PathPackResources(location, base);
            LOGGER.info("[Naven] created resource pack '{}' over {}", PACK_ID, base);
            return pack;
         } catch (Throwable t) {
            LOGGER.error("[Naven] could not create the resource pack over {}", base, t);
            return null;
         }
      }
   }

   /**
    * Synthesises {@code pack.mcmeta} through the vanilla codec so it always matches the running version's format
    * (1.21.10 uses major/minor {@link PackFormat}s, not a plain {@code pack_format} int).
    */
   private static void writePackMeta(Path base) {
      Path meta = base.resolve("pack.mcmeta");
      if (Files.isRegularFile(meta)) {
         return;
      }

      try {
         PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
         PackMetadataSection section = new PackMetadataSection(
            Component.literal("Naven (injected)"), new InclusiveRange<>(format)
         );
         var encoded = PackMetadataSection.forPackType(PackType.CLIENT_RESOURCES)
            .codec()
            .encodeStart(JsonOps.INSTANCE, section)
            .result()
            .orElse(null);
         if (encoded == null) {
            return;
         }

         com.google.gson.JsonObject root = new com.google.gson.JsonObject();
         root.add("pack", encoded);
         Files.writeString(meta, root.toString(), StandardCharsets.UTF_8);
         LOGGER.info("[Naven] wrote pack.mcmeta for pack format {}", format);
      } catch (Throwable t) {
         LOGGER.warn("[Naven] could not synthesise pack.mcmeta (the hot-injected pack works without it)", t);
      }
   }

   private static void installReloadHook(Minecraft minecraft) {
      if (reloadHookInstalled) {
         return;
      }

      try {
         ResourceManager manager = minecraft.getResourceManager();
         if (manager instanceof ReloadableResourceManager reloadable) {
            reloadable.registerReloadListener(new PackInjector());
            reloadHookInstalled = true;
            LOGGER.info("[Naven] registered the resource pack re-injection reload listener");
         }
      } catch (Throwable t) {
         LOGGER.warn("[Naven] could not register the reload listener; the pack will not survive a resource reload", t);
      }
   }

   /**
    * Pushes the pack into the live {@link MultiPackResourceManager} of the given resource manager.
    *
    * @return {@code true} when at least one namespace manager did not know the pack before this call
    */
   private static boolean injectInto(ResourceManager manager, PackResources resources) {
      MultiPackResourceManager multi = unwrap(manager);
      if (multi == null) {
         LOGGER.error("[Naven] could not reach the live MultiPackResourceManager (got {}); assets stay invisible", manager);
         return false;
      }

      Object rawMap = readField(MultiPackResourceManager.class, multi, "namespacedManagers");
      if (!(rawMap instanceof Map<?, ?> namespacedManagers)) {
         LOGGER.error("[Naven] MultiPackResourceManager#namespacedManagers is not reachable; assets stay invisible");
         return false;
      }

      boolean changed = false;

      List<String> injected = new ArrayList<>();
      for (String namespace : namespacesOf(resources)) {
         try {
            FallbackResourceManager fallback;
            Object existing = namespacedManagers.get(namespace);
            if (existing instanceof FallbackResourceManager resourceManager) {
               fallback = resourceManager;
            } else {
               fallback = new FallbackResourceManager(PackType.CLIENT_RESOURCES, namespace);
               @SuppressWarnings("unchecked")
               Map<String, Object> writable = (Map<String, Object>)namespacedManagers;
               writable.put(namespace, fallback);
               LOGGER.info("[Naven] created a resource namespace manager for '{}'", namespace);
            }

            if (!alreadyPushed(fallback, resources)) {
               fallback.push(resources);
               changed = true;
            }

            injected.add(namespace);
         } catch (Throwable t) {
            LOGGER.error("[Naven] could not inject the pack into namespace '{}'", namespace, t);
         }
      }

      LOGGER.info("[Naven] resource pack injected for namespaces {} (no reload was triggered)", injected);
      return changed;
   }

   /**
    * @return {@code true} when a {@code post_effect} json we ship is missing from the current
    *         {@code ShaderManager$Configs#postChains()} snapshot, i.e. {@link #refreshShaderManager()} has work to do
    */
   private static boolean shaderSnapshotIsStale(ShaderManager shaderManager) {
      try {
         Field cacheField = ShaderManager.class.getDeclaredField("compilationCache");
         cacheField.setAccessible(true);
         Object cache = cacheField.get(shaderManager);
         if (cache == null) {
            return true;
         }

         Field configsField = cache.getClass().getDeclaredField("configs");
         configsField.setAccessible(true);
         Object configs = configsField.get(cache);
         if (configs == null) {
            return true;
         }

         Object chains = configs.getClass().getMethod("postChains").invoke(configs);
         if (!(chains instanceof Map<?, ?> postChains)) {
            return true;
         }

         Set<ResourceLocation> ids = postEffectIds();
         if (ids.isEmpty()) {
            return !shaderRefreshedOnce;
         }

         for (ResourceLocation id : ids) {
            if (!postChains.containsKey(id)) {
               LOGGER.info("[Naven] the shader snapshot does not know {} yet", id);
               return true;
            }
         }

         return false;
      } catch (Throwable t) {
         LOGGER.warn("[Naven] could not inspect the shader snapshot", t);
         return !shaderRefreshedOnce;
      }
   }

   /** @return the {@code minecraft:} ids of every {@code assets/minecraft/post_effect/*.json} of this jar. */
   private static Set<ResourceLocation> postEffectIds() {
      Set<ResourceLocation> cached = postEffectIdCache;
      if (cached != null) {
         return cached;
      }

      Set<ResourceLocation> ids = new LinkedHashSet<>();
      Path base = extractDir;
      Path directory = base == null ? null : base.resolve("assets/minecraft/post_effect");
      if (directory != null && Files.isDirectory(directory)) {
         try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
               String name = path.getFileName().toString();
               ids.add(ResourceLocation.withDefaultNamespace(name.substring(0, name.length() - ".json".length())));
            });
         } catch (IOException e) {
            LOGGER.warn("[Naven] could not list the post_effect directory {}", directory, e);
         }
      }

      postEffectIdCache = ids;
      return ids;
   }

   private static Set<String> namespacesOf(PackResources resources) {
      Set<String> namespaces = new LinkedHashSet<>();
      try {
         Set<String> fromPack = resources.getNamespaces(PackType.CLIENT_RESOURCES);
         if (fromPack != null) {
            namespaces.addAll(fromPack);
         }
      } catch (Throwable t) {
         LOGGER.warn("[Naven] could not list the namespaces of the injected pack", t);
      }

      if (namespaces.isEmpty()) {
         namespaces.addAll(FALLBACK_NAMESPACES);
      }

      // The mod reads 'minecraft' assets (post_effect json / glsl) even when the tree could not be listed.
      namespaces.add("minecraft");
      return namespaces;
   }

   private static boolean alreadyPushed(FallbackResourceManager manager, PackResources resources) {
      try {
         Object entries = FallbackResourceManager.class.getField("fallbacks").get(manager);
         if (!(entries instanceof List<?> list)) {
            return false;
         }

         for (Object entry : list) {
            try {
               Field field = entry.getClass().getDeclaredField("resources");
               field.setAccessible(true);
               if (field.get(entry) == resources) {
                  return true;
               }
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable ignored) {
      }

      return false;
   }

   private static MultiPackResourceManager unwrap(ResourceManager manager) {
      ResourceManager current = manager;
      for (int depth = 0; depth < 8 && current != null; depth++) {
         if (current instanceof MultiPackResourceManager multi) {
            return multi;
         }

         if (current instanceof ReloadableResourceManager reloadable) {
            Object next = readField(ReloadableResourceManager.class, reloadable, "resources");
            current = next instanceof ResourceManager resourceManager ? resourceManager : null;
            continue;
         }

         return null;
      }

      return null;
   }

   private static Object readField(Class<?> owner, Object instance, String name) {
      try {
         Field field = owner.getDeclaredField(name);
         field.setAccessible(true);
         return field.get(instance);
      } catch (Throwable t) {
         LOGGER.error("[Naven] could not read {}#{}", owner.getSimpleName(), name, t);
         return null;
      }
   }

   /** Re-injects the pack into the freshly built {@link MultiPackResourceManager} after every reload. */
   private static final class PackInjector extends SimplePreparableReloadListener<Void> {
      @Override
      protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
         return null;
      }

      @Override
      protected void apply(Void value, ResourceManager resourceManager, ProfilerFiller profiler) {
         try {
            PackResources resources = pack();
            if (resources == null) {
               return;
            }

            boolean changed = injectInto(resourceManager, resources);
            if (changed) {
               // The reload that just ran built its ShaderManager snapshot *before* this listener applied, so the
               // snapshot is stale again. Rebuild it once the reload has fully finished (next client tick) instead
               // of racing the other listeners' apply stage.
               Minecraft minecraft = Minecraft.getInstance();
               if (minecraft != null) {
                  minecraft.execute(Resources::refreshShaderManager);
               }
            }
         } catch (Throwable t) {
            LOGGER.error("[Naven] re-injecting the resource pack after a reload failed", t);
         }
      }

      @Override
      public String getName() {
         return "Naven injected resources";
      }
   }
}
