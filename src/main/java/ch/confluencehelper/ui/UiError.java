package ch.confluencehelper.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** A failure with a ready HTTP status and error {@code kind} (see docs/UI-SPEC.md), plus optional extra fields. */
final class UiError extends RuntimeException {

    final int status;
    final String kind;
    final Map<String, Object> extra = new LinkedHashMap<>();

    UiError(int status, String kind, String message) {
        super(message);
        this.status = status;
        this.kind = kind;
    }

    static UiError input(String message) {
        return new UiError(400, "input", message);
    }

    /** Adds a field to the JSON error body; null values are left out. */
    UiError with(String key, Object value) {
        if (value != null) {
            extra.put(key, value);
        }
        return this;
    }
}
