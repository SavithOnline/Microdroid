package com.example.screenshotmover.trigger;

import org.json.JSONObject;

/** Every trigger the app understands: id, UI fields, event matching and summary text. */
public enum TriggerType {
    TIME("time", "At a time", false,
            FieldSpec.text("at", "Time (HH:mm)"),
            FieldSpec.choice("days", "Days", "", "1,2,3,4,5", "6,7")),
    BOOT("boot", "On boot", false),
    POWER("power", "Power connected", false,
            FieldSpec.choice("action", "Action", "any", "connected", "disconnected")),
    BATTERY("battery", "Battery level", true,
            FieldSpec.number("level", "Level %"),
            FieldSpec.choice("direction", "Threshold", "below", "above"),
            FieldSpec.choice("charging", "Charging", "any", "yes", "no")),
    SCREEN("screen", "Screen on/off", true,
            FieldSpec.choice("action", "Action", "on", "off")),
    HEADSET("headset", "Headset", false,
            FieldSpec.choice("state", "State", "plugged", "unplugged")),
    NETWORK("network", "Network change", true,
            FieldSpec.choice("transport", "Transport", "any", "wifi", "mobile", "ethernet")),
    WIFI("wifi", "Wi-Fi network", true,
            FieldSpec.choice("action", "Action", "any", "connected", "disconnected"),
            FieldSpec.text("ssid", "SSID (blank = any)")),
    PACKAGE("package", "App installed/removed", false,
            FieldSpec.choice("action", "Action", "installed", "removed"),
            FieldSpec.text("package", "Package (blank = any)")),
    MEDIA("media", "Media button", false),
    BROADCAST("broadcast", "Broadcast intent", false,
            FieldSpec.text("action", "Intent action (blank = any)")),
    NOTIFICATION("notification", "Notification", false,
            FieldSpec.choice("action", "Action", "posted", "removed"),
            FieldSpec.text("package", "Package (blank = any)"),
            FieldSpec.text("contains", "Contains text (blank = any)")),
    SMS("sms", "SMS received", false,
            FieldSpec.text("from", "From (blank = any)"),
            FieldSpec.text("contains", "Contains text (blank = any)")),
    CALL("call", "Phone call", false,
            FieldSpec.choice("state", "State", "any", "incoming", "outgoing", "missed")),
    APP("app", "App opened/closed", true,
            FieldSpec.choice("action", "Action", "opened", "closed"),
            FieldSpec.text("package", "Package (blank = any)")),
    LOCATION("location", "Location enter/exit", false,
            FieldSpec.choice("action", "Action", "enter", "exit"),
            FieldSpec.number("lat", "Latitude"),
            FieldSpec.number("lng", "Longitude"),
            FieldSpec.number("radius_m", "Radius (m)")),
    SENSOR("sensor", "Motion gesture", true,
            FieldSpec.choice("gesture", "Gesture", "shake", "flip", "proximity")),
    UNLOCK("unlock", "Device unlocked", false),
    CALENDAR("calendar", "Calendar event", true,
            FieldSpec.choice("when", "When", "starts", "ends"),
            FieldSpec.text("keyword", "Title contains (blank = any)")),
    WEBHOOK("webhook", "Webhook (LAN)", true,
            FieldSpec.text("secret", "Secret (blank = none)")),
    HTTP_POLL("http_poll", "HTTP check", true,
            FieldSpec.text("url", "URL"),
            FieldSpec.number("interval_min", "Every (minutes)"),
            FieldSpec.text("contains", "Contains (blank = any change)")),
    SUN("sun", "Sunrise/sunset", false,
            FieldSpec.choice("event", "Event", "sunrise", "sunset"),
            FieldSpec.number("offset_min", "Offset minutes"));

    public final String id;
    public final String label;
    public final boolean needsService;
    public final FieldSpec[] fields;

    TriggerType(String id, String label, boolean needsService, FieldSpec... fields) {
        this.id = id;
        this.label = label;
        this.needsService = needsService;
        this.fields = fields;
    }

    public static TriggerType byId(String id) {
        for (TriggerType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    public String summary(JSONObject cfg) {
        StringBuilder sb = new StringBuilder(label);
        for (FieldSpec f : fields) {
            String v = cfg.optString(f.key, "").trim();
            if (v.isEmpty()) continue;
            sb.append(sb.indexOf(":") < 0 ? ": " : " · ");
            sb.append(v);
        }
        return sb.toString();
    }

    public boolean matches(JSONObject cfg, Event ev) {
        String et = ev.type();
        switch (this) {
            case TIME:
                return "time".equals(et) && eq(cfg.optString("at"), ev.get("at"))
                        && (cfg.optString("days", "").isEmpty() || eq(cfg.optString("days"), ev.get("days")));
            case BOOT:
                return "boot".equals(et);
            case POWER:
                return "power".equals(et) && anyOrEq(cfg, "action", "any", ev.get("action"));
            case BATTERY: {
                if (!"battery".equals(et)) return false;
                int level = ev.intValue("level", -1);
                if (level >= 0 && cfg.optString("level", "").trim().length() > 0) {
                    int target = (int) parse(cfg.optString("level"), 15);
                    String dir = cfg.optString("direction", "below");
                    if ("below".equals(dir) ? level > target : level < target) return false;
                }
                String ch = cfg.optString("charging", "any");
                if (!"any".equals(ch)) {
                    boolean want = "yes".equals(ch);
                    if (ev.boolValue("charging", false) != want) return false;
                }
                return true;
            }
            case SCREEN:
                return "screen".equals(et) && anyOrEq(cfg, "action", "any", ev.get("action"));
            case HEADSET:
                return "headset".equals(et) && anyOrEq(cfg, "state", "any", ev.get("state"));
            case NETWORK:
                return "network".equals(et) && anyOrEq(cfg, "transport", "any", ev.get("transport"));
            case WIFI: {
                if (!"wifi".equals(et)) return false;
                if (!anyOrEq(cfg, "action", "any", ev.get("action"))) return false;
                String ssid = cfg.optString("ssid", "").trim();
                return ssid.isEmpty() || ssid.equalsIgnoreCase(ev.str("ssid", ""));
            }
            case PACKAGE: {
                if (!"package".equals(et)) return false;
                if (!eq(cfg.optString("action", "installed"), ev.get("action"))) return false;
                String pkg = cfg.optString("package", "").trim();
                return pkg.isEmpty() || pkg.equals(ev.get("package"));
            }
            case MEDIA:
                return "media".equals(et);
            case BROADCAST: {
                if (!"broadcast".equals(et)) return false;
                String a = cfg.optString("action", "").trim();
                return a.isEmpty() || a.equals(ev.get("action"));
            }
            case NOTIFICATION: {
                if (!"notification".equals(et)) return false;
                if (!eq(cfg.optString("action", "posted"), ev.get("action"))) return false;
                String pkg = cfg.optString("package", "").trim();
                if (!pkg.isEmpty() && !pkg.equals(ev.get("package"))) return false;
                return contains(cfg, ev, "contains", ev.str("title", "") + " " + ev.str("text", ""));
            }
            case SMS: {
                if (!"sms".equals(et)) return false;
                String from = cfg.optString("from", "").trim();
                if (!from.isEmpty() && !from.equalsIgnoreCase(ev.str("from", ""))) return false;
                return contains(cfg, ev, "contains", ev.str("body", ""));
            }
            case CALL:
                return "call".equals(et) && anyOrEq(cfg, "state", "any", ev.get("state"));
            case APP: {
                if (!"app".equals(et)) return false;
                if (!eq(cfg.optString("action", "opened"), ev.get("action"))) return false;
                String pkg = cfg.optString("package", "").trim();
                return pkg.isEmpty() || pkg.equals(ev.get("package"));
            }
            case LOCATION:
                return "location".equals(et) && eq(cfg.optString("action", "enter"), ev.get("action"));
            case SENSOR:
                return "sensor".equals(et) && anyOrEq(cfg, "gesture", "any", ev.get("gesture"));
            case UNLOCK:
                return "unlock".equals(et);
            case CALENDAR: {
                if (!"calendar".equals(et)) return false;
                if (!eq(cfg.optString("when", "starts"), ev.get("when"))) return false;
                String kw = cfg.optString("keyword", "").trim();
                return kw.isEmpty() || ev.str("title", "").toLowerCase().contains(kw.toLowerCase());
            }
            case WEBHOOK: {
                if (!"webhook".equals(et)) return false;
                String secret = cfg.optString("secret", "").trim();
                return secret.isEmpty() || secret.equals(ev.str("secret", ""));
            }
            case HTTP_POLL: {
                if (!"http_poll".equals(et)) return false;
                String url = cfg.optString("url", "").trim();
                return url.isEmpty() || url.equals(ev.get("url"));
            }
            case SUN:
                return "sun".equals(et) && eq(cfg.optString("event", "sunrise"), ev.get("event"));
            default:
                return false;
        }
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    private static boolean anyOrEq(JSONObject cfg, String key, String def, String value) {
        String want = cfg.optString(key, def);
        return "any".equals(want) || want.equals(value);
    }

    private static boolean contains(JSONObject cfg, Event ev, String key, String haystack) {
        String needle = cfg.optString(key, "").trim();
        return needle.isEmpty() || haystack.toLowerCase().contains(needle.toLowerCase());
    }

    public static double parse(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
