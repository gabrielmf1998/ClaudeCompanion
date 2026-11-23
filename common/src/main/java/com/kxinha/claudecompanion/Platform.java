package com.kxinha.claudecompanion;

import java.nio.file.Path;
import java.util.List;

/** The few things that differ between mod loaders. */
public interface Platform {
    record ModInfo(String id, String name, String version) {}

    /** Human readable loader name, for example Forge or Fabric. */
    String loaderName();

    /** Version of this mod as declared in the loader metadata. */
    String modVersion();

    Path configDir();

    List<ModInfo> mods();
}
