package com.sunny.glassesbridge;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Retains the SDK's secondary error instead of hiding it behind session-ended. */
public final class ConnectionFailure {
    private final LinkedHashMap<String, String> errors = new LinkedHashMap<>();

    public void add(String code, String description) {
        if (errors.size() < 8 || errors.containsKey(code)) errors.put(code, description);
    }

    public String details() {
        StringBuilder result = new StringBuilder();
        for (var entry : errors.entrySet()) {
            if (result.length() > 0) result.append("\n");
            result.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return result.toString();
    }

    public String recoveryMessage() {
        String details = details();
        String lower = details.toLowerCase(Locale.ROOT);
        String action;
        if (lower.contains("dat_app_on_the_glasses_update_required")) {
            action = "Meta could not start its developer app on the glasses. Tap Update glasses runtime. If no update is offered, use Reset Meta connection and check Developer Mode installation in Meta AI.";
        } else if (lower.contains("device_update_required")) {
            action = "Your glasses need a firmware update. Tap Update glasses firmware.";
        } else if (lower.contains("no_eligible_device") || lower.contains("device_unavailable")) {
            action = "Meta has not made the glasses available to this app. Keep them powered on, unfolded and worn. Tap Reset Meta connection to register again.";
        } else {
            action = "The glasses ended the camera connection. Tap Reset Meta connection. If it persists, use Copy connection details so the exact SDK error can be checked.";
        }
        return action + "\n\n" + details;
    }
}
