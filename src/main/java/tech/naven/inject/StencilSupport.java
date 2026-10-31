package tech.naven.inject;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.ClientHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Hot-injection replacement for {@code ConfigureMainRenderTargetEvent#enableStencil()}.
 *
 * <p>A regular mod reacts to {@code net.neoforged.neoforge.client.event.ConfigureMainRenderTargetEvent} on the
 * mod event bus; NeoForge consumes the event in
 * {@code ClientHooks#instantiateMainTarget(int,int)}:</p>
 *
 * <pre>
 *   ConfigureMainRenderTargetEvent event = ModLoader.postEventWithReturn(new ConfigureMainRenderTargetEvent());
 *   return new MainTarget(width, height, event.isStencilEnabled());
 * </pre>
 *
 * <p>That happens exactly once, inside the {@code Minecraft} constructor, long before a DLL injection can run.
 * {@code MainTarget(int,int,boolean)} only forwards the flag to
 * {@code RenderTarget#&lt;init&gt;(String,boolean,boolean)} which stores it in the
 * <em>public final boolean</em> field {@code com.mojang.blaze3d.pipeline.RenderTarget#useStencil}. Everything else
 * derives from that field:</p>
 *
 * <ul>
 *    <li>{@code MainTarget#allocateDepthAttachment} (construction) and
 *        {@code RenderTarget#createBuffers(int,int)} (every resize) pick
 *        {@code ClientHooks.getStencilFormat()} (= {@code DEPTH24_STENCIL8} / {@code DEPTH32_STENCIL8}) instead of
 *        {@code TextureFormat.DEPTH32} when {@code useStencil} is set;</li>
 *    <li>{@code LevelRenderer} copies {@code minecraft.getMainRenderTarget().useStencil} into the
 *        {@code RenderTargetDescriptor} of the level frame graph, so the level target gets a stencil attachment too.</li>
 * </ul>
 *
 * <p>To reproduce the mod-form behaviour we flip that field on the live main target and force a depth
 * re-allocation. The field is {@code final}, which reflection can still write for an <em>instance</em> field on
 * JDK 21 ({@code Field#setBoolean} after {@code setAccessible(true)}); a {@code sun.misc.Unsafe} write is kept as a
 * fallback.</p>
 *
 * <p>Differences to the mod form: the target is rebuilt <em>after</em> the game started, so the depth/stencil
 * texture is recreated once at injection time (a one-frame cost) and any GPU resource that captured the previous
 * depth texture view is stale for that frame. {@code Minecraft#resizeDisplay()} is not used - only
 * {@code RenderTarget#resize(int,int)} - so the GUI scale and screen layout are untouched.</p>
 */
public final class StencilSupport {
   private static final Logger LOGGER = LogManager.getLogger("Naven/Stencil");

   /** {@code com.mojang.blaze3d.pipeline.RenderTarget#useStencil} - the single source of truth for stencil. */
   private static final String STENCIL_FIELD = "useStencil";

   private static volatile boolean enabled;

   private StencilSupport() {
   }

   /**
    * Enables the stencil attachment on the main render target, equivalent to the mod form's
    * {@code event.enableStencil()}. Safe to call from any thread and any number of times; never throws.
    */
   public static void enable() {
      try {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft == null) {
            LOGGER.warn("[Naven] StencilSupport.enable(): Minecraft is not initialised yet; call again once the game is up");
            return;
         }

         if (!RenderSystem.isOnRenderThread()) {
            LOGGER.info("[Naven] StencilSupport.enable(): deferring to the render thread");
            minecraft.execute(StencilSupport::enable);
            return;
         }

         enableOnRenderThread(minecraft);
      } catch (Throwable t) {
         LOGGER.error("[Naven] StencilSupport.enable() failed", t);
      }
   }

   /** @return {@code true} once the main render target owns a depth/stencil attachment. */
   public static boolean isEnabled() {
      return enabled;
   }

   private static void enableOnRenderThread(Minecraft minecraft) {
      RenderTarget main = minecraft.getMainRenderTarget();
      if (main == null) {
         LOGGER.error("[Naven] StencilSupport: the main render target does not exist yet");
         return;
      }

      if (main.useStencil) {
         enabled = true;
         LOGGER.info("[Naven] StencilSupport: the main render target already has a stencil attachment");
         return;
      }

      if (!setUseStencil(main)) {
         LOGGER.error("[Naven] StencilSupport: could not set RenderTarget#useStencil; stencil based rendering stays unavailable");
         return;
      }

      int width = main.width > 0 ? main.width : minecraft.getWindow().getWidth();
      int height = main.height > 0 ? main.height : minecraft.getWindow().getHeight();
      width = Math.max(1, width);
      height = Math.max(1, height);

      TextureFormat format;
      try {
         format = ClientHooks.getStencilFormat();
      } catch (Throwable t) {
         format = null;
         LOGGER.warn("[Naven] StencilSupport: ClientHooks.getStencilFormat() is not available", t);
      }

      // destroyBuffers() + createBuffers(w,h): RenderTarget#createBuffers re-reads useStencil and allocates
      // ClientHooks.getStencilFormat() as the depth attachment, which is what the mod form gets for free.
      main.resize(width, height);

      enabled = true;
      LOGGER.info(
         "[Naven] StencilSupport: main render target rebuilt with stencil ({}x{}, depth format {}); useStencil={}",
         width,
         height,
         format,
         main.useStencil
      );
   }

   private static boolean setUseStencil(RenderTarget target) {
      try {
         Field field = RenderTarget.class.getDeclaredField(STENCIL_FIELD);
         field.setAccessible(true);
         field.setBoolean(target, true);
         if (target.useStencil) {
            return true;
         }
      } catch (Throwable t) {
         LOGGER.warn("[Naven] StencilSupport: Field#setBoolean on RenderTarget#useStencil failed, trying Unsafe", t);
      }

      try {
         Field field = RenderTarget.class.getDeclaredField(STENCIL_FIELD);
         Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
         Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
         theUnsafe.setAccessible(true);
         Object unsafe = theUnsafe.get(null);
         long offset = (Long)unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
         unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(unsafe, target, offset, true);
         return target.useStencil;
      } catch (Throwable t) {
         LOGGER.error("[Naven] StencilSupport: could not write RenderTarget#useStencil", t);
         return false;
      }
   }
}
