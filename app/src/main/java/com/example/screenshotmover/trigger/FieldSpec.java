package com.example.screenshotmover.trigger;

/** One configurable field of a trigger or constraint, used by the generic form UI. */
public final class FieldSpec {
    public static final int TEXT = 0;
    public static final int NUMBER = 1;
    public static final int CHOICE = 2;

    public final String key;
    public final String label;
    public final int kind;
    public final String[] choices;

    public FieldSpec(String key, String label, int kind, String... choices) {
        this.key = key;
        this.label = label;
        this.kind = kind;
        this.choices = choices;
    }

    public static FieldSpec text(String key, String label) {
        return new FieldSpec(key, label, TEXT);
    }

    public static FieldSpec number(String key, String label) {
        return new FieldSpec(key, label, NUMBER);
    }

    public static FieldSpec choice(String key, String label, String... choices) {
        return new FieldSpec(key, label, CHOICE, choices);
    }
}
