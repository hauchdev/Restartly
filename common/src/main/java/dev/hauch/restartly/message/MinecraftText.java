package dev.hauch.restartly.message;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MiniMessage-style markup into vanilla {@link Component} trees
 * without pulling in the adventure library:
 *
 * <pre>
 *   "&lt;red&gt;Server restarting in &lt;white&gt;{time}"
 *   "&lt;dark_gray&gt;[&lt;red&gt;Restartly&lt;dark_gray&gt;] &lt;green&gt;ok"
 *   "&lt;#ff8800&gt;hex color &lt;bold&gt;bold&lt;/bold&gt;"
 * </pre>
 *
 * <p>Supported tags: all {@link ChatFormatting} names (with {@code _}
 * separators, e.g. {@code dark_gray}), the shorthands {@code b/i/u/st/obf},
 * {@code reset} and hex colors ({@code <#RRGGBB>}). Unknown tags are left
 * literally so admins see typos instead of silently missing styling. The
 * parser is intentionally small: it handles colors and modifiers, which
 * covers every message Restartly renders.</p>
 */
public final class MinecraftText {

    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z0-9_#]+)>");

    private static final Map<String, ChatFormatting> FORMATTINGS = new HashMap<>();

    static {
        for (ChatFormatting formatting : ChatFormatting.values()) {
            FORMATTINGS.put(formatting.getName().toLowerCase(Locale.ROOT), formatting);
        }
        FORMATTINGS.put("b", ChatFormatting.BOLD);
        FORMATTINGS.put("i", ChatFormatting.ITALIC);
        FORMATTINGS.put("u", ChatFormatting.UNDERLINE);
        FORMATTINGS.put("st", ChatFormatting.STRIKETHROUGH);
        FORMATTINGS.put("obf", ChatFormatting.OBFUSCATED);
        FORMATTINGS.put("grey", ChatFormatting.GRAY);
        FORMATTINGS.put("dark_grey", ChatFormatting.DARK_GRAY);
        FORMATTINGS.put("light_grey", ChatFormatting.GRAY);
    }

    private MinecraftText() {
    }

    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        // Empty placeholders would otherwise produce stray tags.
        MutableComponent root = Component.empty();
        MutableComponent current = root;
        Deque<Style> stack = new ArrayDeque<>();
        int lastEnd = 0;

        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                current.append(Component.literal(
                        text.substring(lastEnd, matcher.start())));
            }
            boolean closing = matcher.group(1).equals("/");
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            if (closing) {
                applyClosing(stack, name, current);
            } else {
                Style style = styleFor(name);
                if (style != null) {
                    stack.push(style);
                    current = leaf(current, stack);
                } else {
                    // Unknown tag: leave it visible so admins notice.
                    current.append(Component.literal(matcher.group(0))).withStyle(merge(stack));
                }
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            current.append(Component.literal(text.substring(lastEnd))).withStyle(merge(stack));
        } else if (stack.isEmpty() && current == root && lastEnd == 0) {
            return Component.empty();
        }
        return root;
    }

    private static MutableComponent leaf(MutableComponent parent, Deque<Style> stack) {
        MutableComponent leaf = Component.empty();
        parent.append(leaf);
        leaf.setStyle(merge(stack));
        return leaf;
    }

    private static void applyClosing(Deque<Style> stack, String name, MutableComponent current) {
        if (name.equals("reset") || name.isEmpty()) {
            stack.clear();
            return;
        }
        Style expected = styleFor(name);
        if (expected == null) {
            return;
        }
        // Pop until the matching tag (allows imperfect nesting).
        while (!stack.isEmpty()) {
            Style top = stack.pop();
            if (top.equals(expected)) {
                break;
            }
        }
        current.setStyle(merge(stack));
    }

    private static Style styleFor(String name) {
        if (name.startsWith("#")) {
            try {
                long rgb = Long.parseLong(name.substring(1), 16);
                return Style.EMPTY.withColor(TextColor.fromRgb((int) rgb));
            } catch (Exception e) {
                return null;
            }
        }
        return FORMATTINGS.get(name) == null ? null
                : Style.EMPTY.withColor(FORMATTINGS.get(name));
    }

    private static Style merge(Deque<Style> stack) {
        Style merged = Style.EMPTY;
        for (Style style : stack) {
            if (style.getColor() != null) {
                merged = merged.withColor(style.getColor());
            }
            if (style.isBold()) {
                merged = merged.withBold(true);
            }
            if (style.isItalic()) {
                merged = merged.withItalic(true);
            }
            if (style.isUnderlined()) {
                merged = merged.withUnderlined(true);
            }
            if (style.isStrikethrough()) {
                merged = merged.withStrikethrough(true);
            }
            if (style.isObfuscated()) {
                merged = merged.withObfuscated(true);
            }
        }
        return merged;
    }
}