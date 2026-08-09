package mindustry.ui.dialogs;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.Button.*;
import arc.scene.ui.ImageButton.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import betterblueprints.*;
import betterblueprints.BlueprintsData.TileEntry;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.ResizeEvent;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.ui.*;

import java.util.regex.*;

import static mindustry.Vars.*;

/**
 * Tile-based replacement for the vanilla blueprint browser.
 *
 * <p>Opening the dialog shows a Windows-10-style tile grid first: a fixed "All" tile, the
 * user-defined tiles in custom order, a fixed "Uncategorized" tile and a "+" tile for creating
 * new tiles. Clicking a tile enters a per-tile schematic list; every schematic belongs to at most
 * one tile (enforced by {@link BlueprintsStore#moveTo}).</p>
 *
 * <p>This class extends {@link SchematicsDialog} so it can replace the browser globally without
 * touching the typed {@code Vars.ui.schematics} field. The mod classloader breaks package-private
 * overrides, so the vanilla visibility listeners are removed and the whole layout is rebuilt by
 * {@link #rebuild()} from our own shown/resized handlers instead.</p>
 *
 * <p>The tile grid follows the launcher-widget model (Android widget / Win10 start screen): every
 * tile — including the fixed "All" / "Uncategorized" / "+" tiles — is an entity on one virtual
 * grid with its own cell size ({@code w x h}) and cell position ({@code gx, gy}). Tiles can be
 * dragged freely to any cell; while dragging, the target cell is marked with a white outline and
 * the grid itself stays read-only. On drop the target is resolved once: overlapped tiles are
 * chain-pushed along the drag direction (fallback: down, then append below everything), so no
 * overlap ever remains and every tile stays visible. Positions are persisted per tile.</p>
 */
public class BetterSchematicsDialog extends SchematicsDialog{
    /** Size of one grid cell in px (vanilla blueprint card size). */
    private static final float unit = 200f;
    /** Gap between tiles in px (grid pitch keeps this spacing on the virtual grid). */
    private static final float gap = 8f;
    /** Cell pitch: one tile slot including its gap. */
    private static final float cell = unit + gap;
    /** Pointer travel required (px) before a tile drag starts. */
    private static final float dragThreshold = 24f;
    /** Safety cap for the drag target row (the grid may grow below its content, but not unboundedly). */
    private static final int maxRows = 64;
    private static final Pattern ignoreSymbols = Pattern.compile("[`~!@#$%^&*()\\-_=+{}|;:'\",<.>/?]");

    public static final String TILE_ALL = "<all>";
    public static final String TILE_UNCAT = "<uncat>";
    public static final String TILE_NEW = "<new>";

    private final BlueprintsStore store = new BlueprintsStore();
    private final TextField searchField = new TextField();
    private final Table view = new Table();
    private final ObjectMap<String, Texture> imageCovers = new ObjectMap<>();

    private String query = "";
    private String currentTile;
    private Schematic firstSchematic;
    private boolean forceClose;

    //drag & drop state
    private boolean dragActive;
    private int dragPointer = -1;
    private int dragGx, dragGy;
    private float dragDirX, dragDirY;
    private float dragStartX, dragStartY;
    private TileEntry dragTile;
    private String dragFixedId;
    private Table floatingTile;
    /** Target-cell indicator (white outline) shown while dragging (child of gridTable). */
    private Table dragGhost;
    private ScrollPane tilePane;
    private Table gridTable;
    /** Scroll offset to apply after the next grid rebuild (post-drop scroll into view). */
    private float pendingScrollY = -1;
    private boolean widthInitialized;
    private int builtCols;
    private boolean buildingGrid;
    private final Vec2 tmp = new Vec2();
    /** Placeholder entries backing the fixed tiles so drag logic treats them like any tile. */
    private final TileEntry fixedAll = new TileEntry();
    private final TileEntry fixedUncat = new TileEntry();
    private final TileEntry fixedNew = new TileEntry();

    /** One grid entity: a user tile or a fixed built-in tile, with its resolved cell. */
    static class TileEnt{
        final TileEntry source;
        final String fixedId;
        int gx, gy, w, h;

        TileEnt(TileEntry source, String fixedId, int gx, int gy, int w, int h){
            this.source = source;
            this.fixedId = fixedId;
            this.gx = gx;
            this.gy = gy;
            this.w = w;
            this.h = h;
        }
    }

    public BetterSchematicsDialog(){
        super();

        //The mod classloader breaks Java's package-identity rule (package = name + defining loader),
        //so package-private overrides of the superclass do NOT dispatch at runtime. Strip the
        //vanilla visibility listeners (registered in the super constructor) and own the full
        //lifecycle here instead.
        Seq<EventListener> removable = new Seq<>();
        for(EventListener l : getListeners()){
            if(l instanceof VisibilityListener) removable.add(l);
        }
        for(EventListener l : removable) removeListener(l);

        shown(this::rebuild);
        resized(this::rebuild);
        //BaseDialog.onResize registers a global ResizeEvent listener (vanilla setup) that cannot be
        //removed; register AFTER it so our rebuild always runs last and wins the final layout.
        Events.on(ResizeEvent.class, event -> {
            if(isShown()) rebuild();
        });
        hidden(() -> {
            for(Texture t : imageCovers.values()){
                if(t != null && !t.isDisposed()) t.dispose();
            }
            imageCovers.clear();
        });

        searchField.setMessageText("@schematic.search");
        searchField.changed(() -> {
            query = searchField.getText();
            rebuildView();
        });
        searchField.clicked(KeyCode.mouseRight, () -> {
            if(!query.isEmpty()){
                query = "";
                searchField.clearText();
                rebuildView();
            }
        });

        //fixed tiles are full grid entities: constant sizes, persisted positions
        fixedAll.w = 2;
        fixedAll.h = 1;
        fixedUncat.w = 2;
        fixedUncat.h = 1;
        fixedNew.w = 1;
        fixedNew.h = 1;
    }

    @Override
    public void hide(){
        //the bottom "back" button (addCloseButton), ESC and the close button call hide(): in a tile
        //detail view, go back to the tile overview instead of closing the whole browser.
        if(!forceClose && currentTile != null && isShown()){
            currentTile = null;
            rebuildView();
            return;
        }
        cancelDrag();
        forceClose = false;
        super.hide();
    }

    @Override
    public Dialog show(){
        //reset to the tile grid on every open; our own shown-handler calls rebuild() afterwards
        query = "";
        searchField.clearText();
        currentTile = null;
        cancelDrag();

        Dialog out = super.show();

        if(Core.app.isDesktop() && searchField != null){
            Core.scene.setKeyboardFocus(searchField);
        }

        return out;
    }

    void rebuild(){
        cancelDrag();
        try{
            store.ensureLoaded();
            if(store.consumeMigratedFlag()){
                ui.showInfoFade(Core.bundle.format("better-blueprints.migrated", store.data.tiles.size));
            }

            cont.top();
            cont.clear();

            cont.table(s -> {
                s.left();
                s.image(Icon.zoom);
                s.add(searchField).growX();
                s.button(Icon.settings, Styles.emptyi, this::showManageTiles).size(44f).padLeft(6).tooltip("@better-blueprints.managetiles");
            }).fillX().padBottom(4);

            cont.row();

            cont.add(view).grow();

            rebuildView();
        }catch(Throwable t){
            //never leave the dialog in a half-built state; fall back to an error message
            Log.err("[BetterBlueprints] rebuild failed", t);
            try{
                cont.top();
                cont.clear();
                cont.add("[scarlet]" + t.getClass().getSimpleName() + ": " + t.getMessage()).pad(20f);
            }catch(Throwable ignored){}
        }
    }

    void rebuildView(){
        if(dragActive) return; //the grid is read-only while a drag is in progress
        view.clearChildren();
        view.top();
        if(currentTile == null){
            buildTileGrid(view);
        }else{
            buildTileDetail(view);
        }
    }

    void enterTile(String id){
        currentTile = id;
        rebuildView();
    }

    /** When importing while inside a user tile, assign imported schematics to that tile automatically. */
    void autoAssignTile(Schematic s){
        TileEntry tile = store.byId(currentTile);
        if(tile != null){
            store.moveTo(tile, s);
        }
    }

    /** Import flow replacement: vanilla's version calls setup(), which would dispatch to the vanilla layout across the mod classloader. */
    @Override
    public void showImport(){
        BaseDialog dialog = new BaseDialog("@editor.import");
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButtonStyle style = Styles.flatt;
                t.defaults().size(280f, 60f).left();
                t.row();
                t.button("@load.clipboard", Icon.copy, style, () -> {
                    dialog.hide();
                    try{
                        Schematic s = Schematics.readBase64(Core.app.getClipboardText());
                        s.removeSteamID();
                        schematics.add(s);
                        autoAssignTile(s);
                        rebuild();
                        ui.showInfoFade("@schematic.saved");
                        showInfo(s);
                    }catch(Throwable e){
                        ui.showException(e);
                    }
                }).marginLeft(12f).disabled(b -> Core.app.getClipboardText() == null || !Core.app.getClipboardText().startsWith(schematicBaseStart));
                t.row();
                t.button("@import.file", Icon.download, style, () -> FileChooser.open(schematicExtension).submitMulti(files -> {
                    dialog.hide();

                    Schematic last = null;

                    for(Fi file : files){
                        try{
                            Schematic s = Schematics.read(file);
                            s.removeSteamID();
                            schematics.add(s);
                            autoAssignTile(s);
                            last = s;
                        }catch(Exception e){
                            ui.showException(e);
                        }
                    }

                    if(last != null){
                        showInfo(last);
                    }

                    rebuild();
                })).marginLeft(12f);
                t.row();
                if(steam){
                    t.button("@workshop.browse", Icon.book, style, () -> {
                        dialog.hide();
                        platform.openWorkshop();
                    }).marginLeft(12f);
                }
            });
        });

        dialog.addCloseButton();
        dialog.show();
    }

    //region tile grid

    int cols(){
        //columns come from the pane's real width once laid out; fall back to the screen width
        //on the very first pass (before the first layout) so auto-placement still works
        float avail = (tilePane != null && tilePane.getWidth() > 0) ? tilePane.getWidth() : Core.graphics.getWidth();
        return Math.max(1, (int)(avail / Scl.scl(unit)));
    }

    void buildTileGrid(Table root){
        widthInitialized = false;
        Cell<ScrollPane> cell = root.pane(p -> {
            p.top().left();
            gridTable = new Table();
            gridTable.top().left();
            p.add(gridTable).top().left();
            buildGrid();
        }).grow().scrollX(false);
        tilePane = cell.get();
        tilePane.update(() -> {
            //apply the post-drop scroll once this pane has a real size
            if(pendingScrollY >= 0 && tilePane.getHeight() > 0){
                float maxScroll = Math.max(0, gridTable.getHeight() - tilePane.getHeight());
                tilePane.setScrollY(Math.min(pendingScrollY, maxScroll));
                pendingScrollY = -1;
            }
            //the first layout reveals the pane's real width: rebuild once so the column count
            //and auto-placement match what is actually visible (only when the width is sane)
            if(!widthInitialized && tilePane.getWidth() >= Scl.scl(unit) * 2f){
                widthInitialized = true;
                if(cols() != builtCols){
                    buildGrid();
                }
            }
        });
    }

    /**
     * Builds the grid entities in display order: fixed "All" tile, user tiles, fixed
     * "Uncategorized" tile, fixed "+" tile. Auto-assigns and repairs cells first (see
     * {@link #materializePositions()}).
     */
    Seq<TileEnt> buildEntities(){
        materializePositions();
        Seq<TileEnt> out = new Seq<>();
        out.add(new TileEnt(fixedAll, TILE_ALL, store.data.allGx, store.data.allGy, 2, 1));
        for(TileEntry t : store.data.tiles){
            out.add(new TileEnt(t, null, t.gx, t.gy, t.w, t.h));
        }
        out.add(new TileEnt(fixedUncat, TILE_UNCAT, store.data.uncatGx, store.data.uncatGy, 2, 1));
        out.add(new TileEnt(fixedNew, TILE_NEW, store.data.newGx, store.data.newGy, 1, 1));
        return out;
    }

    /**
     * Assigns grid cells to tiles that don't have one yet (new tiles / legacy data) and repairs
     * any overlap or out-of-bounds cell: in display order, each tile keeps its cell when it is
     * valid and free, otherwise it is placed on the first free cell that fits (scanning row by
     * row). Idempotent: once every tile has a valid free cell, nothing moves and nothing is
     * saved again.
     */
    void materializePositions(){
        int ncols = cols();
        //an unreliable column count (pane not laid out yet / tiny window) must never re-place
        //tiles: one wrong pass would shuffle and persist the whole layout
        if(ncols < 2){
            Log.info("[BetterBlueprints] materialize skipped: ncols=@", ncols);
            return;
        }
        boolean changed = false;
        Seq<TileEnt> all = new Seq<>();
        all.add(new TileEnt(fixedAll, TILE_ALL, store.data.allGx, store.data.allGy, 2, 1));
        for(TileEntry t : store.data.tiles){
            all.add(new TileEnt(t, null, t.gx, t.gy, t.w, t.h));
        }
        all.add(new TileEnt(fixedUncat, TILE_UNCAT, store.data.uncatGx, store.data.uncatGy, 2, 1));
        all.add(new TileEnt(fixedNew, TILE_NEW, store.data.newGx, store.data.newGy, 1, 1));

        Seq<TileEnt> placed = new Seq<>();
        for(TileEnt e : all){
            boolean needs = e.gx < 0 || e.gy < 0 || e.gx + e.w > ncols || overlapsPlaced(placed, e.gx, e.gy, e.w, e.h);
            if(needs){
                int[] cell = firstFree(placed, ncols, e.w, e.h);
                Log.info("[BetterBlueprints] materialize: '@' (@,@)->(@,@) ncols=@", e.fixedId != null ? e.fixedId : e.source.name, e.gx, e.gy, cell[0], cell[1], ncols);
                e.gx = cell[0];
                e.gy = cell[1];
                writeBack(e);
                changed = true;
            }
            placed.add(e);
        }
        if(changed) store.save();
    }

    /** First cell (row by row) where a w x h tile fits without overlapping any placed tile. */
    static int[] firstFree(Seq<TileEnt> placed, int ncols, int w, int h){
        int limit = Math.max(16, placed.size * 8 + 16);
        int maxX = Math.max(0, ncols - w);
        for(int gy = 0; gy < limit; gy++){
            for(int gx = 0; gx <= maxX; gx++){
                if(!overlapsPlaced(placed, gx, gy, w, h)) return new int[]{gx, gy};
            }
        }
        return new int[]{0, 0};
    }

    static boolean overlapsPlaced(Seq<TileEnt> placed, int gx, int gy, int w, int h){
        for(TileEnt o : placed){
            if(overlap(gx, gy, w, h, o.gx, o.gy, o.w, o.h)) return true;
        }
        return false;
    }

    /** Writes an entity's resolved cell back into its persistent storage. */
    void writeBack(TileEnt e){
        if(e.fixedId == null){
            e.source.gx = e.gx;
            e.source.gy = e.gy;
        }else if(TILE_ALL.equals(e.fixedId)){
            store.data.allGx = e.gx;
            store.data.allGy = e.gy;
        }else if(TILE_UNCAT.equals(e.fixedId)){
            store.data.uncatGx = e.gx;
            store.data.uncatGy = e.gy;
        }else{
            store.data.newGx = e.gx;
            store.data.newGy = e.gy;
        }
    }

    static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh){
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /** Rebuilds only the tile grid table (keeps the search row and dialog chrome untouched). */
    void buildGrid(){
        if(gridTable == null || buildingGrid) return;
        buildingGrid = true;
        try{
            gridTable.clearChildren();
            Seq<TileEnt> ents = buildEntities();
            int ncols = cols();
            builtCols = ncols;

            //content size first: row gy starts at gy*cell from the content top
            float maxW = 0f, maxH = 0f;
            for(TileEnt e : ents){
                maxW = Math.max(maxW, e.gx * cell + e.w * cell - gap);
                maxH = Math.max(maxH, e.gy * cell + e.h * cell - gap);
            }

            for(TileEnt e : ents){
                float w = e.w * cell - gap, h = e.h * cell - gap;
                float x = e.gx * cell;
                //top-down placement: row 0 is the topmost row; the scene uses y-up coordinates,
                //so the top row has the highest y (older builds placed row 0 at the bottom, which
                //made tiles dropped into empty space land below the visible area and vanish)
                float y = maxH - (e.gy * cell + h);
                Element el = e.fixedId != null ? addFixedTile(e.fixedId, e.source, w, h) : addUserTile(e.source, w, h);
                el.setBounds(x, y, w, h);
                gridTable.addChild(el);
            }

            //drive the pane content size through a transparent spacer so scrolling still works
            Element spacer = new Element();
            spacer.touchable = Touchable.disabled;
            gridTable.add(spacer).size(maxW, maxH);
            gridTable.setSize(maxW, maxH);
            gridTable.validate();
        }finally{
            buildingGrid = false;
        }
    }

    Element addFixedTile(String id, TileEntry holder, float w, float h){
        if(TILE_ALL.equals(id)){
            return addTileWidget(w, h, b -> {
                //stack must grow to the full tile size, otherwise it shrinks to the pref size of
                //its children and the name bar / badge / edit button pile up in a small block
                b.stack(
                    new Table(n -> {
                        n.center();
                        n.image(Icon.paste).size(64f);
                    }),
                    nameBar(fixedName(id), w),
                    badge(schematics.all().size)
                ).grow();
            }, () -> enterTile(TILE_ALL), holder, id);
        }else if(TILE_UNCAT.equals(id)){
            return addTileWidget(w, h, b -> {
                b.stack(
                    new Table(n -> {
                        n.center();
                        n.image(Icon.folder).size(64f);
                    }),
                    nameBar(fixedName(id), w),
                    badge(countUncat())
                ).grow();
            }, () -> enterTile(TILE_UNCAT), holder, id);
        }else{
            return addTileWidget(w, h, b -> {
                b.center();
                b.image(Icon.add).size(48f);
                b.row();
                b.add("@better-blueprints.newtile").color(Color.lightGray).padTop(8f);
            }, () -> showNewTile(s -> {}), holder, id);
        }
    }

    Element addUserTile(TileEntry tile, float w, float h){
        return addTileWidget(w, h, b -> {
            b.margin(0f);
            //tiles are image-first: configurable cover (auto schematic preview / chosen schematic / imported image)
            //stack must grow to the full tile size, otherwise it shrinks to the pref size of its
            //children and the name bar / badge / edit button pile up in a small block (this shows
            //up whenever the tile has no imported image cover, since previews have a tiny pref size)
            b.stack(
                coverElement(tile),
                nameBar(tile.name, w),
                badge(tile.count()),
                pencil(tile)
            ).grow();
        }, () -> enterTile(tile.id), tile, null);
    }

    Table badge(int count){
        Table t = new Table();
        t.top().right();
        t.table(Styles.black3, c -> {
            c.add(String.valueOf(count)).color(Color.lightGray);
        }).pad(6f);
        return t;
    }

    Table pencil(TileEntry tile){
        //edit button sits at the tile's bottom so it never crowds the title bar / cover icons
        Table t = new Table();
        t.bottom().left();
        t.button(Icon.pencilSmall, Styles.emptyi, () -> showTileEditor(tile)).size(34f).pad(4f).tooltip("@better-blueprints.edit");
        return t;
    }

    Table nameBar(String name, float width){
        Table n = new Table();
        n.top();
        n.table(Styles.black3, c -> {
            Label label = c.add(name).style(Styles.outlineLabel).color(Color.white).top().growX().get();
            label.setEllipsis(true);
            label.setAlignment(Align.center);
        }).growX().margin(1).pad(4).padBottom(0);
        return n;
    }

    /** Cover element for a tile: schematic preview, custom image, or placeholder. */
    Element coverElement(TileEntry tile){
        if(tile.coverMode != null && tile.coverMode.equals("image") && tile.coverRef != null){
            Texture tex = imageTexture(tile.coverRef);
            if(tex != null){
                Image img = new Image(new TextureRegionDrawable(new TextureRegion(tex)));
                img.setScaling(Scaling.fit);
                return img;
            }
        }

        Schematic s = coverSchematic(tile);
        if(s != null){
            return new SchematicImage(s);
        }

        Table ph = new Table();
        ph.center();
        ph.image(Icon.paste).size(64f);
        return ph;
    }

    Schematic coverSchematic(TileEntry tile){
        if(tile.coverMode != null && tile.coverMode.equals("schematic") && tile.coverRef != null){
            Schematic s = store.byFileName(tile.coverRef);
            if(s != null) return s;
        }
        //auto: first member in display order
        for(Schematic s : schematics.all()){
            if(tile.members.contains(BlueprintsStore.fileName(s))) return s;
        }
        return null;
    }

    Texture imageTexture(String name){
        Texture tex = imageCovers.get(name);
        if(tex != null && tex.isDisposed()){
            imageCovers.remove(name);
            tex = null;
        }
        if(tex == null){
            Fi file = Core.files.local("better-blueprints/covers/").child(name);
            if(!file.exists()) return null;
            try{
                tex = new Texture(file);
                imageCovers.put(name, tex);
            }catch(Throwable t){
                Log.err("[BetterBlueprints] failed to load cover image '@'", name);
                Log.err(t);
                return null;
            }
        }
        return tex;
    }

    int countUncat(){
        int count = 0;
        for(Schematic s : schematics.all()){
            if(!store.isInTile(s)) count++;
        }
        return count;
    }

    /**
     * Creates one fixed-size tile widget (not added to any parent; the grid places it
     * absolutely). Every tile ({@code dragSource != null}, user or fixed) gets a drag listener:
     * a pointer press records the drag start, the actual drag loop runs in the floating tile's
     * per-frame update (robust against the scroll pane cancelling touch focus when it starts
     * panning).
     */
    Element addTileWidget(float w, float h, Cons<Table> content, Runnable click, TileEntry dragSource, String fixedId){
        Button button = new Button(Styles.flati);
        //local style copy: never mutate the shared flati style
        ButtonStyle style = new ButtonStyle(button.getStyle());
        style.up = Tex.pane;
        button.setStyle(style);
        button.top();
        button.margin(0f);
        content.get(button);
        button.setSize(w, h);

        button.clicked(() -> {
            if(button.childrenPressed()) return;
            if(dragActive) return; //a drag was in progress: release must not navigate
            click.run();
        });

        if(dragSource != null){
            button.addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode code){
                    if(dragPointer != -1) return false;
                    if(code != null && code != KeyCode.mouseLeft) return false;
                    //ignore presses starting on the pencil / badge area
                    if(button.childrenPressed()) return false;
                    beginDrag(pointer, dragSource, fixedId);
                    return false;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode code){
                    if(pointer != dragPointer) return;
                    //scroll pane panning cancelled our focus: the real release is detected by polling
                    if(event.isTouchFocusCancel()) return;
                    endDrag();
                }
            });
        }
        return button;
    }

    //endregion

    //region tile drag & drop

    void beginDrag(int pointer, TileEntry tile, String fixedId){
        dragPointer = pointer;
        dragTile = tile;
        dragFixedId = fixedId;
        dragActive = false;
        dragStartX = Core.input.mouseX(pointer);
        dragStartY = Core.input.mouseY(pointer);

        //copy of the tile (cover + name bar) that follows the pointer; kept off-screen until the
        //drag threshold is crossed. Its update() polls the input so the drag works even after the
        //scroll pane steals touch focus.
        float w = tile.w * cell - gap, h = tile.h * cell - gap;
        floatingTile = new Table();
        floatingTile.touchable = Touchable.disabled;
        floatingTile.top();
        floatingTile.margin(0f);
        floatingTile.setBackground(Tex.pane);
        floatingTile.stack(
            dragBody(tile, fixedId),
            nameBar(dragName(), w)
        ).size(w, h);
        floatingTile.setSize(w, h);
        floatingTile.update(() -> updateDrag());
        floatingTile.setPosition(-10000f, -10000f);
        addChild(floatingTile);
    }

    void updateDrag(){
        if(dragPointer < 0){
            if(floatingTile != null){
                floatingTile.remove();
                floatingTile = null;
            }
            return;
        }

        if(!Core.input.isTouched()){
            endDrag();
            return;
        }

        float mx = Core.input.mouseX(dragPointer);
        float my = Core.input.mouseY(dragPointer);

        if(!dragActive){
            float dx = mx - dragStartX, dy = my - dragStartY;
            if(dx * dx + dy * dy < dragThreshold * dragThreshold) return;

            dragActive = true;
            //dominant drag direction decides how displaced tiles get shoved on drop
            dragDirX = Math.abs(dx) >= Math.abs(dy) ? Math.signum(dx) : 0f;
            dragDirY = Math.abs(dy) > Math.abs(dx) ? Math.signum(dy) : 0f;
            if(dragDirX == 0f && dragDirY == 0f) dragDirX = 1f;
            int[] start = computeDragTarget(mx, my);
            dragGx = start[0];
            dragGy = start[1];
            if(tilePane != null) tilePane.setScrollingDisabled(true, true);

            //target-cell white outline inside the grid; the grid itself never rebuilds while dragging
            dragGhost = new Table(){
                @Override
                public void draw(){
                    Draw.color(Color.white, 0.85f);
                    Lines.stroke(Scl.scl(1.5f));
                    Lines.rect(x, y, width, height);
                    Draw.reset();
                }
            };
            dragGhost.touchable = Touchable.disabled;
            gridTable.addChild(dragGhost);
            updateGhost();
            Log.info("[BetterBlueprints] drag started: '@' target=(@,@)", dragName(), dragGx, dragGy);
        }

        //floating tile follows the pointer, centered on it, in dialog-local coordinates
        tmp.set(mx, my);
        stageToLocalCoordinates(tmp);
        floatingTile.setPosition(tmp.x - floatingTile.getWidth() / 2f, tmp.y - floatingTile.getHeight() / 2f);

        //target cell follows the pointer; only the ghost moves, the grid layout stays untouched
        int[] target = computeDragTarget(mx, my);
        if(target[0] != dragGx || target[1] != dragGy){
            dragGx = target[0];
            dragGy = target[1];
            updateGhost();
        }
    }

    /**
     * Pointer to target grid cell: the dragged tile's center snaps to the pointer, clamped to
     * the grid (horizontally to the visible columns, vertically to a sane row limit).
     */
    int[] computeDragTarget(float mx, float my){
        tmp.set(mx, my);
        gridTable.stageToLocalCoordinates(tmp);
        float contentH = gridTable.getHeight();
        float wOff = (dragTile.w * cell - gap) / 2f;
        float hOff = (dragTile.h * cell - gap) / 2f;
        int tgx = Math.round((tmp.x - wOff) / cell);
        int tgy = Math.round((contentH - tmp.y - hOff) / cell);
        tgx = Math.max(0, Math.min(tgx, Math.max(0, cols() - dragTile.w)));
        tgy = Math.max(0, Math.min(tgy, maxRows));
        return new int[]{tgx, tgy};
    }

    /** Moves the target-cell outline to the current drag cell. Top-down placement means a cell
     * below the current content has a negative y and shows in the empty pane area. */
    void updateGhost(){
        if(dragGhost == null) return;
        float contentH = gridTable.getHeight();
        dragGhost.setBounds(
            dragGx * cell,
            contentH - (dragGy * cell + dragTile.h * cell - gap),
            dragTile.w * cell - gap,
            dragTile.h * cell - gap
        );
    }

    /**
     * Applies the drop. Displacement rule (single, deterministic definition):
     * <ol>
     * <li>if the dragged tile covers another tile, the two <b>swap</b> cells (the covered tile
     *     moves to the dragged tile's old cell, the dragged tile takes the target cell);</li>
     * <li>tiles that still overlap after the swap (larger tiles covering several others, or a
     *     swap partner that does not fit the old cell) are pushed one cell at a time along the
     *     dominant drag direction, shoving any tile in their way (chain push);</li>
     * <li>if the chain is blocked, the same push is tried straight down;</li>
     * <li>as a last resort the tile is appended below everything, leftmost first.</li>
     * </ol>
     * The result never contains overlaps. Persisted positions are updated via
     * {@link #writeBack(TileEnt)}; returns whether anything changed.
     */
    boolean resolveDrop(Seq<TileEnt> ents, TileEnt dragged, int tgx, int tgy, float dirX, float dirY, int ncols){
        ObjectMap<TileEntry, int[]> pos = new ObjectMap<>();
        for(TileEnt e : ents) pos.put(e.source, new int[]{e.gx, e.gy});
        int[] dp = pos.get(dragged.source);
        int[] from = {dp[0], dp[1]};
        dp[0] = tgx;
        dp[1] = tgy;

        //swap rule: the first tile covered by the dragged tile exchanges cells with it
        for(TileEnt e : ents){
            if(e == dragged) continue;
            int[] p = pos.get(e.source);
            if(overlap(dp[0], dp[1], dragged.w, dragged.h, p[0], p[1], e.w, e.h)){
                p[0] = from[0];
                p[1] = from[1];
                Log.info("[BetterBlueprints] swap '@' (@,@) <-> '@' (@,@)", dragged.source.name, from[0], from[1], e.source.name, tgx, tgy);
                break;
            }
        }

        //resolve any remaining overlaps with the push rules; the append fallback guarantees termination
        for(int pass = 0; pass < ents.size + 2; pass++){
            boolean any = false;
            for(TileEnt e : ents){
                if(e == dragged) continue;
                int[] p = pos.get(e.source);
                if(!overlapsAny(ents, pos, e)) continue;
                any = true;
                ObjectSet<TileEntry> visited = new ObjectSet<>();
                int[] moved = pushChain(ents, pos, e, p, dirX, dirY, visited, dragged.source, ncols);
                if(moved == null) moved = pushChain(ents, pos, e, p, 0f, 1f, visited, dragged.source, ncols);
                if(moved == null) moved = appendBottom(ents, pos, e, ncols);
                p[0] = moved[0];
                p[1] = moved[1];
            }
            if(!any) break;
        }

        boolean changed = false;
        for(TileEnt e : ents){
            int[] p = pos.get(e.source);
            if(p[0] != e.gx || p[1] != e.gy){
                e.gx = p[0];
                e.gy = p[1];
                writeBack(e);
                changed = true;
            }else{
                e.gx = p[0];
                e.gy = p[1];
            }
        }
        return changed;
    }

    /** Recursively pushes {@code e} one cell along the direction, shoving blockers in turn.
     * Returns the pushed tile's new cell, or null when the chain is blocked (visited / edge). */
    static int[] pushChain(Seq<TileEnt> ents, ObjectMap<TileEntry, int[]> pos, TileEnt e, int[] cur,
                           float dirX, float dirY, ObjectSet<TileEntry> visited, TileEntry dragged, int ncols){
        if(visited.contains(e.source)) return null;
        visited.add(e.source);

        int nx = cur[0] + Math.round(dirX), ny = cur[1] + Math.round(dirY);
        if(dirX != 0) nx = Math.max(0, Math.min(nx, Math.max(0, ncols - e.w)));
        ny = Math.max(0, ny);
        if(nx == cur[0] && ny == cur[1]) return null; //at the edge: cannot move along this direction

        TileEntry blocker = occupantAt(ents, pos, nx, ny, e.w, e.h, e.source);
        if(blocker == null) return new int[]{nx, ny};
        if(blocker == dragged || visited.contains(blocker)) return null;

        TileEnt be = entFor(ents, blocker);
        int[] bmoved = pushChain(ents, pos, be, pos.get(blocker), dirX, dirY, visited, dragged, ncols);
        if(bmoved == null) return null;
        pos.put(blocker, bmoved);
        return new int[]{nx, ny};
    }

    /** First entity (other than self) whose rect overlaps the given cell, or null. */
    static TileEntry occupantAt(Seq<TileEnt> ents, ObjectMap<TileEntry, int[]> pos, int gx, int gy, int w, int h, TileEntry self){
        for(TileEnt e : ents){
            if(e.source == self) continue;
            int[] p = pos.get(e.source);
            if(overlap(gx, gy, w, h, p[0], p[1], e.w, e.h)) return e.source;
        }
        return null;
    }

    /** Whether {@code e} overlaps any other entity in the position map. */
    static boolean overlapsAny(Seq<TileEnt> ents, ObjectMap<TileEntry, int[]> pos, TileEnt e){
        int[] p = pos.get(e.source);
        for(TileEnt o : ents){
            if(o.source == e.source) continue;
            int[] q = pos.get(o.source);
            if(overlap(p[0], p[1], e.w, e.h, q[0], q[1], o.w, o.h)) return true;
        }
        return false;
    }

    /** Leftmost cell below everything that fits this tile (always succeeds). */
    static int[] appendBottom(Seq<TileEnt> ents, ObjectMap<TileEntry, int[]> pos, TileEnt e, int ncols){
        int maxRow = 0;
        for(TileEnt o : ents){
            int[] p = pos.get(o.source);
            maxRow = Math.max(maxRow, p[1] + o.h);
        }
        int maxX = Math.max(ncols, e.w);
        for(int row = maxRow; row < maxRow + 128; row++){
            for(int x = 0; x <= Math.max(0, maxX - e.w); x++){
                if(occupantAt(ents, pos, x, row, e.w, e.h, e.source) == null){
                    return new int[]{x, row};
                }
            }
        }
        return new int[]{0, maxRow}; //unreachable in practice
    }

    static TileEnt entFor(Seq<TileEnt> ents, TileEntry source){
        for(TileEnt e : ents){
            if(e.source == source) return e;
        }
        return null;
    }

    /** Finishes the drag: resolves the final layout once, persists, restores scrolling, rebuilds. */
    void endDrag(){
        if(dragPointer < 0) return;
        if(!dragActive){
            cancelDrag();
            return;
        }

        Seq<TileEnt> ents = buildEntities();
        TileEnt draggedEnt = entFor(ents, dragTile);
        if(draggedEnt != null){
            boolean changed = resolveDrop(ents, draggedEnt, dragGx, dragGy, dragDirX, dragDirY, cols());
            if(changed) store.save();
            Log.info("[BetterBlueprints] drop '@' -> (@,@) changed=@", dragName(), dragGx, dragGy, changed);

            //scroll the dropped tile into view when it landed below the current viewport
            float bottom = (dragGy + dragTile.h) * cell - gap;
            float vh = tilePane != null ? tilePane.getHeight() : 0f;
            pendingScrollY = (vh > 0 && bottom > vh) ? bottom - vh + Scl.scl(16f) : -1;
        }else{
            pendingScrollY = -1;
        }

        if(tilePane != null) tilePane.setScrollingDisabled(false, false);
        cancelDrag();
        rebuildView();
    }

    /** Drops all drag state without applying or rebuilding. */
    void cancelDrag(){
        dragPointer = -1;
        dragActive = false;
        dragTile = null;
        dragFixedId = null;
        dragGx = 0;
        dragGy = 0;
        if(floatingTile != null){
            floatingTile.remove();
            floatingTile = null;
        }
        if(dragGhost != null){
            dragGhost.remove();
            dragGhost = null;
        }
    }

    /** Body of the floating drag copy: fixed tiles show their icon, user tiles their cover. */
    Element dragBody(TileEntry tile, String fixedId){
        if(fixedId != null){
            Table t = new Table();
            t.center();
            t.image(TILE_ALL.equals(fixedId) ? Icon.paste : TILE_UNCAT.equals(fixedId) ? Icon.folder : Icon.add).size(64f);
            return t;
        }
        return coverElement(tile);
    }

    String dragName(){
        return dragFixedId != null ? fixedName(dragFixedId) : (dragTile != null ? dragTile.name : "");
    }

    String fixedName(String id){
        if(TILE_ALL.equals(id)) return Core.bundle.get("better-blueprints.all");
        if(TILE_UNCAT.equals(id)) return Core.bundle.get("better-blueprints.uncat");
        return Core.bundle.get("better-blueprints.newtile");
    }

    //endregion

    //region tile detail

    void buildTileDetail(Table root){
        String title = tileTitle();

        root.table(header -> {
            header.left();
            header.button(Icon.left, Styles.emptyi, () -> {
                currentTile = null;
                rebuildView();
            }).size(44f).tooltip("@better-blueprints.back");
            header.add(title).padLeft(10f).color(Color.white);
        }).fillX().padBottom(4);

        root.row();

        root.pane(p -> {
            p.top();

            p.update(() -> {
                if(Core.input.keyTap(Binding.chat) && Core.scene.getKeyboardFocus() == searchField && firstSchematic != null){
                    if(!state.rules.schematicsAllowed){
                        ui.showInfo("@schematic.disabled");
                    }else{
                        control.input.useSchematic(firstSchematic);
                        forceClose = true;
                        hide();
                    }
                }
            });

            buildCards(p);
        }).grow().scrollX(false);
    }

    void buildCards(Table p){
        int cols = Math.max((int)(Core.graphics.getWidth() / Scl.scl(230)), 1);

        p.clearChildren();
        int i = 0;
        String searchString = ignoreSymbols.matcher(query.toLowerCase()).replaceAll("");

        firstSchematic = null;

        for(Schematic s : filteredSchematics()){
            if(firstSchematic == null) firstSchematic = s;

            Button[] sel = {null};
            sel[0] = p.button(b -> {
                b.top();
                b.margin(0f);
                b.table(buttons -> {
                    buttons.left();
                    buttons.defaults().size(50f);

                    ImageButtonStyle style = Styles.emptyi;

                    buttons.button(Icon.info, style, () -> showInfo(s)).tooltip("@info.title");
                    buttons.button(Icon.upload, style, () -> showExport(s)).tooltip("@editor.export");
                    buttons.button(Icon.pencil, style, () -> showEdit(s)).tooltip("@schematic.edit");

                    if(s.hasSteamID()){
                        buttons.button(Icon.link, style, () -> platform.viewListing(s)).tooltip("@view.workshop");
                    }else{
                        buttons.button(Icon.trash, style, () -> {
                            if(s.mod != null){
                                ui.showInfo(Core.bundle.format("mod.item.remove", s.mod.meta.displayName));
                            }else{
                                ui.showConfirm("@confirm", "@schematic.delete.confirm", () -> {
                                    store.removeSchematic(s);
                                    schematics.remove(s);
                                    rebuildView();
                                });
                            }
                        }).tooltip("@save.delete");
                    }
                }).growX().height(50f);
                b.row();
                b.stack(new SchematicImage(s).setScaling(Scaling.fit), new Table(n -> {
                    n.top();
                    n.table(Styles.black3, c -> {
                        Label label = c.add(s.name()).style(Styles.outlineLabel).color(Color.white).top().growX().maxWidth(200f - 8f).get();
                        label.setEllipsis(true);
                        label.setAlignment(Align.center);
                    }).growX().margin(1).pad(4).maxWidth(Scl.scl(200f - 8f)).padBottom(0);
                })).size(200f);
            }, () -> {
                if(sel[0].childrenPressed()) return;
                if(state.isMenu()){
                    showInfo(s);
                }else{
                    if(!state.rules.schematicsAllowed){
                        ui.showInfo("@schematic.disabled");
                    }else{
                        control.input.useSchematic(s);
                        forceClose = true;
                        hide();
                    }
                }
            }).pad(4).style(Styles.flati).get();

            sel[0].getStyle().up = Tex.pane;

            if(++i % cols == 0){
                p.row();
            }
        }

        if(firstSchematic == null){
            p.add(query.isEmpty() && !TILE_ALL.equals(currentTile) ? "@better-blueprints.empty" : "@none.found").padLeft(54f).padTop(10f);
        }
    }

    Seq<Schematic> filteredSchematics(){
        Seq<Schematic> out = new Seq<>();
        String searchString = ignoreSymbols.matcher(query.toLowerCase()).replaceAll("");

        for(Schematic s : schematics.all()){
            if(!matchesTile(s)) continue;
            if(!query.isEmpty() && !ignoreSymbols.matcher(s.name().toLowerCase()).replaceAll("").contains(searchString)) continue;
            out.add(s);
        }
        return out;
    }

    boolean matchesTile(Schematic s){
        if(TILE_ALL.equals(currentTile)) return true;
        if(TILE_UNCAT.equals(currentTile)) return !store.isInTile(s);
        TileEntry tile = store.byId(currentTile);
        return tile != null && tile.members.contains(BlueprintsStore.fileName(s));
    }

    String tileTitle(){
        if(TILE_ALL.equals(currentTile)) return Core.bundle.get("better-blueprints.all");
        if(TILE_UNCAT.equals(currentTile)) return Core.bundle.get("better-blueprints.uncat");
        TileEntry tile = store.byId(currentTile);
        return tile == null ? "" : tile.name;
    }

    //endregion

    //region info / edit dialogs

    @Override
    public void showInfo(Schematic schematic){
        showInfoDialog(schematic);
    }

    void showInfoDialog(Schematic schem){
        BaseDialog dialog = new BaseDialog("");
        dialog.setFillParent(true);
        dialog.addCloseListener();
        dialog.title.setText("[[" + Core.bundle.get("schematic") + "] " + schem.name());

        Runnable[] rebuild = {null};

        rebuild[0] = () -> {
            dialog.cont.clear();

            Table inner = new Table();

            inner.add(Core.bundle.format("schematic.info", schem.width, schem.height, schem.tiles.size)).color(Color.lightGray).row();

            //current tile + move button
            TileEntry cur = store.tileOf(schem);
            inner.table(tileRow -> {
                tileRow.left();
                tileRow.add("@better-blueprints.current").padRight(6f);
                tileRow.add(cur == null ? "[lightgray]" + Core.bundle.get("better-blueprints.notile") : cur.name).padRight(10f);
                tileRow.button("@better-blueprints.moveto", () -> showMoveDialog(schem, () -> rebuild[0].run())).size(180f, 44f);
            }).fillX().pad(6).row();

            inner.add(new SchematicImage(schem)).maxSize(800f).row();

            ItemSeq arr = schem.requirements();
            inner.table(r -> {
                int i = 0;
                for(ItemStack s : arr){
                    r.image(s.item.uiIcon).left().size(iconMed);
                    r.label(() -> {
                        Building core = player.core();
                        if(core == null || state.isMenu() || state.rules.infiniteResources || core.items.has(s.item, s.amount)) return "[lightgray]" + s.amount + "";
                        return (core.items.has(s.item, s.amount) ? "[lightgray]" : "[scarlet]") + Math.min(core.items.get(s.item), s.amount) + "[lightgray]/" + s.amount;
                    }).padLeft(2).left().padRight(4);

                    if(++i % 4 == 0){
                        r.row();
                    }
                }
            }).pad(6).row();

            float cons = schem.powerConsumption() * 60, prod = schem.powerProduction() * 60;
            if(!Mathf.zero(cons) || !Mathf.zero(prod)){
                inner.table(t -> {
                    if(!Mathf.zero(prod)){
                        t.image(Icon.powerSmall).color(Pal.powerLight).padRight(3);
                        t.add("+" + Strings.autoFixed(prod, 2)).color(Pal.powerLight).left();

                        if(!Mathf.zero(cons)){
                            t.add().width(15);
                        }
                    }

                    if(!Mathf.zero(cons)){
                        t.image(Icon.powerSmall).color(Pal.remove).padRight(3);
                        t.add("-" + Strings.autoFixed(cons, 2)).color(Pal.remove).left();
                    }
                }).row();
            }

            if(!schem.description().isEmpty()){
                inner.add("[lightgray]" + schem.description()).wrap().padTop(20).growX().maxWidth(500).padLeft(8).padRight(8).row();
            }

            dialog.cont.pane(p -> {
                p.add(inner).growX();
            }).grow().scrollX(false).scrollY(true);

            dialog.buttons.clearChildren();
            dialog.buttons.defaults().size(Core.graphics.isPortrait() ? 150f : 210f, 64f);
            dialog.buttons.button("@back", Icon.left, dialog::hide);
            dialog.buttons.button("@editor.export", Icon.upload, () -> showExport(schem));
            dialog.buttons.button("@edit", Icon.edit, () -> showEdit(schem));
        };

        rebuild[0].run();
        dialog.show();
    }

    void showMoveDialog(Schematic s, Runnable after){
        BaseDialog dialog = new BaseDialog("@better-blueprints.moveto");
        dialog.cont.pane(p -> {
            p.defaults().fillX().height(48f).pad(3);
            p.button("@better-blueprints.uncat", Styles.togglet, () -> {
                store.moveTo(null, s);
                dialog.hide();
                after.run();
            }).checked(store.tileOf(s) == null);

            for(TileEntry tile : store.data.tiles){
                p.button(tile.name, Styles.togglet, () -> {
                    store.moveTo(tile, s);
                    dialog.hide();
                    after.run();
                }).checked(store.tileOf(s) == tile);
            }
        }).grow().maxHeight(Core.graphics.getHeight() * 0.7f).scrollX(false);
        dialog.addCloseButton();
        dialog.show();
    }

    @Override
    public void showEdit(Schematic s){
        new BaseDialog("@schematic.edit"){{
            setFillParent(true);
            addCloseListener();

            cont.margin(30);

            cont.add("@better-blueprints.current").padRight(6f);
            cont.table(tiles -> {
                tiles.left();
                TileEntry cur = store.tileOf(s);
                tiles.add(cur == null ? "[lightgray]" + Core.bundle.get("better-blueprints.notile") : cur.name).padRight(10f);
                tiles.button("@better-blueprints.moveto", () -> showMoveDialog(s, () -> rebuild())).size(180f, 44f);
            }).maxWidth(400f).fillX().left().row();

            cont.margin(30).add("@name").padRight(6f);
            TextField nameField = cont.field(s.name(), null).size(400f, 55f).left().get();

            cont.row();

            cont.margin(30).add("@editor.description").padRight(6f);
            TextField descField = cont.area(s.description(), Styles.areaField, t -> {}).size(400f, 140f).left().get();

            Runnable accept = () -> {
                s.tags.put("name", nameField.getText());
                s.tags.put("description", descField.getText());
                s.save();
                hide();
                rebuild();
            };

            buttons.defaults().size(210f, 64f).pad(4);
            buttons.button("@ok", Icon.ok, accept).disabled(b -> nameField.getText().isEmpty());
            buttons.button("@cancel", Icon.cancel, this::hide);

            keyDown(KeyCode.enter, () -> {
                if(!nameField.getText().isEmpty() && Core.scene.getKeyboardFocus() != descField){
                    accept.run();
                }
            });
        }}.show();
    }

    //endregion

    //region tile management

    void showManageTiles(){
        BaseDialog dialog = new BaseDialog("@better-blueprints.managetiles");
        dialog.addCloseButton();
        Runnable[] rebuild = {null};
        dialog.cont.pane(p -> {
            rebuild[0] = () -> {
                p.clearChildren();
                p.margin(12f).defaults().fillX().left();

                //fixed tiles shown as non-editable hint entries
                p.table(Tex.whiteui, hint -> {
                    hint.setColor(Pal.gray);
                    hint.margin(6f);
                    hint.left();
                    hint.image(Icon.paste).size(28f).padRight(8f);
                    hint.add(Core.bundle.get("better-blueprints.all")).color(Color.lightGray).padRight(16f);
                    hint.image(Icon.folder).size(28f).padRight(8f);
                    hint.add(Core.bundle.get("better-blueprints.uncat")).color(Color.lightGray).padRight(16f);
                    hint.add("[lightgray]" + Core.bundle.get("better-blueprints.builtin"));
                }).pad(4);

                float sum = 0f;
                Table current = new Table().left();

                for(TileEntry tile : store.data.tiles){
                    float si = 40f;

                    Table next = new Table(Tex.whiteui, n -> {
                        n.setColor(Pal.gray);
                        n.margin(5f);

                        n.table(move -> {
                            move.button(Icon.upOpen, Styles.emptyi, () -> {
                                int idx = store.data.tiles.indexOf(tile);
                                if(idx > 0){
                                    store.data.tiles.swap(idx, idx - 1);
                                    store.save();
                                    rebuild[0].run();
                                }
                            }).size(si).tooltip("@editor.moveup").row();

                            move.button(Icon.downOpen, Styles.emptyi, () -> {
                                int idx = store.data.tiles.indexOf(tile);
                                if(idx < store.data.tiles.size - 1){
                                    store.data.tiles.swap(idx, idx + 1);
                                    store.save();
                                    rebuild[0].run();
                                }
                            }).size(si).tooltip("@editor.movedown");
                        }).fillY();

                        n.table(t -> {
                            t.add(tile.name).left().row();
                            t.add(String.valueOf(tile.count())).left()
                            .update(b -> b.setColor(b.hasMouse() ? Pal.accent : Color.lightGray)).get().clicked(() -> {
                                dialog.hide();
                                enterTile(tile.id);
                            });
                        }).growX().fillY();

                        n.table(b -> {
                            b.margin(2);

                            b.button(Icon.pencil, Styles.emptyi, () -> showTileEditor(tile)).size(si).tooltip("@better-blueprints.edit").row();
                            b.button(Icon.trash, Styles.emptyi, () -> {
                                ui.showConfirm("@confirm", "@better-blueprints.delete.confirm", () -> {
                                    store.deleteTile(tile);
                                    rebuild[0].run();
                                });
                            }).size(si).tooltip("@better-blueprints.delete");
                        }).fillY();
                    });

                    next.pack();
                    float w = next.getWidth() + Scl.scl(9f);

                    if(w*2f + sum >= Core.graphics.getWidth() * 0.9f){
                        p.add(current).row();
                        current = new Table();
                        current.left();
                        sum = 0;
                    }

                    current.add(next).minWidth(210).pad(4);

                    sum += w;
                }

                if(sum > 0){
                    p.add(current).row();
                }

                p.table(t -> {
                    t.left().defaults().fillX().height(42f).pad(2);

                    t.button("@better-blueprints.newtile", Icon.add, () -> showNewTile(res -> rebuild[0].run())).wrapLabel(false).get().getLabelCell().padLeft(5);
                });
            };

            resized(true, rebuild[0]);
        }).scrollX(false);
        dialog.show();
    }

    void showNewTile(Cons<String> after){
        ui.showTextInput("@better-blueprints.newtile", "", "", name -> {
            if(name.trim().isEmpty()) return;
            store.newTile(name.trim());
            after.get(name);
            rebuild();
        });
    }

    void showTileEditor(TileEntry tile){
        BaseDialog dialog = new BaseDialog("@better-blueprints.edit");
        TextField[] nameField = {null};
        dialog.cont.pane(scroll -> {
            scroll.top();
            scroll.table(main -> {
                main.center();
                main.table(Tex.pane, inner -> {
                    inner.margin(20f).left().top();

                    inner.add("@name").padRight(8f);
                    nameField[0] = inner.field(tile.name, null).size(340f, 48f).left().get();
                    inner.row();
                    inner.marginTop(14f);

                    Table options = new Table().left().top();
                    inner.add(options).growX().padTop(12f);

                    Runnable[] refresh = {null};
                    refresh[0] = () -> {
                        options.clearChildren();
                        buildTileEditorOptions(dialog, options, tile, refresh[0]);
                    };
                    refresh[0].run();
                }).minWidth(480f);
            }).growX();
        }).grow().scrollX(false);

        dialog.buttons.defaults().size(180f, 56f).pad(4);
        dialog.buttons.button("@ok", Icon.ok, () -> {
            if(!nameField[0].getText().trim().isEmpty()){
                tile.name = nameField[0].getText().trim();
                store.save();
            }
            dialog.hide();
            rebuild();
        });
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide);
        dialog.show();
    }

    void buildTileEditorOptions(BaseDialog dialog, Table p, TileEntry tile, Runnable refresh){
        //width stepper
        p.add("@better-blueprints.width").padRight(8f);
        p.table(w -> {
            w.left();
            w.button("-", Styles.defaultt, () -> {
                tile.w = Math.max(1, tile.w - 1);
                store.save();
                refresh.run();
            }).size(44f).disabled(tile.w <= 1).tooltip("@better-blueprints.decrease");
            w.add(String.valueOf(tile.w)).style(Styles.outlineLabel).minWidth(56f).center();
            w.button("+", Styles.defaultt, () -> {
                tile.w = Math.min(4, tile.w + 1);
                store.save();
                refresh.run();
            }).size(44f).disabled(tile.w >= 4).tooltip("@better-blueprints.increase");
        });
        p.row();

        //height stepper
        p.add("@better-blueprints.height").padRight(8f);
        p.table(h -> {
            h.left();
            h.button("-", Styles.defaultt, () -> {
                tile.h = Math.max(1, tile.h - 1);
                store.save();
                refresh.run();
            }).size(44f).disabled(tile.h <= 1).tooltip("@better-blueprints.decrease");
            h.add(String.valueOf(tile.h)).style(Styles.outlineLabel).minWidth(56f).center();
            h.button("+", Styles.defaultt, () -> {
                tile.h = Math.min(4, tile.h + 1);
                store.save();
                refresh.run();
            }).size(44f).disabled(tile.h >= 4).tooltip("@better-blueprints.increase");
        });
        p.row();

        p.add("@better-blueprints.cover").padRight(8f).padTop(12f);
        p.table(cv -> {
            cv.left();
            cv.button("@better-blueprints.cover.auto", Styles.togglet, () -> {
                tile.coverMode = "auto";
                tile.coverRef = null;
                store.save();
                refresh.run();
            }).checked(tile.coverMode == null || "auto".equals(tile.coverMode)).pad(2).height(40f).minWidth(90f);
            cv.button("@better-blueprints.cover.schematic", Styles.togglet, () -> {
                tile.coverMode = "schematic";
                store.save();
                refresh.run();
            }).checked("schematic".equals(tile.coverMode)).pad(2).height(40f).minWidth(90f);
            cv.button("@better-blueprints.cover.image", Styles.togglet, () -> {
                tile.coverMode = "image";
                store.save();
                refresh.run();
            }).checked("image".equals(tile.coverMode)).pad(2).height(40f).minWidth(90f);
        });
        p.row();

        if("schematic".equals(tile.coverMode)){
            p.table(sel -> {
                sel.left();
                sel.button("@better-blueprints.cover.select", Icon.paste, () -> showCoverSchematicPicker(tile, refresh)).size(260f, 46f);
            }).padTop(12f);
        }else if("image".equals(tile.coverMode)){
            p.table(sel -> {
                sel.left();
                sel.button("@better-blueprints.cover.import", Icon.fileImage, () -> importCoverImage(tile, refresh)).size(260f, 46f);
                if(tile.coverRef != null){
                    sel.button("@better-blueprints.cover.clear", Icon.cancelSmall, () -> {
                        tile.coverMode = "auto";
                        tile.coverRef = null;
                        store.save();
                        refresh.run();
                    }).size(260f, 46f).padLeft(8f);
                }
            }).padTop(12f);
        }
        p.row();

        //live preview at the tile's actual grid size
        p.add(coverElement(tile)).size(tile.w * cell - gap, tile.h * cell - gap).padTop(12f);
        p.row();

        p.button("@better-blueprints.delete", Icon.trash, () -> {
            ui.showConfirm("@confirm", "@better-blueprints.delete.confirm", () -> {
                store.deleteTile(tile);
                dialog.hide();
                rebuild();
            });
        }).size(260f, 46f).padTop(12f);
    }

    void showCoverSchematicPicker(TileEntry tile, Runnable refresh){
        BaseDialog dialog = new BaseDialog("@better-blueprints.cover.select");
        dialog.cont.pane(p -> {
            p.defaults().fillX().height(48f).pad(3);
            for(Schematic s : schematics.all()){
                p.button(s.name(), Styles.flatt, () -> {
                    String name = BlueprintsStore.fileName(s);
                    if(name != null){
                        tile.coverMode = "schematic";
                        tile.coverRef = name;
                        store.save();
                    }
                    dialog.hide();
                    refresh.run();
                });
            }
        }).grow().scrollX(false);
        dialog.addCloseListener();
        dialog.show();
    }

    void importCoverImage(TileEntry tile, Runnable refresh){
        FileChooser.open("png", "jpg", "jpeg").submit(file -> {
            try{
                Fi dir = Core.files.local("better-blueprints/covers/");
                dir.mkdirs();
                Fi dest = dir.child(tile.id + "." + file.extension());
                file.copyTo(dest);
                tile.coverMode = "image";
                tile.coverRef = dest.name();
                store.save();
                refresh.run();
            }catch(Throwable t){
                ui.showException(t);
            }
        });
    }

    //endregion
}
