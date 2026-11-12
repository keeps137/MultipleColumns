package ghostfixture;

import arc.struct.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.type.*;

/** 本批账目：四科目同构，攒账 → 结清 → 批内只花账内 → 完工注销。 */
public class BatchAccount {
    /** 物品需求与已入账（物品id -> 数量） */
    public IntIntMap needItems = new IntIntMap();
    public IntIntMap haveItems = new IntIntMap();

    /** 液体需求与已入账（-1 = 无液体需求；整批总量 = 每秒用量 × 单批时长） */
    public int liquidId = -1;
    public float needLiquid, haveLiquid;

    /** 电力：每刻耗电 × 单批刻数，结清即整批预付（有电池的电网结清时实扣；无电池走富余校验退路） */
    public float powerTotal;
    public boolean powerOk;

    /** 热量（带符号）：需热为正。原版热力无存储，物理上无法预付——结清校验 + 批内逐帧校验 */
    public float heatTotal;
    public boolean heatOk;

    /** 物品产出（整批直塞，上限×2 内放行） */
    public IntIntMap outItems = new IntIntMap();
    /** 液体产出（每刻输出） */
    public int outLiquidId = -1;
    public float outLiquid;

    public boolean allFunded(){
        if(!powerOk || !heatOk) return false;
        if(liquidId >= 0 && haveLiquid < needLiquid - 0.0001f) return false;
        for(IntIntMap.Entry e : needItems){
            if(haveItems.get(e.key, 0) < e.value) return false;
        }
        return true;
    }

    public void write(Writes w){
        w.i(needItems.size);
        for(IntIntMap.Entry e : needItems){
            w.i(e.key);
            w.i(e.value);
        }
        // 已入账部分也要写：否则读档后攒好的账蒸发、在产列退款变零
        w.i(haveItems.size);
        for(IntIntMap.Entry e : haveItems){
            w.i(e.key);
            w.i(e.value);
        }
        w.i(liquidId);
        w.f(needLiquid);
        w.f(haveLiquid);
        w.f(powerTotal);
        w.f(heatTotal);
        w.i(outItems.size);
        for(IntIntMap.Entry e : outItems){
            w.i(e.key);
            w.i(e.value);
        }
        w.i(outLiquidId);
        w.f(outLiquid);
    }

    public void read(Reads r){
        int n = r.i();
        needItems.clear();
        haveItems.clear();
        for(int i = 0; i < n; i++){
            int id = r.i(), amt = r.i();
            needItems.put(id, amt);
        }
        n = r.i();
        for(int i = 0; i < n; i++){
            int id = r.i(), amt = r.i();
            haveItems.put(id, amt);
        }
        liquidId = r.i();
        needLiquid = r.f();
        haveLiquid = r.f();
        powerTotal = r.f();
        heatTotal = r.f();
        n = r.i();
        for(int i = 0; i < n; i++){
            int id = r.i(), amt = r.i();
            outItems.put(id, amt);
        }
        outLiquidId = r.i();
        outLiquid = r.f();
        // 读档后 powerOk/heatOk 需要重新校验
        powerOk = false;
        heatOk = false;
    }

    /** 未花完的账（拆除返还用）：已入账部分 × 剩余比例 */
    public void collectRefund(float refundScale, ObjectMap<Item, Integer> refund){
        if(refundScale <= 0f) return;
        for(IntIntMap.Entry e : haveItems){
            int unspent = (int)Math.floor(e.value * refundScale);
            if(unspent > 0) refund.put(Vars.content.item(e.key), refund.get(Vars.content.item(e.key), 0) + unspent);
        }
        // 液体 v1 暂不返还（记入限制）
    }
}
