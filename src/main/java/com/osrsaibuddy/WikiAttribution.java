package com.osrsaibuddy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import okhttp3.HttpUrl;

/** Credits are generated from actual retrievals, never guessed by the model. */
final class WikiAttribution
{
    static final String LICENSE = "https://creativecommons.org/licenses/by-nc-sa/3.0/";
    static final String HEADING = "Wiki references retrieved in this conversation";

    static List<String> valid(Collection<String> urls)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (urls != null) for (String value : urls)
        {
            HttpUrl url = value == null ? null : HttpUrl.parse(value);
            if (url != null && url.isHttps() && url.host().equals("oldschool.runescape.wiki")
                && url.port() == 443 && url.username().isEmpty() && url.password().isEmpty()
                && url.encodedPath().startsWith("/w/") && url.encodedPath().length() > 3)
            { result.add(url.newBuilder().fragment(null).query(null).build().toString()); }
        }
        return new ArrayList<>(result);
    }

    static String notice(Collection<String> urls)
    {
        List<String> pages = valid(urls);
        if (pages.isEmpty()) { return ""; }
        StringBuilder out = new StringBuilder("\n\n---\n**").append(HEADING).append("**\n");
        for (String page : pages)
        {
            HttpUrl url = HttpUrl.get(page);
            String title = String.join("/", url.pathSegments().subList(1, url.pathSize())).replace('_', ' ')
                .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace('\n', ' ').replace('\r', ' ');
            out.append("\n- [").append(title).append("](").append(page).append(") · [Contributors](")
                .append(url.newBuilder().addQueryParameter("action", "history").build()).append(")");
        }
        return out.append("\n\nWiki-derived portions are adapted/summarized from these contributors' work under [CC BY-NC-SA 3.0](")
            .append(LICENSE).append("). This license applies to Wiki-derived material, not your private account data or the plugin code. "
                + "Retrieved references do not verify every claim in an AI answer. No Wiki endorsement is implied.").toString();
    }

    static ChatApi.ToolResult result(String text, Collection<String> urls)
    {
        ChatApi.ToolResult result = ChatApi.ToolResult.ok(text);
        result.wikiSources = valid(urls);
        return result;
    }
}
