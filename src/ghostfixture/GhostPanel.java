package ghostfixture;

import arc.Core;
import arc.graphics.g2d.*;
import arc.scene.event.Touchable;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import ghostfixture.GhostHostBlock.GhostHostBuild;
import mindustry.ui.Styles;

/** 幽灵层面板：宿主配置栏按钮唤起，逐列显示状态与账目，可扩列。 */
public class GhostPanel {

    public GhostHostBuild target;
    public Table holder;
    public Table content;
    int frame;

    public GhostPanel(){
        holder = new Table(Styles.black6);
        holder.visible = false;
        holder.touchable = Touchable.enabled;
        holder.margin(12f);

        content = new Table();
        holder.add(content).grow();
        holder.pack();

        Core.scene.add(holder);
    }

    public void show(GhostHostBuild host){
        target = host;
        rebuild();
        holder.visible = true;
        holder.pack(); // 先按内容算好首选尺寸
        holder.setSize(Math.min(620f, Core.graphics.getWidth() - 60f), Math.min(700f, Core.graphics.getHeight() - 120f));
        holder.setPosition(
            (Core.graphics.getWidth() - holder.getWidth()) / 2f,
            (Core.graphics.getHeight() - holder.getHeight()) / 2f
        );
    }

    public void hide(){
        holder.visible = false;
        target = null;
    }

    void rebuild(){
        content.clear();
        if(target == null){
            hide();
            return;
        }
        content.add("幽灵层 · 宿主面板").left().padBottom(8f).row();

        if(target.columns.isEmpty()){
            content.add("还没有幽灵列，点下面「扩列」开工。").left().pad(8f).color(Pal.lightishGray).row();
        }
        for(int i = 0; i < target.columns.size; i++){
            GhostColumn c = target.columns.get(i);
            int idx = i;
            content.table(row -> {
                row.add("列 #" + (idx + 1)).width(70f).left();
                row.add(stateName(c.state)).width(70f).left();
                if(c.state == GhostColumn.GhostState.EMPTY){
                    row.add("虚位 —— 可扩列占用").left().color(Pal.lightishGray);
                }else{
                    String detail = "";
                    if(c.state == GhostColumn.GhostState.RUNNING || c.state == GhostColumn.GhostState.EJECTING){
                        detail = "进度 " + (int)(c.progress * 100f) + "%";
                    }
                    if(c.state == GhostColumn.GhostState.EJECTING){
                        detail += " · 出货锁定：等腾缓冲";
                    }
                    row.add(detail).left();
                    row.add(accountText(c)).right().color(Pal.lightishGray);
                }
            }).growX().padBottom(4f).row();
        }

        content.table(foot -> {
            foot.button("+ 扩列（耗等价材料，从核心扣）", () -> {
                target.addColumn();
                rebuild();
            }).growX();
            foot.add(" " + target.columns.size + " / " + target.maxColumns).padLeft(8f);
            foot.button("关闭", this::hide).padLeft(8f);
        }).growX().padTop(8f).row();

        content.add("列从下往上排队：上层开工 = 下面全部在产；断供只停筹备列（无总闸门）。")
            .left().color(Pal.lightishGray).padTop(6f).row();
    }

    String stateName(GhostColumn.GhostState s){
        switch(s){
            case EMPTY: return "空列";
            case FUNDING: return "筹备";
            case RUNNING: return "在产";
            case EJECTING: return "出货锁定";
        }
        return s.toString();
    }

    String accountText(GhostColumn c){
        BatchAccount a = c.account;
        StringBuilder sb = new StringBuilder();
        for(IntIntMap.Entry e : a.needItems){
            if(sb.length() > 0) sb.append(' ');
            int have = a.haveItems.get(e.key, 0);
            sb.append(Vars.content.item(e.key).name).append(' ').append(have).append('/').append(e.value);
        }
        if(a.liquidId >= 0){
            if(sb.length() > 0) sb.append(' ');
            sb.append(Vars.content.liquid(a.liquidId).name).append(' ').append((int)a.haveLiquid).append('/').append((int)a.needLiquid);
        }
        sb.append(" 电").append(a.powerOk ? "√" : "…");
        sb.append(" 热").append(a.heatOk ? "√" : "…");
        return sb.toString();
    }

    /** 面板打开时低频刷新进度 */
    public void tick(){
        if(!holder.visible) return;
        // 回主菜单或宿主失效（被拆/被炸）时自动收起
        if(Vars.state.isMenu() || target == null || !target.isValid()){
            hide();
            return;
        }
        if(++frame % 12 == 0) rebuild();
    }
}
