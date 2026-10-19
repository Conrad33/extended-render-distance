package com.extendedrenderdistance;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExtendedRenderDistancePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ExtendedRenderDistancePlugin.class);
		RuneLite.main(args);
	}
}
