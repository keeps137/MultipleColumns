package ghostfixture;

import arc.struct.*;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;

/** 配方探针：读宿主当前配方（消费器）→ 折算整批账目模板。 */
public class RecipeProbe {

    public static BatchAccount probe(GhostHostBlock block){
        BatchAccount a = new BatchAccount();
        for(Consume c : block.consumers){
            if(c instanceof ConsumeItems ci){
                for(ItemStack s : ci.items){
                    a.needItems.put(s.item.id, s.amount);
                }
            }else if(c instanceof ConsumeLiquid cl){
                // amount 是每刻用量；整批 = amount × craftTime（craftTime 单位是刻/帧，不是秒）
                a.liquidId = cl.liquid.id;
                a.needLiquid = cl.amount * block.craftTime;
            }
        }
        // 电力：每刻用量 × 单批刻数
        if(block.consPower != null){
            a.powerTotal = block.consPower.usage * block.craftTime;
        }
        // 热量：需热为正（v1 只校验不实扣；v146 无产热字段，产热列以后加）
        if(block.heatRequirement > 0f){
            a.heatTotal = block.heatRequirement;
        }
        if(block.outputItem != null){
            a.outItems.put(block.outputItem.item.id, block.outputItem.amount);
        }
        if(block.outputLiquid != null){
            a.outLiquidId = block.outputLiquid.liquid.id;
            a.outLiquid = block.outputLiquid.amount * block.craftTime; // 逐帧产出累计整批正好 amount × craftTime
        }
        return a;
    }
}
