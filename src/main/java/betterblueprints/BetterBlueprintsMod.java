package betterblueprints;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Timer;
import arc.util.Time;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.BetterSchematicsDialog;

import java.io.PrintWriter;
import java.io.StringWriter;

import static mindustry.Vars.headless;
import static mindustry.Vars.ui;

/** Replaces the vanilla blueprint browser with the tile-based {@link BetterSchematicsDialog}. */
public class BetterBlueprintsMod extends Mod{

    @Override
    public void init(){
        diag("init called, headless=" + headless + ", ui=" + ui);
        if(headless) return;

        if(ui != null && ui.schematics != null){
            replace();
        }else{
            Events.on(ClientLoadEvent.class, event -> replace());
        }

        //Some host builds (MindustryX and friends) recreate or reset Vars.ui.schematics after mod
        //init, or reload mods while running. Keep re-asserting the replacement for a while.
        Events.on(ClientLoadEvent.class, event -> {
            for(float delay : new float[]{1f, 3f, 10f, 30f}){
                Timer.schedule(this::replace, delay);
            }
        });
    }

    private void replace(){
        try{
            if(ui == null || ui.schematics == null || ui.schematics instanceof BetterSchematicsDialog){
                diag("replace skip: ui.schematics=" + (ui == null ? "null-ui" : ui.schematics == null ? "null" : ui.schematics.getClass().getName()));
                return;
            }
            ui.schematics = new BetterSchematicsDialog();
            diag("replaced: now " + ui.schematics.getClass().getName());
            Log.info("[BetterBlueprints] blueprint browser replaced with tile view");
        }catch(Throwable t){
            diag("replace failed: " + t, t);
            Log.err("[BetterBlueprints] failed to replace blueprint browser", t);
        }
    }

    /** Writes diagnostics to the mod data folder so runtime issues can be reported without console access. */
    private static void diag(String msg){
        diag(msg, null);
    }

    private static void diag(String msg, Throwable t){
        try{
            Fi file = Core.files.local("better-blueprints.log");
            StringBuilder sb = new StringBuilder();
            sb.append(Time.millis()).append(" [").append(Thread.currentThread().getName()).append("] ").append(msg).append('\n');
            if(t != null){
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            file.writeString(sb.toString(), true);
        }catch(Throwable ignored){
        }
    }
}
