package com.osrsaibuddy;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsAiBuddyPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsAiBuddyPlugin.class);
		RuneLite.main(args);
	}
}

