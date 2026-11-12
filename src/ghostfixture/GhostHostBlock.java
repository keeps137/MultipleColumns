package ghostfixture;

import arc.graphics.g2d.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.struct.ObjectMap;
import mindustry.content.*;
import mindustry.gen.Building;
import mindustry.type.*;
import mindustry.world.blocks.production.*;

/** 测试宿主：砂×4 + 氢 → 硅×1，需电、需热。本体照常生产，幽灵列叠加其上。 */
public class GhostHostBlock extends HeatCrafter {

    public GhostHostBlock(String name){
        super(name);
        requirements(Category.crafting, ItemStack.with(Items.copper, 30, Items.lead, 20, Items.silicon, 15));

        size = 2; // 与 64x64 贴图匹配（2x2 格）

        // 测试配方（数字只为打通流程，平衡以后调）
        craftTime = 80f;
        outputItem = new ItemStack(Items.silicon, 1);
        craftEffect = Fx.smeltsmoke;
        updateEffect = Fx.none;

        consumeItems(ItemStack.with(Items.sand, 4));
        consumeLiquid(Liquids.hydrogen, 0.2f);
        consumePower(1f);

        hasItems = true;
        hasLiquids = true;

        // 热力：需热 6（v1 只校验不实扣）
        heatRequirement = 6f;

        // 入口：可被选中打开配置栏，幽灵层按钮浮现在配置栏里
        configurable = true;

        // 关键：挂上我们的建筑类型，幽灵列逻辑才会执行
        buildType = GhostHostBuild::new;
    }

    /**
     * 幽灵宿主建筑：本体照常生产（原版逻辑），同时挂一串幽灵列。
     * 列从下往上扫描；上层列开工条件 = 下面所有列都在产中；任何时刻最多一列在攒账。
     * 原版建筑类是绑定方块实例的内部类，所以这里也按内部类声明。
     */
    public class GhostHostBuild extends HeatCrafterBuild {

        public Seq<GhostColumn> columns = new Seq<>();
        public int maxColumns = GhostLayerMod.MAX_COLUMNS;

        @Override
        public void updateTile(){
            super.updateTile(); // 本体照常跑
            GhostSaveChunk.cached.put(pos(), columns);
            // 层叠门控前缀：从底往上数连续在产的列数（O(n) 代替逐列全扫下方）
            int prefixRun = 0;
            while(prefixRun < columns.size
               && columns.get(prefixRun).state == GhostColumn.GhostState.RUNNING){
                prefixRun++;
            }
            for(int i = 0; i < columns.size; i++){
                GhostColumn c = columns.get(i);
                if(c.state == GhostColumn.GhostState.EMPTY) break;
                c.tick(this, i <= prefixRun);
            }
        }

        public boolean canExpand(){
            return columns.size < maxColumns;
        }

        /** 扩列：消耗与本体等价的建造材料（从主核心扣，拍板过的防刷规则）。
         *  v1 不返还：没有删列入口；拆宿主时列费随本体消失（原版只返还本体材料）。 */
        public void addColumn(){
            if(!canExpand()) return;
            Building core = team().core();
            // 没有核心就没法扣费，直接不开列（防白嫖）
            if(core == null) return;
            // 先验后扣，缺一不动账
            for(ItemStack req : requirements){
                if(core.items.get(req.item) < req.amount) return;
            }
            for(ItemStack req : requirements){
                core.items.remove(req.item, req.amount);
            }
            GhostColumn c = new GhostColumn();
            c.startBatch(this);
            columns.add(c);
        }

        /** 入口（方案 A）：选中宿主时，配置栏旁边浮现幽灵层按钮 */
        @Override
        public void buildConfiguration(Table table){
            super.buildConfiguration(table);
            table.button("幽灵层 " + columns.size + "/" + maxColumns,
                () -> GhostLayerMod.instance.panel.show(this));
        }

        /** 拆宿主（非摧毁）：退还未花完的账；摧毁不返还（与原版一致） */
        @Override
        public void onRemoved(){
            super.onRemoved();
            if(!dead()){
                ObjectMap<Item, Integer> refund = new ObjectMap<>();
                for(GhostColumn c : columns){
                    c.collectRefund(refund);
                }
                Building core = team().core();
                if(core != null){
                    for(ObjectMap.Entry<Item, Integer> e : refund){
                        core.items.add(e.key, e.value);
                    }
                }
            }
            GhostSaveChunk.cached.remove(pos());
        }
    }
}
