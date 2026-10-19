package com.extendedrenderdistance.gl;

import com.extendedrenderdistance.ExtendedRenderDistanceConfig;
import com.extendedrenderdistance.world.ObjectMesher;
import com.extendedrenderdistance.world.Reach;
import com.extendedrenderdistance.world.TerrainMesher;
import com.extendedrenderdistance.world.WorldCache;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws the world beyond the normal view into the GPU plugin's frame, from the game cache, only ever the part that
 * belongs with where the player is (the {@link Reach}: in a dungeon, that dungeon; in an instance, nothing): the ground out to the
 * terrain distance (levels of detail), all scenery out to the scenery distance and buildings and
 * tall things out to the building distance (each a {@link RegionLayer}).
 * <p>
 * It runs inside the GPU plugin's scene pass (see {@link CallbacksWrapper}): after the GPU plugin has cleared its
 * framebuffer and drawn the sky, before the scene. It uses the GPU plugin's exact projection and shares its depth
 * buffer, with its depth pushed back a little so the real scene always wins where both draw; it leaves out whatever
 * the game draws itself (see {@link DrawnZones}): every object and floor in a zone the GPU plugin drew, and the
 * ground there but for a zone's overlap at the edge, so nothing is ever drawn twice. The GPU plugin's
 * fog at the edge of its view is turned off (it would show as a ring of sky). Every bit of GL state it touches is put
 * back. Client thread, inside the GPU plugin's GL context.
 */
@Slf4j
@Singleton
public class FarRenderer
{
	private static final float NEAR = 50;
	/** Ground detail: a grid every 1, 2, 4, 8 or 16 tiles out to these distances (tiles), then the coarsest. */
	private static final int[] TERRAIN_STEPS = {1, 2, 4, 8, 16};
	private static final double[] TERRAIN_RANGES = {40, 110, 260, 600};
	/** Without performance mode: one level, every tile, everywhere. */
	private static final double[] FULL_RANGE = {Double.MAX_VALUE};
	/**
	 * Scenery detail by level: exact, then no decorations snapped to a 24-unit grid, then only buildings and tall
	 * things snapped to 64 and 160 units; finer for squares holding big structures (one grid per square, so their
	 * pieces still meet).
	 */
	private static final ObjectMesher.Detail[] SCENERY_DETAIL = {ObjectMesher.Detail.FULL,
		new ObjectMesher.Detail(false, false, 24), new ObjectMesher.Detail(false, true, 64),
		new ObjectMesher.Detail(false, true, 160)};
	/** Tiles out to which scenery is exact. */
	private static final int FULL_DETAIL = 128;
	/** Tiles out to which buildings are only a little simplified. */
	private static final int BUILDINGS_FINE = 600;
	/** The texture unit the drawn-zone mask is bound to (one the GPU plugin doesn't use). */
	private static final int MASK_UNIT = 7;

	private final Client client;
	private final DrawnZones zones = new DrawnZones();
	private final ExtendedRenderDistanceConfig config;
	private final ConfigManager configManager;

	private volatile WorldCache world;
	private WorldCache builtFor;
	private int builtScenery = -1, builtLandmarks = -1;
	private boolean builtSimplify;
	/** The part of the map drawn, and the one being worked out for where the player is now. */
	private Reach reach, builtReach;
	private CompletableFuture<Reach> nextReach;
	/** The world the reach was worked out on, and the one the next is being worked out on. */
	private WorldCache reachFor, nextReachFor;
	/**
	 * The reach the current one was worked out again from, for more of the world, with the player still in it:
	 * only the squares where they differ are built again.
	 */
	private Reach refreshedFrom, nextRefreshes;
	private RegionLayer terrain, scenery;

	/** The GL context our objects belong to; when the GPU plugin restarts, it's a new one. */
	private GLCapabilities caps;
	private int program, uniProj, uniOffset, uniBrightness, uniViewEnd, uniDepthScale;
	private int uniZones, uniZonesOrigin, maskTexture;
	private int uniTextures, uniTexturesOn, uniTextureEnd, uniTick, uniTextureAnimations;
	private int uniTextureLightMode, uniSmoothBanding;
	/** The texture animations are set on the program. */
	private boolean animationsSet;
	private int tick;
	private int[] texColours;
	private int fogProgram = -1, uniUseFog = -1;
	private boolean failed;

	@Inject
	FarRenderer(Client client, ExtendedRenderDistanceConfig config, ConfigManager configManager)
	{
		this.configManager = configManager;
		this.client = client;
		this.config = config;
	}

	/** The world to draw (it can arrive any time). Any thread. */
	public void setWorld(WorldCache world)
	{
		this.world = world;
	}

	/**
	 * Draws the far world. The camera is at scene position (x, y, z) (the client's axes: y down), turned by pitch
	 * and yaw (radians), as the GPU plugin was given it.
	 */
	public void draw(float camX, float camY, float camZ, float pitch, float yaw)
	{
		WorldCache w = world;
		if (w == null || failed)
			return;
		GLCapabilities current = GL.getCapabilities();
		if (current != caps)
		{
			// a new context (first frame, or the GPU plugin restarted): the old one's objects are gone with it
			caps = current;
			terrain = scenery = null;
			builtFor = null;
			fogProgram = -1;
			try
			{
				initProgram();
			}
			catch (RuntimeException e)
			{
				log.error("Extended Render Distance: couldn't set up the far renderer", e);
				failed = true;
				return;
			}
		}
		if (texColours == null || texColours.length == 0)
			texColours = textureColours();
		WorldView wv = client.getTopLevelWorldView();
		if (wv.isInstance() || !updateReach(w))
			return;
		layers(w);

		int vpW = client.getViewportWidth(), vpH = client.getViewportHeight();
		if (vpW <= 0 || vpH <= 0)
			return;
		float scale = client.getScale();
		float[] proj = Mat4.scale(scale, scale, 1);
		Mat4.mul(proj, Mat4.projection(vpW, vpH, NEAR));
		Mat4.mul(proj, Mat4.rotateX(pitch));
		Mat4.mul(proj, Mat4.rotateY(yaw));

		int baseX = wv.getBaseX(), baseY = wv.getBaseY();

		// --- save the GPU plugin's state
		int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int prevBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
		boolean blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE), depth = glIsEnabled(GL_DEPTH_TEST);
		int depthFunc = glGetInteger(GL_DEPTH_FUNC);
		boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		int prevUnit = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
		int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
		int prevUnpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);

		try
		{
			glDisable(GL_BLEND);
			glDisable(GL_CULL_FACE);
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER);
			glDepthMask(true);
			glUseProgram(program);
			glUniformMatrix4fv(uniProj, false, proj);
			TextureProvider tp = client.getTextureProvider();
			glUniform1f(uniBrightness, tp == null ? 0.8f : (float) tp.getBrightness());
			// a clean edge at the terrain distance
			glUniform1f(uniViewEnd, config.terrainDistance() * 128f);
			// what the game drew itself last frame, zone by zone; zone (0, 0) starts 5 zones before the scene's corner
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			glBindTexture(GL_TEXTURE_2D, maskTexture);
			java.nio.ByteBuffer mask = zones.mask();
			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, DrawnZones.MASK, DrawnZones.MASK, GL_RED, GL_UNSIGNED_BYTE, mask);
			java.lang.ref.Reference.reachabilityFence(mask);
			glUniform1i(uniZones, MASK_UNIT);
			textures();
			float zone0 = -DrawnZones.OFFSET * 1024f;
			glUniform2f(uniZonesOrigin, zone0 - camX, zone0 - camZ);
			glUniform1f(uniDepthScale, 0.996f);

			double view = config.terrainDistance();
			// squares the game draws wholly aren't drawn at all
			java.util.function.Predicate<com.extendedrenderdistance.world.WorldTerrain.Region> hole = r -> covered(r, baseX, baseY);
			terrain.draw(proj, camX, camY, camZ, baseX, baseY, uniOffset, view, hole);
			// models' back faces are hidden, as the game does: a wall's two sides never fight over the same pixels
			// (simplifying far away can bring them together)
			glEnable(GL_CULL_FACE);
			scenery.draw(proj, camX, camY, camZ, baseX, baseY, uniOffset, view, hole);
		}
		finally
		{
			// --- put it all back
			glBindVertexArray(prevVao);
			glBindBuffer(GL_ARRAY_BUFFER, prevBuffer);
			glUseProgram(prevProgram);
			setEnabled(GL_BLEND, blend);
			setEnabled(GL_CULL_FACE, cull);
			setEnabled(GL_DEPTH_TEST, depth);
			glDepthFunc(depthFunc);
			glDepthMask(depthMask);
			glBindTexture(GL_TEXTURE_2D, prevTexture);
			glActiveTexture(prevUnit);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevUnpack);
		}

		if (prevProgram != 0)
		{
			// the GPU plugin's program is bound again and its uniforms are set for this frame: turn its edge fog off
			if (prevProgram != fogProgram)
			{
				fogProgram = prevProgram;
				uniUseFog = glGetUniformLocation(prevProgram, "useFog");
			}
			if (uniUseFog >= 0)
				glUniform1i(uniUseFog, 0);
		}
	}

	/** Whether every zone of a map square is drawn by the game itself. */
	private boolean covered(com.extendedrenderdistance.world.WorldTerrain.Region r, int baseX, int baseY)
	{
		for (int i = 0; i < 64; i += 8)
		{
			for (int j = 0; j < 64; j += 8)
			{
				if (!zones.isDrawnAt(r.baseX() + i, r.baseY() + j, baseX, baseY))
					return false;
			}
		}
		return true;
	}

	/** A zone the game drew this frame. */
	public void zoneDrawn(int zx, int zz)
	{
		zones.drawn(zx, zz);
	}

	/** The end of a frame. */
	public void endFrame()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
			zones.endFrame(wv.getBaseX(), wv.getBaseY());
	}

	private static void setEnabled(int cap, boolean on)
	{
		if (on)
			glEnable(cap);
		else
			glDisable(cap);
	}

	/**
	 * Keeps the reach on where the player is: when they step out of it (into a dungeon, through a teleport), or
	 * more of the world is read, the new one is worked out in the background. False while there's none for here
	 * yet (draw nothing meanwhile).
	 */
	private boolean updateReach(WorldCache w)
	{
		Player p = client.getLocalPlayer();
		if (p == null)
			return false;
		WorldPoint at = p.getWorldLocation();
		if (nextReach != null && nextReach.isDone())
		{
			reach = nextReach.getNow(null);
			reachFor = nextReachFor;
			refreshedFrom = nextRefreshes;
			nextReach = null;
		}
		boolean here = reach != null && reach.has(at.getX(), at.getY());
		if ((!here || reachFor != w) && nextReach == null)
		{
			int x = at.getX(), y = at.getY();
			nextReachFor = w;
			nextRefreshes = here ? reach : null;
			nextReach = CompletableFuture.supplyAsync(() -> Reach.from(w.terrain, x, y));
		}
		return here;
	}

	/**
	 * (Re)builds the layers when the world, the reach or the distances change. More of the same world, or its
	 * reach worked out again for that, only builds again the squares that changed; their old meshes are drawn
	 * meanwhile, so the view doesn't flicker as the world fills in.
	 */
	private void layers(WorldCache w)
	{
		int sc = config.sceneryDistance(), lm = Math.max(sc, config.structureDistance());
		boolean simplify = config.performanceMode();
		boolean distances = sc != builtScenery || lm != builtLandmarks;
		if (w == builtFor && reach == builtReach && !distances && simplify == builtSimplify && terrain != null)
			return;
		Reach rc = reach;
		Reach was = builtReach;
		boolean reachRefreshed = rc != was && was != null && refreshedFrom == was;
		boolean incremental = terrain != null && builtFor != null && (rc == was || reachRefreshed);
		int[] tex = texColours;
		// performance mode: coarser ground further away; without it, every tile everywhere
		RegionLayer.Builder ground = (r, level) -> TerrainMesher.build(w.terrain, r,
			simplify ? TERRAIN_STEPS[level] : 1, tex, rc);
		double[] groundRanges = simplify ? TERRAIN_RANGES : FULL_RANGE;
		ObjectMesher objects = new ObjectMesher(w, texColours);
		ObjectMesher.Detail[] detail = simplify ? SCENERY_DETAIL : unsimplified();
		RegionLayer.Builder things = (r, level) -> objects.build(r, detail[level], rc);

		if (!incremental)
		{
			if (terrain != null)
				terrain.free();
			terrain = new RegionLayer("terrain", groundRanges, simplify, 1, 24, 12);
			terrain.setFilter(r -> rc == null || rc.touches(r));
			terrain.setWorld(w.terrain, ground);
		}
		else
		{
			update(terrain, w, rc, was, ground);
			if (simplify != builtSimplify)
				terrain.setLevels(groundRanges, simplify, ground);
		}

		// everything exactly up close, then (performance mode) simplified out to the scenery distance, then buildings
		// and tall things (simplified more and more in performance mode) out to the building distance
		double[] ranges = {simplify ? Math.min(FULL_DETAIL, sc) : sc, sc, Math.max(sc, Math.min(BUILDINGS_FINE, lm)),
			lm};
		if (!incremental)
		{
			if (scenery != null)
				scenery.free();
			scenery = new RegionLayer("scenery", ranges, false, 3, 9, 4);
			scenery.setFilter(r -> rc == null || rc.touches(r));
			scenery.setWorld(w.terrain, things);
		}
		else
		{
			update(scenery, w, rc, was, things);
			// the distances only pick which level is drawn where: what's built stays right
			if (distances || simplify != builtSimplify)
				scenery.setRanges(ranges);
			if (simplify != builtSimplify)
				scenery.rebuild(r -> true, things);
		}
		builtFor = w;
		builtReach = rc;
		builtScenery = sc;
		builtLandmarks = lm;
		builtSimplify = simplify;
	}

	/** Moves a layer on to more of the world and the reach worked out again for it. */
	private void update(RegionLayer layer, WorldCache w, Reach rc, Reach was, RegionLayer.Builder builder)
	{
		if (w != builtFor)
			layer.update(w.terrain, builder);
		if (rc != was)
		{
			layer.rebuild(r -> rc.differsIn(was, r), builder);
			layer.setFilter(r -> rc.touches(r));
		}
	}

	/** Without performance mode: everything out to the scenery distance, then the landmarks; nothing snapped. */
	private static ObjectMesher.Detail[] unsimplified()
	{
		ObjectMesher.Detail[] out = new ObjectMesher.Detail[SCENERY_DETAIL.length];
		for (int i = 0; i < out.length; i++)
			out[i] = SCENERY_DETAIL[i].landmarksOnly ? new ObjectMesher.Detail(false, true, 0) : ObjectMesher.Detail.FULL;
		return out;
	}

	/**
	 * Points the program at the GPU plugin's textures (it keeps its texture array bound to unit 1), with its animations
	 * and tick; flat colours while it has none.
	 */
	private void textures()
	{
		glActiveTexture(GL_TEXTURE1);
		int array = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
		glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
		TextureProvider tp = client.getTextureProvider();
		if (!animationsSet && tp != null && tp.getTextures() != null && tp.getTextures().length > 0)
		{
			glUniform2fv(uniTextureAnimations, textureAnimations(tp.getTextures()));
			animationsSet = true;
		}
		// as the GPU plugin: textures stand still while loading
		if (client.getGameState() == GameState.LOGGED_IN)
			tick = client.getGameCycle() & 127;
		glUniform1i(uniTick, tick);
		glUniform1i(uniTextures, 1);
		glUniform1i(uniTexturesOn, array != 0 && animationsSet ? 1 : 0);
		glUniform1f(uniTextureEnd, config.textureDistance() * 128f);
		// the GPU plugin's own colour settings, so the far world matches its scene
		glUniform1f(uniTextureLightMode, gpuSetting("brightTextures", false) ? 1f : 0f);
		glUniform1f(uniSmoothBanding, gpuSetting("smoothBanding", true) ? 0f : 1f);
	}

	/** A GPU plugin setting, or its default when it hasn't been changed. */
	private boolean gpuSetting(String key, boolean byDefault)
	{
		Boolean value = configManager.getConfiguration("gpu", key, Boolean.class);
		return value == null ? byDefault : value;
	}

	/** Each texture's scroll per tick (texels), as the GPU plugin works it out (BSD-2). */
	private static float[] textureAnimations(Texture[] textures)
	{
		float[] out = new float[256 * 2];
		for (int i = 0; i < Math.min(256, textures.length); i++)
		{
			Texture t = textures[i];
			if (t == null)
				continue;
			float u = 0, v = 0;
			switch (t.getAnimationDirection())
			{
				case 1:
					v = -1;
					break;
				case 3:
					v = 1;
					break;
				case 2:
					u = -1;
					break;
				case 4:
					u = 1;
					break;
			}
			out[i * 2] = u * t.getAnimationSpeed();
			out[i * 2 + 1] = v * t.getAnimationSpeed();
		}
		return out;
	}

	private void initProgram()
	{
		animationsSet = false;
		program = Shaders.program(Shaders.FAR_VERT, Shaders.FAR_FRAG);
		uniProj = glGetUniformLocation(program, "proj");
		uniOffset = glGetUniformLocation(program, "offset");
		uniBrightness = glGetUniformLocation(program, "brightness");
		uniViewEnd = glGetUniformLocation(program, "viewEnd");
		uniZones = glGetUniformLocation(program, "drawnZones");
		uniZonesOrigin = glGetUniformLocation(program, "zonesOrigin");
		maskTexture = glGenTextures();
		int unit = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
		int prev = glGetInteger(GL_TEXTURE_BINDING_2D);
		glBindTexture(GL_TEXTURE_2D, maskTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, DrawnZones.MASK, DrawnZones.MASK, 0, GL_RED, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glBindTexture(GL_TEXTURE_2D, prev);
		glActiveTexture(unit);
		uniDepthScale = glGetUniformLocation(program, "depthScale");
		uniTextures = glGetUniformLocation(program, "textures");
		uniTexturesOn = glGetUniformLocation(program, "texturesOn");
		uniTextureEnd = glGetUniformLocation(program, "textureEnd");
		uniTick = glGetUniformLocation(program, "tick");
		uniTextureAnimations = glGetUniformLocation(program, "textureAnimations");
		uniTextureLightMode = glGetUniformLocation(program, "textureLightMode");
		uniSmoothBanding = glGetUniformLocation(program, "smoothBanding");
	}

	/** The client's colour for each texture (packed HSL): water, lava and so on. */
	private int[] textureColours()
	{
		TextureProvider tp = client.getTextureProvider();
		Texture[] textures = tp == null ? null : tp.getTextures();
		if (textures == null)
			return new int[0];
		int[] out = new int[textures.length];
		for (int i = 0; i < textures.length; i++)
			out[i] = tp.getDefaultColor(i) & 0xffff;
		return out;
	}

	/** Frees what it can (client thread, in the GPU plugin's context if that's still the one). */
	public void free()
	{
		if (caps != null && GL.getCapabilities() == caps)
		{
			if (terrain != null)
				terrain.free();
			if (scenery != null)
				scenery.free();
			if (program != 0)
				glDeleteProgram(program);
			if (maskTexture != 0)
				glDeleteTextures(maskTexture);
		}
		terrain = scenery = null;
		builtFor = null;
		caps = null;
		program = 0;
		maskTexture = 0;
		fogProgram = -1;
	}
}
