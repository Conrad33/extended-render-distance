package com.extendedrenderdistance;

import com.google.inject.Provides;
import com.extendedrenderdistance.gl.CallbacksWrapper;
import com.extendedrenderdistance.gl.FarRenderer;
import com.extendedrenderdistance.world.WorldData;
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
	name = "Extended Render Distance",
	description = "Render all of Gielinor at once. (requires GPU plugin)",
	tags = {"draw", "distance", "render", "view", "horizon", "far", "extended", "gpu"}
)
public class ExtendedRenderDistancePlugin extends Plugin
{
	@Inject
	private ClientThread clientThread;
	@Inject
	private WorldData worldData;
	@Inject
	private FarRenderer farRenderer;
	@Inject
	private CallbacksWrapper wrapper;
	@Inject
	private SpinnerClamp spinnerClamp;
	@Inject
	private ClientCache clientCache;

	@Override
	protected void startUp()
	{
		// read the world from the client's game cache in the background
		clientCache.open();
		worldData.whenLoaded(clientCache, farRenderer::setWorld);
		javax.swing.SwingUtilities.invokeLater(spinnerClamp::install);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(wrapper::unwrap);
		clientCache.close();
		javax.swing.SwingUtilities.invokeLater(spinnerClamp::uninstall);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		clientCache.service();
		wrapper.check();
	}

	@Provides
	ExtendedRenderDistanceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExtendedRenderDistanceConfig.class);
	}
}
