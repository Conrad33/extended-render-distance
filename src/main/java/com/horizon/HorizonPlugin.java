package com.horizon;

import com.google.inject.Provides;
import com.horizon.gl.CallbacksWrapper;
import com.horizon.gl.FarRenderer;
import com.horizon.world.WorldData;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Horizon",
	description = "Draws the world past the normal render distance, out to the horizon, from the game cache. Needs the GPU plugin",
	tags = {"draw", "distance", "render", "view", "horizon", "far", "gpu"}
)
public class HorizonPlugin extends Plugin
{
	@Inject
	private ClientThread clientThread;
	@Inject
	private WorldData worldData;
	@Inject
	private FarRenderer farRenderer;
	@Inject
	private CallbacksWrapper wrapper;

	@Override
	protected void startUp()
	{
		// read the world from the game cache in the background
		worldData.whenLoaded(farRenderer::setWorld);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(wrapper::unwrap);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		wrapper.check();
	}

	@Provides
	HorizonConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HorizonConfig.class);
	}
}
