package com.example.screenshotmover.trigger;

import org.json.JSONObject;

/** Constraints evaluated when a trigger fires; all must pass for the script to run. */
public enum ConstraintType {
    TIME_WINDOW("time_window", "Time window",
            FieldSpec.text("from", "From (HH:mm)"),
            FieldSpec.text("to", "To (HH:mm)"),
            FieldSpec.choice("days", "Days", "", "1,2,3,4,5", "6,7")),
    BATTERY_MIN("battery_min", "Battery at least %",
            FieldSpec.number("min", "Minimum %")),
    CHARGING("charging", "Charging",
            FieldSpec.choice("state", "State", "yes", "no")),
    SCREEN("screen", "Screen",
            FieldSpec.choice("state", "State", "on", "off")),
    WIFI_SSID("wifi_ssid", "Wi-Fi SSID",
            FieldSpec.text("ssid", "SSID")),
    NETWORK("network", "Network type",
            FieldSpec.choice("transport", "Transport", "any", "wifi", "mobile")),
    FOREGROUND_APP("foreground_app", "App in foreground",
            FieldSpec.text("package", "Package")),
    HEADPHONES("headphones", "Headphones",
            FieldSpec.choice("state", "State", "yes", "no")),
    DND("dnd", "Do Not Disturb",
            FieldSpec.choice("state", "State", "on", "off")),
    LOCATION("location", "Near location",
            FieldSpec.number("lat", "Latitude"),
            FieldSpec.number("lng", "Longitude"),
            FieldSpec.number("radius_m", "Radius (m)"));

    public final String id;
    public final String label;
    public final FieldSpec[] fields;

    ConstraintType(String id, String label, FieldSpec... fields) {
        this.id = id;
        this.label = label;
        this.fields = fields;
    }

    public static ConstraintType byId(String id) {
        for (ConstraintType c : values()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public String summary(JSONObject cfg) {
        StringBuilder sb = new StringBuilder(label);
        for (FieldSpec f : fields) {
            String v = cfg.optString(f.key, "").trim();
            if (v.isEmpty()) continue;
            sb.append(": ").append(v);
        }
        return sb.toString();
    }
}
