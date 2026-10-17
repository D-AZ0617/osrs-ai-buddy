package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** General-purpose account model choices, with a lightweight default. */
final class BuddyModels
{
    static final String NOTE = "Luna is recommended for everyday questions. Flagship models are optional and can use more of your ChatGPT allowance. Plan limits still apply.";

    static boolean allowed(String id)
    {
        return id != null && (id.matches("gpt-6(?:\\.\\d+)?-(?:luna|sol|astra)")
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
        result.sort(Comparator.comparingInt((String id) -> id.contains("luna") ? 0 : id.contains("mini") ? 1 : 2)
            .thenComparing(Comparator.reverseOrder()));
        return result;
    }

    static String selected(String saved, List<String> offered)
    {
        List<String> choices = choices(offered);
        if (choices.contains(saved)) { return saved; }
        return choices.stream().filter(id -> id.contains("luna") || id.contains("mini")).findFirst().orElse("");
    }

    static String label(String id)
    {
        return id != null && id.matches("gpt-6(?:\\.\\d+)?-luna") ? id + " (Recommended)" : id;
    }
}
