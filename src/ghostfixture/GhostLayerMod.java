package ghostfixture;

import arc.Events;
import mindustry.game.EventType;
import mindustry.io.SaveVersion;
import mindustry.mod.Mod;

/** 模组入口：注册测试宿主、挂存档块、挂面板刷新。 */
public class GhostLayerMod extends Mod {

    public static GhostLayerMod instance;

    /** 出货锁定上限倍率（可调） */
    public static final float BUFFER_MULTIPLIER = 2f;
    /** 列上限 */
    public static final int MAX_COLUMNS = 100;

    public GhostHostBlock ghostHost;
    public GhostPanel panel;

    public GhostLayerMod(){
        instance = this;
    }

    @Override
    public void loadContent(){
        ghostHost = new GhostHostBlock("ghost-host");
    }

    @Override
    public void init(){
        panel = new GhostPanel();
        // 存档块：块名独立，避免与主模组同名互相覆盖（同名后注册会顶掉先注册）
        SaveVersion.addCustomChunk("ghost-fixture", GhostSaveChunk.instance);
        // 面板低频刷新
        Events.run(EventType.Trigger.update, panel::tick);
        // 新开一局/回主菜单时清掉上一局的列缓存，防止残留进新档
        Events.on(EventType.ResetEvent.class, e -> GhostSaveChunk.cached.clear());
    }
}
