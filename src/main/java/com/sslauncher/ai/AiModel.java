package com.sslauncher.ai;

/** One selectable model returned by a provider's discovery endpoint. */
public record AiModel(String id, String label, int contextWindow) {
    public String display() {
        if (label == null || label.isBlank() || label.equals(id)) {
            return id;
        }
        return label + "  ·  " + id;
    }
}
