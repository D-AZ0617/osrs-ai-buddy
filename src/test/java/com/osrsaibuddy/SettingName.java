package com.osrsaibuddy;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;

/**
 * A setting's name as RuneLite's settings panel shows it, read from its {@link ConfigItem} in {@link OsrsAiBuddyConfig}: what
 * OSRS AI Buddy's own words must call it, checked against what the player actually sees.
 */
final class SettingName
{
	private SettingName()
	{
	}

	/** The name shown for the setting saved as {@code keyName}: "Share bank, inventory & gear" for "shareItems". */
	static String of(String keyName)
	{
		for (Method m : OsrsAiBuddyConfig.class.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null && item.keyName().equals(keyName))
			{
				return item.name();
			}
		}
		throw new AssertionError("OSRS AI Buddy has no setting " + keyName);
	}
}

