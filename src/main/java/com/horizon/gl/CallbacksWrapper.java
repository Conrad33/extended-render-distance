package com.horizon.gl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Slips the {@link FarRenderer} into the GPU plugin's frame. The client's draw callbacks are swapped for a proxy
 * that passes every call straight through to the GPU plugin, and after the GPU plugin's top-level
 * {@code preSceneDraw} (framebuffer bound and cleared, sky drawn, scene not yet drawn) draws the far world. Only the
 * GPU plugin is wrapped: other renderers (117 HD) keep their own pipeline. Client thread.
 */
@Slf4j
@Singleton
public class CallbacksWrapper
{
	private static final String GPU_PLUGIN = "net.runelite.client.plugins.gpu.GpuPlugin";

	private final Client client;
	private final FarRenderer far;
	private DrawCallbacks delegate;
	private DrawCallbacks proxy;
	private boolean drewThisFrame;
	private boolean warnedOther;

	@Inject
	CallbacksWrapper(Client client, FarRenderer far)
	{
		this.client = client;
		this.far = far;
	}

	/** Wraps whatever GPU plugin is drawing now, if it isn't already wrapped. Call every frame. */
	public void check()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null || current == proxy)
			return;
		if (!GPU_PLUGIN.equals(current.getClass().getName()))
		{
			if (!warnedOther)
			{
				log.info("Horizon: {} is drawing; the far world only works with the GPU plugin", current.getClass().getName());
				warnedOther = true;
			}
			return;
		}
		// the GPU plugin (re)started: wrap it
		delegate = current;
		proxy = (DrawCallbacks) Proxy.newProxyInstance(DrawCallbacks.class.getClassLoader(),
			new Class<?>[]{DrawCallbacks.class}, handler(current));
		client.setDrawCallbacks(proxy);
		log.debug("Horizon: wrapped the GPU plugin");
	}

	/** Puts the GPU plugin back as it was. */
	public void unwrap()
	{
		if (proxy != null && client.getDrawCallbacks() == proxy)
			client.setDrawCallbacks(delegate);
		proxy = null;
		delegate = null;
		far.free();
	}

	private InvocationHandler handler(DrawCallbacks target)
	{
		return (Object p, Method method, Object[] args) ->
		{
			Object result;
			try
			{
				result = method.invoke(target, args);
			}
			catch (InvocationTargetException e)
			{
				throw e.getCause();
			}
			String name = method.getName();
			if ("preSceneDraw".equals(name))
				afterPreSceneDraw(args);
			else if ("draw".equals(name) && args != null && args.length == 1)
				drewThisFrame = false;
			return result;
		};
	}

	private void afterPreSceneDraw(Object[] args)
	{
		Scene scene = (Scene) args[0];
		if (drewThisFrame || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		drewThisFrame = true;
		// (scene, [projection,] camera x, y, z, pitch, yaw, ...)
		int i = args[1] instanceof Projection ? 2 : 1;
		try
		{
			far.draw((float) args[i], (float) args[i + 1], (float) args[i + 2], (float) args[i + 3], (float) args[i + 4]);
		}
		catch (RuntimeException e)
		{
			log.error("Horizon: drawing the far world failed", e);
		}
	}
}
