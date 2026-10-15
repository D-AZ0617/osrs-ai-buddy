package com.osrsaibuddy;

import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import net.runelite.api.WorldType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountProgressTest
{
    private long account = 1;
    private boolean loggedIn = true;
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
        (proxy, method, args) -> method.getName().equals("getName") ? "Example" : null);
    private final ItemComposition item = (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(), new Class<?>[]{ItemComposition.class},
        (proxy, method, args) -> method.getName().equals("getName") ? "Observed item" : null);
    private final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
        (proxy, method, args) -> {
            switch (method.getName())
            {
                case "getGameState": return loggedIn ? GameState.LOGGED_IN : GameState.LOGIN_SCREEN;
                case "getLocalPlayer": return player;
                case "getAccountHash": return account;
                case "getWorldType": return EnumSet.noneOf(WorldType.class);
                case "getItemDefinition": return item;
                case "getVarpValue": return 100;
                case "getVarbitValue": return 0;
                default: return null;
            }
        });

    @Test public void unseenCollectionItemsRemainUnknownAndNoFalseCompleteClaim()
    {
        AccountProgress progress = new AccountProgress(client);
        progress.obtained(123); progress.obtained(123); progress.obtained(-1);
        JsonObject data = progress.read("collection_log");
        assertTrue(data.get("coverage").getAsString().contains("PARTIAL"));
        assertEquals(1, data.getAsJsonArray("entries").size());
        assertEquals(100, data.get("reportedObtained").getAsInt());
    }

    @Test public void collectionCannotCarryAcrossAccountsOrSharingReset()
    {
        AccountProgress progress = new AccountProgress(client); progress.obtained(123);
        account = 2;
        assertEquals(0, progress.read("collection_log").getAsJsonArray("entries").size());
        progress.obtained(456); assertEquals(1, progress.read("collection_log").getAsJsonArray("entries").size());
        progress.clear(); assertEquals(0, progress.read("collection_log").getAsJsonArray("entries").size());
    }

    @Test public void noLoggedInIdentityOnLoginScreen()
    { loggedIn = false; assertNull(new AccountProgress(client).identity()); }
}
