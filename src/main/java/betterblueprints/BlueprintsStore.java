package betterblueprints;

import arc.Core;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import betterblueprints.BlueprintsData.TileEntry;
import mindustry.game.Schematic;

import static mindustry.Vars.schematics;

/**
 * Loads, persists and mutates the tile layout ({@link BlueprintsData}).
 *
 * <p>Schematics are referenced by their {@code .msch} file name, which is stable across in-game
 * renames. Members whose schematic no longer exists are pruned on load.</p>
 */
public class BlueprintsStore{
    public static final String SETTINGS_KEY = "better-blueprints-data";
    public static final String VANILLA_TAGS_KEY = "schematic-tags";

    public BlueprintsData data = new BlueprintsData();

    private boolean loaded;
    private boolean justMigrated;

    /** Loads data once. Also prunes missing members and runs the one-time tag migration. */
    public void ensureLoaded(){
        if(loaded) return;
        loaded = true;

        data = Core.settings.getJson(SETTINGS_KEY, BlueprintsData.class, BlueprintsData::new);
        if(data == null) data = new BlueprintsData();
        if(data.tiles == null) data.tiles = new Seq<>();

        pruneMissing();
        migrateTags();
        migrateSizes();
    }

    /** Whether the last {@link #ensureLoaded()} performed a migration; used for a one-time toast. */
    public boolean consumeMigratedFlag(){
        boolean out = justMigrated;
        justMigrated = false;
        return out;
    }

    public void save(){
        Core.settings.putJson(SETTINGS_KEY, BlueprintsData.class, data);
    }

    /** Creates a tile with a fresh id. */
    public TileEntry newTile(String name){
        TileEntry tile = new TileEntry();
        tile.id = String.valueOf(System.currentTimeMillis()) + "-" + Math.abs(java.util.UUID.randomUUID().hashCode());
        tile.name = name;
        tile.w = 1;
        tile.h = 1;
        tile.size = null;
        data.tiles.add(tile);
        save();
        return tile;
    }

    public void deleteTile(TileEntry tile){
        data.tiles.remove(tile);
        save();
    }

    /** Moves a schematic to a tile (or to uncategorized when tile is null), enforcing exclusivity. */
    public void moveTo(TileEntry tile, Schematic s){
        String name = fileName(s);
        if(name == null) return;

        for(TileEntry t : data.tiles){
            t.members.remove(name);
        }
        if(tile != null && !tile.members.contains(name)){
            tile.members.add(name);
        }
        save();
    }

    /** Removes a deleted schematic from every tile. */
    public void removeSchematic(Schematic s){
        String name = fileName(s);
        if(name == null) return;

        boolean changed = false;
        for(TileEntry t : data.tiles){
            changed |= t.members.remove(name);
        }
        if(changed) save();
    }

    /** @return the tile owning this schematic, or null when uncategorized. */
    public TileEntry tileOf(Schematic s){
        String name = fileName(s);
        if(name == null) return null;

        for(TileEntry t : data.tiles){
            if(t.members.contains(name)) return t;
        }
        return null;
    }

    public boolean isInTile(Schematic s){
        return tileOf(s) != null;
    }

    public TileEntry byId(String id){
        if(id == null) return null;
        for(TileEntry t : data.tiles){
            if(id.equals(t.id)) return t;
        }
        return null;
    }

    /** @return the schematic with the given .msch file name, or null. Index rebuilt on demand. */
    public Schematic byFileName(String name){
        if(name == null) return null;
        for(Schematic s : schematics.all()){
            if(name.equals(fileName(s))) return s;
        }
        return null;
    }

    public static String fileName(Schematic s){
        return s != null && s.file != null ? s.file.name() : null;
    }

    private void pruneMissing(){
        ObjectMap<String, Schematic> byFile = index();
        for(TileEntry t : data.tiles){
            if(t.members == null){
                t.members = new Seq<>();
            }
            for(int i = t.members.size - 1; i >= 0; i--){
                if(!byFile.containsKey(t.members.get(i))){
                    t.members.remove(i);
                }
            }
            if(t.coverMode != null && t.coverMode.equals("schematic") && t.coverRef != null && !byFile.containsKey(t.coverRef)){
                t.coverMode = "auto";
                t.coverRef = null;
            }
        }
    }

    /** One-time conversion of vanilla schematic tags into tiles (first label wins per schematic). */
    private void migrateTags(){
        if(data.migrated) return;
        data.migrated = true;

        Seq<String> tags = Core.settings.getJson(VANILLA_TAGS_KEY, Seq.class, String.class, Seq::new);
        if(tags == null || tags.isEmpty()){
            save();
            return;
        }

        ObjectMap<String, Schematic> byFile = index();

        ObjectMap<String, TileEntry> byTag = new ObjectMap<>();
        for(String tag : tags){
            TileEntry tile = new TileEntry();
            tile.id = String.valueOf(System.currentTimeMillis()) + "-" + Math.abs(tag.hashCode());
            tile.name = tag;
            tile.w = 2;
            tile.h = 1;
            tile.coverMode = "auto";
            tile.members = new Seq<>();
            data.tiles.add(tile);
            byTag.put(tag, tile);
        }

        for(Schematic s : schematics.all()){
            String name = fileName(s);
            if(name == null || s.labels.isEmpty()) continue;

            TileEntry tile = byTag.get(s.labels.first());
            if(tile != null && !tile.members.contains(name)){
                tile.members.add(name);
            }
        }

        save();
        justMigrated = true;
    }

    /**
     * One-time conversion of the legacy {@code size} field (small/wide/large) into grid-cell
     * dimensions. Only runs for tiles that still carry a size and untouched w/h, so user-set
     * sizes are never overwritten. After migration the size field is cleared and saved.
     */
    private void migrateSizes(){
        boolean changed = false;
        for(TileEntry t : data.tiles){
            if(t.size == null || t.w != 1 || t.h != 1) continue;

            if("wide".equals(t.size)){
                t.w = 2;
            }else if("large".equals(t.size)){
                t.w = 2;
                t.h = 2;
            }else{
                t.w = 1;
                t.h = 1;
            }
            t.size = null;
            changed = true;
        }
        if(changed) save();
    }

    private static ObjectMap<String, Schematic> index(){
        ObjectMap<String, Schematic> byFile = new ObjectMap<>();
        for(Schematic s : schematics.all()){
            String name = fileName(s);
            if(name != null) byFile.put(name, s);
        }
        return byFile;
    }
}
