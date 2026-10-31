package tech.naven.inject;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.naven.modules.impl.render.EspRenderTypes;

/**
 * Hot-injection replacement for the {@code RegisterRenderPipelinesEvent} listener that
 * {@code NavenClientSetup} adds to the mod event bus.
 *
 * <p>1.21.10 keeps every pipeline in one static map:</p>
 *
 * <pre>
 *   private static final Map&lt;ResourceLocation, RenderPipeline&gt; RenderPipelines.PIPELINES_BY_LOCATION;   // new HashMap&lt;&gt;()
 *   private static RenderPipeline RenderPipelines.register(RenderPipeline pipeline) { PIPELINES_BY_LOCATION.put(pipeline.getLocation(), pipeline); return pipeline; }
 *   public  static void RenderPipelines.registerCustomPipelines() { ModLoader.postEvent(new RegisterRenderPipelinesEvent(RenderPipelines::registerCustomPipelines$lambda)); }
 * </pre>
 *
 * <p>{@code registerCustomPipelines()} is called once from {@code ShaderManager#apply} during the initial resource
 * reload; the event's registrar is
 * {@code RenderPipelines.lambda$registerCustomPipelines$0} which does {@code map.putIfAbsent(...)} and throws on a
 * duplicate location. Since our listener never made it onto the mod event bus we replay exactly that step from
 * here - through {@link EspRenderTypes#registerPipelines(RegisterRenderPipelinesEvent)} so there is a single source
 * of truth for the three pipelines - and then do the second half that
 * {@code ShaderManager#apply} would have done: {@code GpuDevice#precompilePipeline(pipeline,
 * ShaderManager::getShader)}.</p>
 *
 * <p>Compilation matters: {@code ShaderManager#apply} compiles <em>all</em> pipelines of
 * {@code RenderPipelines#getStaticPipelines()} at reload time and throws when one of them is invalid, but a
 * pipeline registered after that reload would never be compiled until the next reload. Compiling eagerly here also
 * surfaces shader errors in the log instead of at the first draw.</p>
 *
 * <p>Never throws; every failure is logged.</p>
 */
public final class PipelineBootstrap {
   private static final Logger LOGGER = LogManager.getLogger("Naven/Pipelines");

   /** {@code net.minecraft.client.renderer.RenderPipelines#PIPELINES_BY_LOCATION}. */
   private static final String MAP_FIELD = "PIPELINES_BY_LOCATION";

   /** {@code net.minecraft.client.renderer.RenderPipelines#register(RenderPipeline)} - fallback route. */
   private static final String REGISTER_METHOD = "register";

   private static volatile boolean registered;

   private PipelineBootstrap() {
   }

   /**
    * Registers the ESP pipelines into {@code RenderPipelines.PIPELINES_BY_LOCATION} and compiles them.
    * Safe to call from any thread and any number of times; never throws.
    */
   public static void register() {
      try {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft == null) {
            LOGGER.warn("[Naven] PipelineBootstrap.register(): Minecraft is not initialised yet; call again once the game is up");
            return;
         }

         if (!RenderSystem.isOnRenderThread()) {
            LOGGER.info("[Naven] PipelineBootstrap.register(): deferring to the render thread");
            minecraft.execute(PipelineBootstrap::register);
            return;
         }

         registerOnRenderThread(minecraft);
      } catch (Throwable t) {
         LOGGER.error("[Naven] PipelineBootstrap.register() failed", t);
      }
   }

   /** @return {@code true} once the pipelines have been pushed into the vanilla registry. */
   public static boolean isRegistered() {
      return registered;
   }

   private static void registerOnRenderThread(Minecraft minecraft) {
      Map<ResourceLocation, RenderPipeline> pipelines = pipelinesByLocation();
      List<RenderPipeline> touched = new ArrayList<>(3);

      if (pipelines != null) {
         // Reuse the mod-form registration code path, but feed it a registrar that writes into the live map.
         // putIfAbsent mirrors RenderPipelines.lambda$registerCustomPipelines$0 without its hard failure.
         RegisterRenderPipelinesEvent event = new RegisterRenderPipelinesEvent(pipeline -> {
            touched.add(pipeline);
            ResourceLocation location = pipeline.getLocation();
            RenderPipeline previous = pipelines.putIfAbsent(location, pipeline);
            if (previous == null) {
               LOGGER.info("[Naven] registered render pipeline {}", location);
            } else if (previous != pipeline) {
               LOGGER.warn("[Naven] render pipeline {} was already registered elsewhere; keeping the existing one", location);
            }
         });

         EspRenderTypes.registerPipelines(event);
      } else {
         LOGGER.warn("[Naven] {}#{} is not reachable, falling back to RenderPipelines#{}", RenderPipelines.class.getSimpleName(), MAP_FIELD, REGISTER_METHOD);
         RegisterRenderPipelinesEvent event = new RegisterRenderPipelinesEvent(pipeline -> {
            touched.add(pipeline);
            registerReflectively(pipeline);
         });

         EspRenderTypes.registerPipelines(event);
      }

      if (touched.isEmpty()) {
         LOGGER.error("[Naven] no ESP render pipeline was handed to the registrar");
         return;
      }

      registered = true;
      compile(minecraft, touched);
   }

   private static void compile(Minecraft minecraft, List<RenderPipeline> pipelines) {
      GpuDevice device;
      ShaderManager shaders;
      try {
         device = RenderSystem.getDevice();
         shaders = minecraft.getShaderManager();
      } catch (Throwable t) {
         LOGGER.warn("[Naven] could not reach the GPU device / shader manager; skipping the eager shader compile", t);
         return;
      }

      if (device == null || shaders == null) {
         LOGGER.warn(
            "[Naven] GPU device or shader manager is not ready yet; the pipelines stay uncompiled until the next resource reload compiles RenderPipelines#getStaticPipelines()"
         );
         return;
      }

      for (RenderPipeline pipeline : pipelines) {
         try {
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, shaders::getShader);
            if (compiled.isValid()) {
               LOGGER.info("[Naven] compiled render pipeline {}", pipeline.getLocation());
            } else {
               LOGGER.error(
                  "[Naven] render pipeline {} failed to compile; ESP rendering will be skipped until the shaders load",
                  pipeline.getLocation()
               );
            }
         } catch (Throwable t) {
            LOGGER.error("[Naven] could not compile render pipeline " + pipeline.getLocation(), t);
         }
      }
   }

   @SuppressWarnings("unchecked")
   private static Map<ResourceLocation, RenderPipeline> pipelinesByLocation() {
      try {
         Field field = RenderPipelines.class.getDeclaredField(MAP_FIELD);
         field.setAccessible(true);
         Object value = field.get(null);
         if (value instanceof Map<?, ?> map) {
            return (Map<ResourceLocation, RenderPipeline>)map;
         }
      } catch (Throwable t) {
         LOGGER.error("[Naven] could not read RenderPipelines#{}", MAP_FIELD, t);
      }

      return null;
   }

   private static void registerReflectively(RenderPipeline pipeline) {
      try {
         Method register = RenderPipelines.class.getDeclaredMethod(REGISTER_METHOD, RenderPipeline.class);
         register.setAccessible(true);
         register.invoke(null, pipeline);
         LOGGER.info("[Naven] registered render pipeline {} through RenderPipelines#{}", pipeline.getLocation(), REGISTER_METHOD);
      } catch (Throwable t) {
         LOGGER.error("[Naven] could not register render pipeline " + pipeline.getLocation(), t);
      }
   }
}
