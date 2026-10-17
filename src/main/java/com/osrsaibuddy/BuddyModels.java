package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Conservative general-purpose small-model policy. Never invents account access. */
final class BuddyModels
{
    static final String NOTE = "Lightweight models only. Buddy prefers Luna, then mini. ChatGPT usage limits still apply. No flagship fallback.";

    static boolean allowed(String id)
    {
        return id != null && (id.matches("gpt-6(?:\\.\\d+)?-luna")
            || id.matches("gpt-5(?:\\.\\d+)?-mini"));
    }

    static List<String> choices(List<String> offered)
    {
        List<String> result = new ArrayList<>();
        if (offered != null)
        {
            for (String id : offered)
            {
                if (allowed(id) && !result.contains(id)) { result.add(id); }
            }
        }
        result.sort(Comparator.comparingInt((String id) -> id.contains("luna") ? 0 : 1)
            .thenComparing(Comparator.reverseOrder()));
        return result;
    }

    static String selected(String saved, List<String> offered)
    {
        List<String> choices = choices(offered);
        return choices.contains(saved) ? saved : choices.isEmpty() ? "" : choices.get(0);
    }
}
