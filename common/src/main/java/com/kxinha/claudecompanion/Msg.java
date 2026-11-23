package com.kxinha.claudecompanion;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class Msg {
    private static final TextColor CLAUDE = TextColor.fromRgb(0xD97757);

    private Msg() {}

    private static MutableComponent tag() {
        return Component.literal("[Claude] ").withStyle(Style.EMPTY.withColor(CLAUDE).withBold(true));
    }

    public static Component claude(String text) {
        return tag().append(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(false)));
    }

    public static Component info(String text) {
        return tag().append(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)));
    }

    public static Component error(String text) {
        return tag().append(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(false)));
    }

    public static Component tool(String text) {
        return Component.literal("  » " + text).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(true));
    }

    public static Component you(String text) {
        return Component.literal("You » ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    public static Component actionbar(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(CLAUDE));
    }

    /** Turns markdown from Claude into something readable in the Minecraft chat. */
    public static String clean(String line) {
        String s = line.replace("\r", "");
        s = s.replaceAll("^\\s{0,3}#{1,6}\\s+", "");
        s = s.replaceAll("^(\\s*)[-*+]\\s+", "$1• ");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        s = s.replace("—", ",").replace("–", ",");
        s = s.replace("§", "");
        return s;
    }
}
