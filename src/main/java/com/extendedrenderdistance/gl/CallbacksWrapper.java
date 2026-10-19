package com.extendedrenderdistance.gl;

import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;

/**
 * Slips the {@link FarRenderer} into the GPU plugin's frame. The client's draw callbacks are swapped for a wrapper
 * that passes every call straight through, and after the top-level {@code preSceneDraw} (framebuffer bound and
 * cleared, sky drawn, scene not yet drawn) draws the far world; it also notes which zones the game draws.
 * <p>
 * Other plugins wrap the callbacks the same way (EV Bloat does), so whatever is installed is wrapped, wrappers
 * included. The client calls {@code draw} every frame, so as long as our wrapper is being called it's in the chain
 * somewhere (maybe wrapped in turn) and is left alone; once it hasn't been for a while, something replaced it (the
 * GPU plugin restarted) and whatever is installed now is wrapped. Only while the GPU plugin is running, and 117 HD
 * isn't. Client thread.
 */
@Slf4j
@Singleton
public class CallbacksWrapper
{
	/** Frames without our wrapper being called before it counts as gone. */
	private static final int LOST_AFTER = 10;

	private final Client client;
	private final PluginManager pluginManager;
	private final FarRenderer far;
	private Wrapper wrapper;
	private boolean drewThisFrame;
	private long frame, lastCalled;

	@Inject
	CallbacksWrapper(Client client, PluginManager pluginManager, FarRenderer far)
	{
		this.client = client;
		this.pluginManager = pluginManager;
		this.far = far;
	}

	/** Wraps whatever is drawing, unless our wrapper is still being called. Call every frame. */
	public void check()
	{
		frame++;
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null || current == wrapper)
			return;
		if (wrapper != null && frame - lastCalled < LOST_AFTER)
			return;
		if (!gpuPluginDrawing())
			return;
		wrapper = new Wrapper(current);
		lastCalled = frame;
		client.setDrawCallbacks(wrapper);
		log.debug("Extended Render Distance: wrapped {}", current.getClass().getName());
	}

	/** The GPU plugin is running and 117 HD (which draws its own way) isn't. */
	private boolean gpuPluginDrawing()
	{
		boolean gpu = false;
		for (Plugin p : pluginManager.getPlugins())
		{
			if (!pluginManager.isPluginActive(p))
				continue;
			if (p instanceof GpuPlugin)
				gpu = true;
			else if (p.getClass().getName().startsWith("rs117."))
				return false;
		}
		return gpu;
	}

	/** Takes the wrapper out, if it's still the one installed. */
	public void unwrap()
	{
		if (wrapper != null && client.getDrawCallbacks() == wrapper)
			client.setDrawCallbacks(wrapper.target);
		wrapper = null;
		far.free();
	}

	private void afterPreSceneDraw(Scene scene, float x, float y, float z, float pitch, float yaw)
	{
		if (drewThisFrame || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		drewThisFrame = true;
		try
		{
			far.draw(x, y, z, pitch, yaw);
		}
		catch (RuntimeException e)
		{
			log.error("Extended Render Distance: drawing the far world failed", e);
		}
	}

	/** Every call passed through to the renderer; a few noted on the way. */
	private final class Wrapper implements DrawCallbacks
	{
		final DrawCallbacks target;

		Wrapper(DrawCallbacks target)
		{
			this.target = target;
		}

		@Override
		public void draw(int overlayColor)
		{
			target.draw(overlayColor);
			lastCalled = frame;
			drewThisFrame = false;
			far.endFrame();
		}

		@Override
		public void preSceneDraw(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ,
			float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
		{
			target.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel,
				level, maxLevel, hideRoofIds);
			afterPreSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}

		@Override
		public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ, float cameraPitch,
			float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
		{
			target.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel,
				hideRoofIds);
			afterPreSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}

		@Override
		public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
		{
			target.drawZoneOpaque(entityProjection, scene, zx, zz);
			if (scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL)
				far.zoneDrawn(zx, zz);
		}

		// ---- the rest, straight through

		@Override
		public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z,
			long hash)
		{
			target.draw(projection, scene, renderable, orientation, x, y, z, hash);
		}

		@Override
		public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
		{
			target.drawScenePaint(scene, paint, plane, tileX, tileZ);
		}

		@Override
		public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
		{
			target.drawSceneTileModel(scene, model, tileX, tileZ);
		}

		@Override
		public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw,
			int plane)
		{
			target.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
		}

		@Override
		public void postDrawScene()
		{
			target.postDrawScene();
		}

		@Override
		public void animate(Texture texture, int diff)
		{
			target.animate(texture, diff);
		}

		@Override
		public void loadScene(Scene scene)
		{
			target.loadScene(scene);
		}

		@Override
		public void swapScene(Scene scene)
		{
			target.swapScene(scene);
		}

		@Override
		public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX,
			int cameraY, int cameraZ, int plane, int msx, int msy)
		{
			return target.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx,
				msy);
		}

		@Override
		public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY)
		{
			return target.zoneInFrustum(zoneX, zoneZ, maxY, minY);
		}

		@Override
		public void loadScene(WorldView worldView, Scene scene)
		{
			target.loadScene(worldView, scene);
		}

		@Override
		public void despawnWorldView(WorldView worldView)
		{
			target.despawnWorldView(worldView);
		}

		@Override
		public void postSceneDraw(Scene scene)
		{
			target.postSceneDraw(scene);
		}

		@Override
		public void drawPass(Projection projection, Scene scene, int pass)
		{
			target.drawPass(projection, scene, pass);
		}

		@Override
		public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
		{
			target.drawZoneAlpha(entityProjection, scene, level, zx, zz);
		}

		@Override
		public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m,
			int orient, int x, int y, int z)
		{
			target.drawDynamic(worldProjection, scene, tileObject, r, m, orient, x, y, z);
		}

		@Override
		public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
			Renderable r, Model m, int orient, int x, int y, int z)
		{
			target.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
		}

		@Override
		public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x,
			int y, int z)
		{
			target.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
		}

		@Override
		public void invalidateZone(Scene scene, int zx, int zz)
		{
			target.invalidateZone(scene, zx, zz);
		}
	}
}
