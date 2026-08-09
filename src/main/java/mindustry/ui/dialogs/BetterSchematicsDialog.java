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
    private Table floatingTile;
    private ScrollPane tilePane;
    private Table gridTable;
    private final Vec2 tmp = new Vec2();
    /** Simulated grid positions of every tile during a drag (drag preview / tile shoving). */
    private final ObjectMap<TileEntry, int[]> dragPreview = new ObjectMap<>();
    /** Placeholder entries for the fixed tiles so drag logic treats them like any tile. */
    private final TileEntry fixedAll = new TileEntry();
    private final TileEntry fixedUncat = new TileEntry();
    private final TileEntry fixedNew = new TileEntry();

    /** One entry of the tile grid: a fixed tile, a user tile or the empty drag placeholder. */
    static class GridSlot{
        final TileEntry tile;
        final String fixed;
        final int w, h;
        final boolean placeholder;
        int gx, gy;

        GridSlot(TileEntry tile, String fixed, int w, int h, boolean placeholder){
            this.tile = tile;
            this.fixed = fixed;
            this.w = w;
            this.h = h;
            this.placeholder = placeholder;
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
        //skyline columns: 1 cell = one 200px column; tiles may span several columns
        return Math.max((int)(Core.graphics.getWidth() / Scl.scl(unit)), 1);
    }

    void buildTileGrid(Table root){
        Cell<ScrollPane> cell = root.pane(p -> {
            p.top().left();
            gridTable = new Table();
            gridTable.top().left();
            p.add(gridTable).top().left();
            buildGrid();
        }).grow().scrollX(false);
        tilePane = cell.get();
    }

    /**
     * Virtual-grid placement: every slot is drawn at its own grid cell position
     * ({@code gx * cell, gy * cell}); tiles keep their positions when dragged elsewhere and may
     * leave empty cells between them. Returns each slot's pixel rect (top-left origin, y grows
     * downward). Shared by the real grid build and the drag simulation.
     */
    static void simulateLayout(Seq<GridSlot> slots, int cols, Seq<Rect> out){
        int n = slots.size;
        while(out.size < n) out.add(new Rect());

        for(int i = 0; i < n; i++){
            GridSlot s = slots.get(i);
            Rect r = out.get(i);
            r.set(s.gx * cell, s.gy * cell, s.w * cell - gap, s.h * cell - gap);
        }
        out.size = n;
    }

    /** Grid slots in display order: fixed tiles at their stored cells, user tiles at theirs. */
    Seq<GridSlot> buildSlots(Seq<TileEntry> users){
        materializePositions();
        Seq<GridSlot> slots = new Seq<>();
        slots.add(fixedSlot(TILE_ALL, fixedAll, 2, 1));
        for(TileEntry t : users){
            slots.add(tileSlot(t, dragPreview.get(t)));
        }
        slots.add(fixedSlot(TILE_UNCAT, fixedUncat, 2, 1));
        slots.add(fixedSlot(TILE_NEW, fixedNew, 1, 1));
        return slots;
    }

    GridSlot fixedSlot(String id, TileEntry holder, int w, int h){
        GridSlot s = new GridSlot(holder, id, w, h, false);
        int[] p = dragPreview.get(holder);
        s.gx = p != null ? p[0] : holder.gx;
        s.gy = p != null ? p[1] : holder.gy;
        return s;
    }

    /** User-tile slot at its stored position, or the drag-preview position while dragging. */
    GridSlot tileSlot(TileEntry t, int[] preview){
        GridSlot s = new GridSlot(t, null, t.w, t.h, false);
        s.gx = preview != null ? preview[0] : t.gx;
        s.gy = preview != null ? preview[1] : t.gy;
        return s;
    }

    /** Slots while dragging: the dragged tile is replaced by an empty placeholder at its target cell. */
    Seq<GridSlot> buildDragSlots(){
        Seq<GridSlot> slots = buildSlots(store.data.tiles);
        //the dragged tile itself is floating; draw the placeholder at the target position instead
        Seq<GridSlot> out = new Seq<>();
        for(GridSlot s : slots){
            if(s.tile == dragTile) continue;
            out.add(s);
        }
        GridSlot ph = new GridSlot(null, null, dragTile.w, dragTile.h, true);
        ph.gx = dragGx;
        ph.gy = dragGy;
        out.add(ph);
        return out;
    }

    /**
     * Assigns grid cells to tiles that don't have one yet (new tiles / legacy data): in list
     * order, each tile is placed on the first free cell that fits, scanning row by row. The
     * fixed tiles get stable auto cells: All = (0,0), Uncategorized and the new-tile button
     * fill the next free spots. Results are persisted back into the data.
     */
    void materializePositions(){
        boolean changed = false;
        int ncols = cols();

        //fixed All tile
        if(store.data.allGx < 0 || store.data.allGy < 0){
            store.data.allGx = 0;
            store.data.allGy = 0;
            changed = true;
        }
        fixedAll.gx = store.data.allGx;
        fixedAll.gy = store.data.allGy;
        fixedAll.w = 2;
        fixedAll.h = 1;

        Seq<TileEntry> placed = new Seq<>();
        placed.add(fixedAll);

        for(TileEntry t : store.data.tiles){
            if(t.gx >= 0 && t.gy >= 0){
                placed.add(t);
                continue;
            }
            int[] cell = firstFree(placed, ncols, t.w, t.h);
            t.gx = cell[0];
            t.gy = cell[1];
            placed.add(t);
            changed = true;
        }

        if(store.data.uncatGx < 0 || store.data.uncatGy < 0){
            int[] cell = firstFree(placed, ncols, 2, 1);
            store.data.uncatGx = cell[0];
            store.data.uncatGy = cell[1];
            changed = true;
        }
        fixedUncat.gx = store.data.uncatGx;
        fixedUncat.gy = store.data.uncatGy;
        fixedUncat.w = 2;
        fixedUncat.h = 1;
        placed.add(fixedUncat);

        if(store.data.newGx < 0 || store.data.newGy < 0){
            int[] cell = firstFree(placed, ncols, 1, 1);
            store.data.newGx = cell[0];
            store.data.newGy = cell[1];
            changed = true;
        }
        fixedNew.gx = store.data.newGx;
        fixedNew.gy = store.data.newGy;
        fixedNew.w = 1;
        fixedNew.h = 1;

        if(changed) store.save();
    }

    /** Scans the grid row by row for the first cell where a w x h tile fits without overlapping. */
    static int[] firstFree(Seq<TileEntry> placed, int ncols, int w, int h){
        int limit = Math.max(16, placed.size * 4 + 8);
        for(int gy = 0; gy < limit; gy++){
            for(int gx = 0; gx + w <= ncols; gx++){
                if(!overlapsAny(placed, gx, gy, w, h)) return new int[]{gx, gy};
            }
        }
        return new int[]{0, 0};
    }

    static boolean overlapsAny(Seq<TileEntry> placed, int gx, int gy, int w, int h){
        for(TileEntry t : placed){
            if(overlap(gx, gy, w, h, t.gx, t.gy, t.w, t.h)) return true;
        }
        return false;
    }

    static boolean overlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh){
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /** Rebuilds only the tile grid table (keeps the search row and dialog chrome untouched). */
    void buildGrid(){
        if(gridTable == null) return;
        gridTable.clearChildren();

        Seq<GridSlot> slots = dragActive ? buildDragSlots() : buildSlots(store.data.tiles);
        Seq<Rect> rects = new Seq<>();
        simulateLayout(slots, cols(), rects);

        float maxW = 0f, maxH = 0f;
        for(int i = 0; i < slots.size; i++){
            GridSlot s = slots.get(i);
            Rect r = rects.get(i);

            Element e;
            if(s.placeholder){
                Table ph = new Table();
                ph.touchable = Touchable.disabled;
                e = ph;
            }else if(s.fixed != null){
                e = addFixedTile(s.fixed, holderFor(s.fixed), r.width, r.height);
            }else{
                e = addUserTile(s.tile, r.width, r.height);
            }

            //absolute placement: tiles butt against each other, skyline-style
            e.setBounds(r.x, r.y, r.width, r.height);
            gridTable.addChild(e);
            maxW = Math.max(maxW, r.x + r.width);
            maxH = Math.max(maxH, r.y + r.height);
        }

        //drive the pane content size through a transparent spacer so scrolling still works
        Element spacer = new Element();
        spacer.touchable = Touchable.disabled;
        gridTable.add(spacer).size(maxW, maxH);
        gridTable.setSize(maxW, maxH);
        gridTable.validate();
    }

    /** The draggable placeholder entry backing a fixed tile. */
    TileEntry holderFor(String id){
        if(TILE_ALL.equals(id)) return fixedAll;
        if(TILE_UNCAT.equals(id)) return fixedUncat;
        return fixedNew;
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
                    nameBar(Core.bundle.get("better-blueprints.all"), w),
                    badge(schematics.all().size)
                ).grow();
            }, () -> enterTile(TILE_ALL), holder);
        }else if(TILE_UNCAT.equals(id)){
            return addTileWidget(w, h, b -> {
                b.stack(
                    new Table(n -> {
                        n.center();
                        n.image(Icon.folder).size(64f);
                    }),
                    nameBar(Core.bundle.get("better-blueprints.uncat"), w),
                    badge(countUncat())
                ).grow();
            }, () -> enterTile(TILE_UNCAT), holder);
        }else{
            return addTileWidget(w, h, b -> {
                b.center();
                b.image(Icon.add).size(48f);
                b.row();
                b.add("@better-blueprints.newtile").color(Color.lightGray).padTop(8f);
            }, () -> showNewTile(s -> {}), holder);
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
        }, () -> enterTile(tile.id), tile);
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
     * absolutely). User tiles ({@code dragSource != null}) get a drag listener: a pointer press
     * records the drag start, the actual drag loop runs in the floating tile's per-frame update
     * (robust against the scroll pane cancelling touch focus when it starts panning).
     */
    Element addTileWidget(float w, float h, Cons<Table> content, Runnable click, TileEntry dragSource){
        Button[] sel = {null};
        Button button = new Button(Styles.flati);
        button.top();
        button.margin(0f);
        content.get(button);
        sel[0] = button;
        button.setSize(w, h);

        button.clicked(() -> {
            if(sel[0].childrenPressed()) return;
            if(dragActive) return; //a drag was in progress: release must not navigate
            click.run();
        });
        button.getStyle().up = Tex.pane;

        if(dragSource != null){
            button.addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(dragPointer != -1) return false;
                    if(button != null && button != KeyCode.mouseLeft) return false;
                    //ignore presses starting on the pencil / badge area
                    if(sel[0].childrenPressed()) return false;
                    beginDrag(pointer, dragSource);
                    return false;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
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

    void beginDrag(int pointer, TileEntry tile){
        dragPointer = pointer;
        dragTile = tile;
        dragActive = false;
        dragStartX = Core.input.mouseX(pointer);
        dragStartY = Core.input.mouseY(pointer);

        //semi-transparent copy of the tile (cover + name bar) that follows the pointer; kept
        //off-screen until the drag threshold is crossed. Its update() polls the input so the
        //drag works even after the scroll pane steals touch focus.
        floatingTile = new Table();
        floatingTile.touchable = Touchable.disabled;
        floatingTile.top();
        floatingTile.margin(0f);
        floatingTile.setBackground(Tex.pane);
        floatingTile.stack(
            coverElement(tile),
            nameBar(tile.name, tile.w * cell - gap)
        ).size(tile.w * cell - gap, tile.h * cell - gap);
        floatingTile.setSize(tile.w * cell - gap, tile.h * cell - gap);
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
            //dominant drag direction decides how displaced tiles get shoved
            dragDirX = Math.abs(dx) >= Math.abs(dy) ? Math.signum(dx) : 0f;
            dragDirY = Math.abs(dy) > Math.abs(dx) ? Math.signum(dy) : 0f;
            if(dragDirX == 0f && dragDirY == 0f) dragDirX = 1f;
            int[] start = computeDragTarget(mx, my);
            dragGx = start[0];
            dragGy = start[1];
            if(tilePane != null) tilePane.setScrollingDisabled(true, true);
            simulateMove();
            buildGrid();
        }

        //floating tile follows the pointer, centered on it, in dialog-local coordinates
        tmp.set(mx, my);
        stageToLocalCoordinates(tmp);
        floatingTile.setPosition(tmp.x - floatingTile.getWidth() / 2f, tmp.y - floatingTile.getHeight() / 2f);

        int[] target = computeDragTarget(mx, my);
        if(target[0] != dragGx || target[1] != dragGy){
            dragGx = target[0];
            dragGy = target[1];
            simulateMove();
            buildGrid();
        }
    }

    /**
     * Pointer to target grid cell: the dragged tile's top-left snaps so its center follows the
     * pointer; the target is clamped inside the grid.
     */
    int[] computeDragTarget(float mx, float my){
        tmp.set(mx, my);
        gridTable.stageToLocalCoordinates(tmp);
        float left = tmp.x - dragTile.w * unit / 2f;
        float top = gridTable.getHeight() - tmp.y - dragTile.h * unit / 2f;
        int tgx = Math.round(left / cell);
        int tgy = Math.round(top / cell);
        tgx = Math.max(0, Math.min(tgx, Math.max(0, cols() - dragTile.w)));
        tgy = Math.max(0, tgy);
        return new int[]{tgx, tgy};
    }

    /**
     * Simulates the grid state while dragging: the dragged tile is placed at its target cell and
     * every tile it overlaps is moved to the nearest free cell along the drag direction. Only
     * directly displaced tiles move (no cascading rows); results go into {@link #dragPreview}
     * (stored positions are untouched until the drop).
     */
    void simulateMove(){
        dragPreview.clear();
        Seq<TileEntry> all = new Seq<>(store.data.tiles);
        all.add(fixedAll);
        all.add(fixedUncat);
        all.add(fixedNew);
        for(TileEntry t : all){
            dragPreview.put(t, new int[]{t.gx, t.gy});
        }
        int[] dp = dragPreview.get(dragTile);
        dp[0] = dragGx;
        dp[1] = dragGy;

        //only tiles overlapped by the dragged tile move; each goes to the nearest free cell
        for(TileEntry t : all){
            if(t == dragTile) continue;
            int[] pt = dragPreview.get(t);
            if(!overlap(dp[0], dp[1], dragTile.w, dragTile.h, pt[0], pt[1], t.w, t.h)) continue;

            int[] slot = findFreeSlot(t, pt[0], pt[1], dragDirX, dragDirY);
            if(slot != null){
                pt[0] = slot[0];
                pt[1] = slot[1];
            }
        }
    }

    /** Nearest cell along the drag direction that fits this tile without overlapping any preview tile. */
    int[] findFreeSlot(TileEntry t, int gx, int gy, float dirX, float dirY){
        for(int i = 1; i <= 32; i++){
            int nx = gx + Math.round(dirX * i);
            int ny = gy + Math.round(dirY * i);
            if(dirX != 0f) nx = Math.max(0, Math.min(nx, Math.max(0, cols() - t.w)));
            ny = Math.max(0, ny);
            if(!overlapsInPreview(t, nx, ny)) return new int[]{nx, ny};
        }
        return null;
    }

    boolean overlapsInPreview(TileEntry self, int gx, int gy){
        for(ObjectMap.Entry<TileEntry, int[]> e : dragPreview){
            if(e.key == self) continue;
            int[] p = e.value;
            if(overlap(gx, gy, self.w, self.h, p[0], p[1], e.key.w, e.key.h)) return true;
        }
        return false;
    }

    /** Finishes the drag: applies the simulated positions, restores scrolling, rebuilds. */
    void endDrag(){
        if(dragPointer < 0) return;

        if(dragActive){
            boolean changed = false;
            for(TileEntry t : store.data.tiles){
                int[] p = dragPreview.get(t);
                if(p != null && (p[0] != t.gx || p[1] != t.gy)){
                    t.gx = p[0];
                    t.gy = p[1];
                    changed = true;
                }
            }
            int[] pa = dragPreview.get(fixedAll);
            if(pa != null && (pa[0] != store.data.allGx || pa[1] != store.data.allGy)){
                store.data.allGx = pa[0];
                store.data.allGy = pa[1];
                changed = true;
            }
            int[] pu = dragPreview.get(fixedUncat);
            if(pu != null && (pu[0] != store.data.uncatGx || pu[1] != store.data.uncatGy)){
                store.data.uncatGx = pu[0];
                store.data.uncatGy = pu[1];
                changed = true;
            }
            int[] pn = dragPreview.get(fixedNew);
            if(pn != null && (pn[0] != store.data.newGx || pn[1] != store.data.newGy)){
                store.data.newGx = pn[0];
                store.data.newGy = pn[1];
                changed = true;
            }
            if(changed) store.save();
            if(tilePane != null) tilePane.setScrollingDisabled(false, false);
            dragActive = false;
        }
        cancelDrag();
        rebuildView();
    }

    /** Drops all drag state without applying or rebuilding. */
    void cancelDrag(){
        dragPointer = -1;
        dragActive = false;
        dragTile = null;
        dragGx = 0;
        dragGy = 0;
        dragPreview.clear();
        if(floatingTile != null){
            floatingTile.remove();
            floatingTile = null;
        }
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
