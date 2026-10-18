package com.sockspal.agent.memory;

/** Names a chat for the customer's chat list, from its first question and answer. */
@FunctionalInterface
public interface ChatTitler {

    int MAX_CHARS = 60;

    String title(String question, String answer);

    /** The title used until (or instead of) a generated one: the start of the first question. */
    static String fallback(String question) {
        return shorten(question);
    }

    /** One line, at most MAX_CHARS. */
    static String shorten(String text) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= MAX_CHARS ? line : line.substring(0, MAX_CHARS - 1).trim() + "…";
    }
}
