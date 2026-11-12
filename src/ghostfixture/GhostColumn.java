package ghostfixture;

import arc.struct.*;
import mindustry.*;
import ghostfixture.GhostHostBlock.GhostHostBuild;
import mindustry.type.*;
import mindustry.world.blocks.power.PowerGraph;

/**
 * 一条幽灵列。状态机：空列 / 筹备 / 在产 / 出货（可锁定）。
 * 每批一条虚拟账目：攒账 → 结清（一次付清）→ 批内只花账内 → 完工注销。
 * 超速等额继承：进度按宿主实时加速值推进（Building.delta() 已含 timeScale），账目总量不变。
 */
public class GhostColumn {

    public enum GhostState {
        EMPTY, FUNDING, RUNNING, EJECTING
    }

    /** 筹备期每帧从真实来源拉进账的量（小步攒账） */
    public static final int ITEMS_PER_TICK = 4;
    public static final float LIQUID_PER_TICK = 4f;

    public GhostState state = GhostState.EMPTY;
    public BatchAccount account = new BatchAccount();
    public float progress;

    /** 开新批：探针读宿主当前配方，折算账目模板 */
    public void startBatch(GhostHostBuild host){
        account = RecipeProbe.probe((GhostHostBlock)host.block);
        progress = 0f;
        state = GhostState.FUNDING;
    }

    /** 每帧推进。gate = 下面所有列都在产中（层叠门控，天然串行） */
    public void tick(GhostHostBuild host, boolean gate){
        GhostHostBlock block = (GhostHostBlock)host.block;
        switch(state){
            case FUNDING:
                if(gate) fundTick(host);
                break;
            case RUNNING:
                // 热：原版热力无存储，物理上无法预付——批内逐帧校验，不够就停（唯一批内仍有实时约束的科目）
                if(account.heatTotal > 0f && host.heat < account.heatTotal) break;
                progress += host.delta() / block.craftTime;
                if(progress >= 1f){
                    progress = 1f;
                    state = GhostState.EJECTING;
                    tryEject(host);
                }
                break;
            case EJECTING:
                tryEject(host);
                break;
        }
    }

    /** 筹备：四科目按速率攒账，全清即开工 */
    void fundTick(GhostHostBuild host){
        BatchAccount a = account;
        // 电力：有电池的电网要求存量足够（结清时实扣）；无电池退回"富余校验"退路（拍板过的退路方案）
        if(!a.powerOk){
            if(a.powerTotal <= 0f){
                a.powerOk = true;
            }else if(host.power != null){
                PowerGraph g = (PowerGraph)host.power.graph;
                if(g.getBatteryCapacity() > 0f){
                    a.powerOk = g.getBatteryStored() > a.powerTotal + 1f;
                }else{
                    a.powerOk = g.getPowerBalance() >= 0f;
                }
            }else{
                a.powerOk = true; // 宿主未接电网：无网可查，按退路放行
            }
        }
        // 热量：只校验不实扣（原版热力无存储无消耗纯加减法）
        if(!a.heatOk){
            a.heatOk = a.heatTotal <= 0f || host.heat >= a.heatTotal;
        }
        // 物品：逐帧小步从宿主缓冲拉进账
        for(IntIntMap.Entry e : a.needItems){
            int have = a.haveItems.get(e.key, 0);
            if(have >= e.value) continue;
            Item item = Vars.content.item(e.key);
            int take = Math.min(e.value - have, ITEMS_PER_TICK);
            if(host.items.get(item) >= take){
                host.items.remove(item, take);
                a.haveItems.put(e.key, have + take);
            }
        }
        // 液体：同款小步拉
        if(a.liquidId >= 0 && a.haveLiquid < a.needLiquid - 0.0001f){
            Liquid liq = Vars.content.liquid(a.liquidId);
            float take = Math.min(a.needLiquid - a.haveLiquid, LIQUID_PER_TICK);
            if(host.liquids.get(liq) >= take){
                host.liquids.remove(liq, take);
                a.haveLiquid += take;
            }
        }
        if(a.allFunded()){
            // 结清前复核热：攒账期热Ok是锁存的，热刚好在结清前掉下去就退回继续攒，不让电费白压
            if(a.heatTotal > 0f && host.heat < a.heatTotal){
                a.heatOk = false;
                return;
            }
            // 电费实付：有电池的电网在结清那一刻真扣一次整批能量
            if(a.powerTotal > 0f && host.power != null){
                PowerGraph g = (PowerGraph)host.power.graph;
                if(g.getBatteryCapacity() > 0f){
                    if(g.getBatteryStored() > a.powerTotal + 1f){
                        g.useBatteries(a.powerTotal);
                    }else{
                        a.powerOk = false; // 结清前电池被抽走：结清失败，继续攒
                        return;
                    }
                }
                // 无电池电网：退路方案，不实扣（已在攒账时校验富余）
            }
            state = GhostState.RUNNING;
            progress = 0f;
        }
    }

    /** 出货（带锁）：够就一次塞入回筹备开新批；不够锁在出货，进度和账一分不少，每帧重试 */
    void tryEject(GhostHostBuild host){
        BatchAccount a = account;
        GhostHostBlock block = (GhostHostBlock)host.block;
        // 先检查：液体有没有地方收
        if(a.outLiquidId >= 0 && a.outLiquid > 0f){
            Liquid out = Vars.content.liquid(a.outLiquidId);
            if(host.liquids.get(out) + a.outLiquid > block.liquidCapacity + 0.0001f){
                return; // 液体收不下，锁定等
            }
        }
        // 再检查：物品塞进去会不会爆（现有量 + 产出 ≤ 上限×2，允许超本体上限）
        int total = host.items.total();
        for(IntIntMap.Entry e : a.outItems){
            total += e.value;
        }
        if(total > block.itemCapacity * GhostLayerMod.BUFFER_MULTIPLIER){
            return; // 锁定等腾空间
        }
        // 两头都放得下，才真正塞入（顺序：先查后塞，避免重试时重复入账）
        if(a.outLiquidId >= 0 && a.outLiquid > 0f){
            host.liquids.add(Vars.content.liquid(a.outLiquidId), a.outLiquid);
        }
        for(IntIntMap.Entry e : a.outItems){
            host.items.add(Vars.content.item(e.key), e.value);
        }
        // 塞净，注销本批，回筹备开下一批
        startBatch(host);
    }

    /** 拆宿主返还：退账内没花完的份 */
    public void collectRefund(ObjectMap<Item, Integer> refund){
        switch(state){
            case FUNDING:
                // 还没结清：退已拉进账的部分
                account.collectRefund(1f, refund);
                break;
            case RUNNING:
                // 已结清、跑到一半：按剩余进度比例退
                account.collectRefund(1f - progress, refund);
                break;
            default:
                // 出货中：整批已付清，无可退
                break;
        }
        state = GhostState.EMPTY;
    }

    public void write(arc.util.io.Writes w){
        w.b(state.ordinal());
        w.f(progress);
        account.write(w);
    }

    public void read(arc.util.io.Reads r){
        state = GhostState.values()[r.b()];
        progress = r.f();
        account.read(r);
        if(state == GhostState.FUNDING && account.allFunded()) state = GhostState.RUNNING;
    }
}
