package ghostfixture;

import java.io.*;

import arc.math.geom.Point2;
import arc.struct.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.gen.*;
import ghostfixture.GhostHostBlock.GhostHostBuild;
import mindustry.io.*;

/**
 * 存档块：宿主坐标键控，逐列写入基础类型。
 * 格式：版本字节 + 载荷长度 + 载荷；版本不认识就按长度跳过，不弄坏后面模组的存档。
 */
public class GhostSaveChunk implements SaveFileReader.CustomChunk {

    public static final GhostSaveChunk instance = new GhostSaveChunk();
    public static final byte VERSION = 2; // v2：账目新增 haveItems 序列化

    /** pos -> 每帧刷新的列数据（拆除返还与存档共用） */
    public static LongMap<Seq<GhostColumn>> cached = new LongMap<>();

    @Override
    public boolean shouldWrite(){
        return true;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        // 载荷
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Writes payload = new Writes(new DataOutputStream(bos));
        int count = 0;
        for(LongMap.Entry<Seq<GhostColumn>> e : cached){
            if(!e.value.isEmpty()) count++;
        }
        payload.i(count);
        for(LongMap.Entry<Seq<GhostColumn>> e : cached){
            if(e.value.isEmpty()) continue;
            payload.l(e.key);
            payload.i(e.value.size);
            for(GhostColumn c : e.value){
                c.write(payload);
            }
        }
        byte[] bytes = bos.toByteArray();
        Writes w = new Writes(out);
        w.b(VERSION);
        w.i(bytes.length);
        out.write(bytes);
    }

    @Override
    public void read(DataInput in) throws IOException {
        Reads r = new Reads(in);
        byte ver = r.b();
        int len = r.i();
        cached.clear(); // 无论版本认不认识，先清跨存档残留，防止旧局数据写进新档
        if(ver != VERSION){
            // 跳过整个载荷，保证排在后面的模组存档还能读
            in.skipBytes(len);
            return;
        }
        // 实体在自定义块之前已读入，可直接认领
        int hostCount = r.i();
        for(int i = 0; i < hostCount; i++){
            long pos = r.l();
            int colCount = r.i();
            Seq<GhostColumn> cols = new Seq<>();
            for(int j = 0; j < colCount; j++){
                GhostColumn c = new GhostColumn();
                c.read(r);
                if(c.state != GhostColumn.GhostState.EMPTY) cols.add(c);
            }
            if(cols.isEmpty()) continue;
            int packed = (int)pos;
            mindustry.world.Tile t = Vars.world.tile(Point2.x(packed), Point2.y(packed));
            if(t == null || t.build == null) continue; // 坏档/越界坐标：安全丢弃该宿主
            if(t.build instanceof GhostHostBuild){
                ((GhostHostBuild)t.build).columns = cols;
            }
            // 找不到建筑（该列已被安全丢弃）就丢弃，不写回
        }
    }
}
