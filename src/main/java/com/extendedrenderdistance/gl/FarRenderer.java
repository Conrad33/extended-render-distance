package com.extendedrenderdistance.gl;

import com.extendedrenderdistance.ExtendedRenderDistanceConfig;
import com.extendedrenderdistance.Simplification;
import com.extendedrenderdistance.world.ObjectMesher;
import com.extendedrenderdistance.world.Reach;
import com.extendedrenderdistance.world.TerrainMesher;
import com.extendedrenderdistance.world.WorldCache;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws the world beyond the normal view into the GPU plugin's frame, from the game cache, only ever the part that
 * belongs with where the player is (the {@link Reach}: in a dungeon, that dungeon; in an instance, nothing): the ground out to the
 * view distance (levels of detail, hazed into the sky), all scenery out to the scenery distance and buildings and
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
	/**
	 * Scenery detail by level: exact, then no decorations, then only buildings and tall things; snapped (unless
	 * simplification is off) to these grids (local units).
	 */
	private static final int[] SCENERY_CELLS = {0, 24, 64, 160};
	/** Tiles out to which scenery is exact. */
	private static final int FULL_DETAIL = 128;
	/** Tiles out to which buildings are only a little simplified. */
	private static final int BUILDINGS_FINE = 600;
	/** The texture unit the drawn-zone mask is bound to (one the GPU plugin doesn't use). */
	private static final int MASK_UNIT = 7;

	private final Client client;
	private final DrawnZones zones = new DrawnZones();
	private final ExtendedRenderDistanceConfig config;

	private volatile WorldCache world;
	private WorldCache builtFor;
	private int builtScenery = -1, builtLandmarks = -1;
	private Simplification builtSimplification;
	/** The part of the map drawn, and the one being worked out for where the player is now. */
	private Reach reach, builtReach;
	private CompletableFuture<Reach> nextReach;
	private RegionLayer terrain, scenery;

	/** The GL context our objects belong to; when the GPU plugin restarts, it's a new one. */
	private GLCapabilities caps;
	private int program, uniProj, uniOffset, uniBrightness, uniFogColour, uniFogDistance, uniDepthScale;
	private int uniZones, uniZonesOrigin, maskTexture;
	private int[] texColours;
	private int fogProgram = -1, uniUseFog = -1;
	private boolean failed;

	@Inject
	FarRenderer(Client client, ExtendedRenderDistanceConfig config)
	{
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
			int sky = client.getSkyboxColor();
			glUniform3f(uniFogColour, (sky >> 16 & 0xff) / 255f, (sky >> 8 & 0xff) / 255f, (sky & 0xff) / 255f);
			glUniform1f(uniFogDistance, config.viewDistance() * 128f * 0.55f);
			// what the game drew itself last frame, zone by zone; zone (0, 0) starts 5 zones before the scene's corner
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			glBindTexture(GL_TEXTURE_2D, maskTexture);
			java.nio.ByteBuffer mask = zones.mask();
			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, DrawnZones.MASK, DrawnZones.MASK, GL_RED, GL_UNSIGNED_BYTE, mask);
			java.lang.ref.Reference.reachabilityFence(mask);
			glUniform1i(uniZones, MASK_UNIT);
			float zone0 = -DrawnZones.OFFSET * 1024f;
			glUniform2f(uniZonesOrigin, zone0 - camX, zone0 - camZ);
			glUniform1f(uniDepthScale, 0.996f);

			double view = config.viewDistance();
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
	 * Keeps the reach on where the player is: when they step out of it (into a dungeon, through a teleport), the
	 * new one is worked out in the background. False while there's none for here yet (draw nothing meanwhile).
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
			nextReach = null;
		}
		boolean here = reach != null && reach.has(at.getX(), at.getY());
		if (!here && nextReach == null)
		{
			int x = at.getX(), y = at.getY();
			nextReach = CompletableFuture.supplyAsync(() -> Reach.from(w.terrain, x, y));
		}
		return here;
	}

	/** (Re)builds the layers when the world, the reach or the distances change. */
	private void layers(WorldCache w)
	{
		int sc = config.sceneryDistance(), lm = Math.max(sc, config.landmarkDistance());
		Simplification simplify = config.simplification();
		if (w == builtFor && reach == builtReach && sc == builtScenery && lm == builtLandmarks
			&& simplify == builtSimplification && terrain != null)
			return;
		Reach rc = reach;
		if (terrain == null || w != builtFor || rc != builtReach)
		{
			if (terrain != null)
				terrain.free();
			int[] tex = texColours;
			terrain = new RegionLayer("terrain", TERRAIN_RANGES, true, 1, 24, 12);
			terrain.setFilter(r -> rc == null || rc.touches(r));
			terrain.setWorld(w.terrain, (r, level) -> TerrainMesher.build(w.terrain, r, TERRAIN_STEPS[level], tex, rc));
		}
		if (scenery != null)
			scenery.free();
		ObjectMesher objects = new ObjectMesher(w, texColours);
		// everything exactly up close, simplified out to the scenery distance, then buildings and tall things
		// simplified more and more out to the building distance
		double[] ranges = {Math.min(FULL_DETAIL, sc), sc, Math.max(sc, Math.min(BUILDINGS_FINE, lm)), lm};
		scenery = new RegionLayer("scenery", ranges, false, 3, 9, 4);
		scenery.setFilter(r -> rc == null || rc.touches(r));
		ObjectMesher.Detail[] detail = new ObjectMesher.Detail[SCENERY_CELLS.length];
		for (int i = 0; i < detail.length; i++)
		{
			detail[i] = new ObjectMesher.Detail(i == 0, i >= 2, simplify == Simplification.NONE ? 0 : SCENERY_CELLS[i],
				simplify == Simplification.SIZE_AWARE);
		}
		scenery.setWorld(w.terrain, (r, level) -> objects.build(r, detail[level], rc));
		builtFor = w;
		builtReach = rc;
		builtScenery = sc;
		builtLandmarks = lm;
		builtSimplification = simplify;
	}

	private void initProgram()
	{
		program = Shaders.program(Shaders.FAR_VERT, Shaders.FAR_FRAG);
		uniProj = glGetUniformLocation(program, "proj");
		uniOffset = glGetUniformLocation(program, "offset");
		uniBrightness = glGetUniformLocation(program, "brightness");
		uniFogColour = glGetUniformLocation(program, "fogColour");
		uniFogDistance = glGetUniformLocation(program, "fogDistance");
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
