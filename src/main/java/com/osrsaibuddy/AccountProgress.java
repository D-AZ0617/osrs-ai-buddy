package com.osrsaibuddy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** Read-only account progress. All methods run on the client thread; nothing is written to disk. */
final class AccountProgress implements AccountContextTools.Source
{
    private final Client client;
    private final Map<Integer, Integer> collection = new LinkedHashMap<>();
    private String owner;

    AccountProgress(Client client) { this.client = client; }

    @Override public String identity()
    {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
            || client.getLocalPlayer().getName() == null) { return null; }
        return client.getAccountHash() + ":" + client.getWorldType() + ":" + client.getLocalPlayer().getName();
    }

    @Override public String username() { return client.getLocalPlayer().getName(); }

    void clear() { owner = null; collection.clear(); }

    // Script 4100 reports an obtained collection item. Also used by WikiSync; no script is invoked here.
    void obtained(int itemId)
    {
        String current = identity();
        if (current == null || itemId <= 0) { return; }
        if (!current.equals(owner)) { clear(); owner = current; }
        collection.putIfAbsent(itemId, 1);
    }

    void observe(ItemContainerChanged event)
    {
        String current = identity();
        if (current == null) { clear(); return; }
        if (!current.equals(owner)) { clear(); owner = current; }
        if (event.getContainerId() != InventoryID.COLLECTION_TRANSMIT) { return; }
        for (Item item : event.getItemContainer().getItems())
        {
            if (item.getId() > 0 && item.getQuantity() > 0) { collection.put(item.getId(), item.getQuantity()); }
        }
    }

    @Override public JsonObject read(String section)
    {
        JsonObject out = new JsonObject();
        out.addProperty("source", "RuneLite current logged-in account");
        out.addProperty("capturedAt", Instant.now().toString());
        out.addProperty("section", section);
        JsonArray entries = new JsonArray();
        switch (section)
        {
            case "overview":
                out.addProperty("username", username());
                out.addProperty("accountType", CharacterInfo.accountType(client.getVarbitValue(VarbitID.IRONMAN)));
                out.addProperty("combatLevel", client.getLocalPlayer().getCombatLevel());
                out.addProperty("totalLevel", client.getTotalLevel());
                out.addProperty("questPoints", client.getVarpValue(VarPlayerID.QP));
                out.addProperty("combatAchievementPoints", client.getVarbitValue(VarbitID.CA_POINTS));
                out.addProperty("collectionObtained", client.getVarpValue(VarPlayerID.COLLECTION_COUNT));
                out.addProperty("collectionTotal", client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX));
                out.addProperty("availableSections", "overview, skills, quests, combat_achievements, collection_log, collection_sections, unlocks, currencies");
                break;
            case "skills":
                for (Skill skill : Skill.values())
                {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", skill.getName());
                    row.addProperty("level", client.getRealSkillLevel(skill));
                    row.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
                    row.addProperty("xp", client.getSkillExperience(skill));
                    entries.add(row);
                }
                break;
            case "quests":
                for (Quest quest : Quest.values())
                {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", quest.getName());
                    row.addProperty("state", quest.getState(client).name());
                    entries.add(row);
                }
                break;
            case "collection_log":
                out.addProperty("coverage", "PARTIAL: only obtained items passively transmitted by the game while sharing was enabled. Unseen items are UNKNOWN, not missing. Open the collection log to supply more data. Counts can be unavailable before the game initializes them.");
                out.addProperty("reportedObtained", client.getVarpValue(VarPlayerID.COLLECTION_COUNT));
                out.addProperty("reportedTotal", client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX));
                if (identity().equals(owner))
                {
                    collection.forEach((id, qty) -> {
                        JsonObject row = new JsonObject();
                        row.addProperty("itemId", id);
                        row.addProperty("name", client.getItemDefinition(id).getName());
                        row.addProperty("minimumObservedQuantity", qty);
                        entries.add(row);
                    });
                }
                break;
            case "combat_achievements":
            case "collection_sections":
            case "unlocks":
            case "currencies":
                out.addProperty("coverage", "Known RuneLite 1.13.2 progress variables; internal identifiers, not official task titles. Newly added or unexposed progress may be unavailable. These are not the entire account.");
                for (AccountFlags.Flag flag : AccountFlags.forSection(section))
                {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", flag.name);
                    row.addProperty("value", flag.varp ? client.getVarpValue(flag.id) : client.getVarbitValue(flag.id));
                    entries.add(row);
                }
                break;
            default: throw new IllegalArgumentException("Unknown account section");
        }
        out.add("entries", entries);
        return out;
    }
}
