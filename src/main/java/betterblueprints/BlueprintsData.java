package betterblueprints;

import arc.struct.Seq;

/** Persisted tile layout data, stored in Core.settings under {@link BlueprintsStore#SETTINGS_KEY}. */
public class BlueprintsData{
    public int version = 1;
    /** Whether the one-time migration from vanilla schematic tags has already run. */
    public boolean migrated;
    /** User-defined tiles, in display order. */
    public Seq<TileEntry> tiles = new Seq<>();
    /** Grid cells of the fixed "All" tile; -1 = auto (0,0). */
    public int allGx = -1, allGy = -1;
    /** Grid cells of the fixed "Uncategorized" tile; -1 = auto place. */
    public int uncatGx = -1, uncatGy = -1;
    /** Grid cells of the fixed "new tile" button; -1 = auto place. */
    public int newGx = -1, newGy = -1;

    public static class TileEntry{
        /** Stable id used to reference this tile. */
        public String id;
        /** Display name. */
        public String name = "";
        /** Width in grid cells (1..4). One cell = one vanilla blueprint card (200px). */
        public int w = 1;
        /** Height in grid cells (1..4). */
        public int h = 1;
        /** Grid cell position on the virtual tile grid; -1 = auto-place in list order (new/legacy tiles). */
        public int gx = -1, gy = -1;
        /** Legacy size tag ("small" | "wide" | "large"); null after one-time migration to {@link #w}/{@link #h}. */
        public String size;
        /** "auto" (first member schematic) | "schematic" (coverRef = schematic file name) | "image" (coverRef = image file name). */
        public String coverMode = "auto";
        /** Schematic .msch file name or cover image file name, depending on coverMode. */
        public String coverRef;
        /** Schematic .msch file names owned by this tile. A schematic may belong to at most one tile. */
        public Seq<String> members = new Seq<>();

        public int count(){
            return members == null ? 0 : members.size;
        }
    }
}
