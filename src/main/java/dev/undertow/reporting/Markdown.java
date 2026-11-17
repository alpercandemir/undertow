package dev.undertow.reporting;

/** Escape owner-supplied text in contract reports. */
public final class Markdown {
    private Markdown() {}

    public static String safe(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("@", "&#64;")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace("`", "&#96;")
                .replace("*", "&#42;")
                .replace("_", "&#95;")
                .replace("\\", "&#92;")
                .replace("\r", "")
                .replace("\n", " ");
    }
}
