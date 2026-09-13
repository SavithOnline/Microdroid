package com.example.screenshotmover.automation;

import java.util.Collections;
import java.util.List;

/** Central registry. Add new automations to all(). */
public final class Automations {
    private Automations() {}

    public static List<Automation> all() {
        return Collections.emptyList();
    }

    public static Automation byId(String id) {
        if (id == null) return null;
        for (Automation a : all()) {
            if (a.id().equalsIgnoreCase(id)) return a;
        }
        return null;
    }
}
