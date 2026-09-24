package br.com.suaempresa.sambamanager.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups share names by an explicit "parent - child" naming convention. */
public final class ShareHierarchy {
    private ShareHierarchy() { }

    public static Map<String, List<String>> childrenOf(List<String> shares) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (String share : shares) children.put(share, new ArrayList<>());
        for (String share : shares) {
            String parent = parentOf(share, shares);
            if (parent != null) children.get(parent).add(share);
        }
        return children;
    }

    public static String parentOf(String share, List<String> shares) {
        String parent = null;
        String normalized = share.toLowerCase(Locale.ROOT);
        for (String candidate : shares) {
            if (candidate.length() >= share.length()) continue;
            if (!normalized.startsWith(candidate.toLowerCase(Locale.ROOT) + " - ")) continue;
            if (parent == null || candidate.length() > parent.length()) parent = candidate;
        }
        return parent;
    }

    public static String childLabel(String share, String parent) {
        return parent == null ? share : share.substring(parent.length() + 3);
    }
}
