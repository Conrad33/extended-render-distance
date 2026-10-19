package com.horizon.gl;

import com.horizon.HorizonConfig;
import com.horizon.world.ObjectMesher;
import com.horizon.world.Reach;
import com.horizon.world.TerrainMesher;
import com.horizon.world.WorldCache;
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
import net.runelite.client.config.ConfigManager;
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
 * buffer, with its depth pushed back a little so the real scene always wins where both draw; inside the area the GPU
 * plugin draws (the loaded scene, out to its draw distance, less a margin) it draws nothing at all. Every bit of GL
 * state it touches is put back. Client thread, inside the GPU plugin's GL context.
 */
@Slf4j
@Singleton
public class FarRenderer
{
	private static final float NEAR = 50;
	/** Ground detail: a grid every 1, 2, 4, 8 or 16 tiles out to these distances (tiles), then the coarsest. */
	private static final int[] TERRAIN_STEPS = {1, 2, 4, 8, 16};
	private static final double[] TERRAIN_RANGES = {40, 110, 260, 600};
	/** Tiles inside the GPU plugin's edge where the far world stops (both draw in this band; the real scene wins). */
	private static final int EDGE_OVERLAP = 10;
	/** The GPU plugin's largest draw distance. */
	private static final int GPU_MAX_DISTANCE = 184;

	private final Client client;
	private final ConfigManager configManager;
	private final HorizonConfig config;

	private volatile WorldCache world;
	private WorldCache builtFor;
	private int builtScenery = -1, builtLandmarks = -1;
	/** The part of the map drawn, and the one being worked out for where the player is now. */
	private Reach reach, builtReach;
	private CompletableFuture<Reach> nextReach;
	private RegionLayer terrain, scenery;

	/** The GL context our objects belong to; when the GPU plugin restarts, it's a new one. */
	private GLCapabilities caps;
	private int program, uniProj, uniOffset, uniBrightness, uniFogColour, uniFogDistance, uniHole, uniDepthScale;
	private int[] texColours;
	private int fogProgram = -1, uniUseFog = -1;
	private boolean failed;

	@Inject
	FarRenderer(Client client, ConfigManager configManager, HorizonConfig config)
	{
		this.client = client;
		this.configManager = configManager;
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
				log.error("Horizon: couldn't set up the far renderer", e);
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

		// what the GPU plugin draws: the loaded scene, out to its draw distance from the camera
		int expanded = client.getExpandedMapLoading() * 8;
		Integer gpuDistance = configManager.getConfiguration("gpu", "drawDistance", Integer.class);
		int dd = Math.max(0, Math.min(GPU_MAX_DISTANCE, gpuDistance == null ? 25 : gpuDistance));
		float t = 128f;
		float minX = Math.max(-expanded * t, camX - dd * t), maxX = Math.min((104 + expanded) * t, camX + dd * t);
		float minZ = Math.max(-expanded * t, camZ - dd * t), maxZ = Math.min((104 + expanded) * t, camZ + dd * t);
		float m = EDGE_OVERLAP * t;

		// --- save the GPU plugin's state
		int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int prevBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
		boolean blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE), depth = glIsEnabled(GL_DEPTH_TEST);
		int depthFunc = glGetInteger(GL_DEPTH_FUNC);
		boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);

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
			// the hole, camera-relative
			glUniform4f(uniHole, minX + m - camX, minZ + m - camZ, maxX - m - camX, maxZ - m - camZ);
			glUniform1f(uniDepthScale, 0.996f);

			int baseX = wv.getBaseX(), baseY = wv.getBaseY();
			double view = config.viewDistance();
			terrain.draw(proj, camX, camY, camZ, baseX, baseY, uniOffset, view);
			scenery.draw(proj, camX, camY, camZ, baseX, baseY, uniOffset, view);
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
		}

		if (config.hideEdgeFog() && prevProgram != 0)
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
		if (w == builtFor && reach == builtReach && sc == builtScenery && lm == builtLandmarks && terrain != null)
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
		scenery = new RegionLayer("scenery", new double[]{Math.max(1, sc), Math.max(2, lm)}, false, 3, 9, 4);
		scenery.setFilter(r -> rc == null || rc.touches(r));
		scenery.setWorld(w.terrain, (r, level) -> objects.build(r, level == 1, rc));
		builtFor = w;
		builtReach = rc;
		builtScenery = sc;
		builtLandmarks = lm;
	}

	private void initProgram()
	{
		program = Shaders.program(Shaders.FAR_VERT, Shaders.FAR_FRAG);
		uniProj = glGetUniformLocation(program, "proj");
		uniOffset = glGetUniformLocation(program, "offset");
		uniBrightness = glGetUniformLocation(program, "brightness");
		uniFogColour = glGetUniformLocation(program, "fogColour");
		uniFogDistance = glGetUniformLocation(program, "fogDistance");
		uniHole = glGetUniformLocation(program, "hole");
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
		}
		terrain = scenery = null;
		builtFor = null;
		caps = null;
		program = 0;
		fogProgram = -1;
	}
}
