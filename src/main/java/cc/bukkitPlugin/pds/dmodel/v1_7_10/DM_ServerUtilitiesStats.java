package cc.bukkitPlugin.pds.dmodel.v1_7_10;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import cc.bukkitPlugin.commons.Log;
import cc.bukkitPlugin.pds.PlayerDataSQL;
import cc.bukkitPlugin.pds.dmodel.ADataModel;
import cc.bukkitPlugin.pds.util.CPlayer;
import cc.bukkitPlugin.pds.util.DataHelper;
import cc.bukkitPlugin.pds.util.ReflectHelper;

/**
 * ServerUtilities 统计与排行榜数据。
 * <p>
 * ServerUtilities 的排行榜是纯动态计算的：榜单值直接读 {@code ForgePlayer.stats()} 返回的<br>
 * {@code StatisticsFile} 内存数据，外加 {@code ForgePlayer.lastTimeSeen}(最后在线)。<br>
 * 所以只要保证这两处与源服一致，{@code /leaderboard} 的每个榜单就自然一致。
 * </p>
 * <p>
 * 只同步个人数据：队伍（{@code ForgeTeam} / 成员关系 / 权限 / 领地）不归本插件管。<br>
 * 队伍是各子服独立维护的对象，领地又绑定各服的世界坐标，跨服覆盖会把权限与领地判定一起带歪。
 * </p>
 * <p>
 * 严禁直接改写 {@code world/stats/<uuid>.json}：{@code StatisticsFile} 只在构造时读盘，<br>
 * 之后一直以内存副本为准并会在存档时回写，绕过内存写盘会被下一次回写覆盖。
 * 因此这里通过 {@code StatisticsFile} 自己的 JSON 序列化/反序列化方法直接改内存。
 * </p>
 * <p>
 * {@code lastTimeSeen} 是世界刻数，各子服的世界刻基准不同，不能直接搬运，<br>
 * 因此序列化的是"距今多少刻"，还原时再折算成目标服的时间轴。
 * </p>
 */
public class DM_ServerUtilitiesStats extends ADataModel {

    private static final byte DATA_VERSION = 1;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** StatisticsFile 的统计缓存 Map 字段 */
    private Field field_Stats_map;
    /** String f(Map) 把统计缓存序列化成 JSON */
    private Method method_Stats_dump;
    /** Map f(String) 把 JSON 解析成统计缓存 */
    private Method method_Stats_load;
    /** 定位失败后不再重试,避免每个玩家每次保存都刷日志 */
    private boolean mStatAccessorUsable = true;
    /**
     * 最近一次成功序列化的数据
     * <p>
     * 关服时 Forge 先派发 {@code FMLServerStoppingEvent}（此时 {@code Universe} 已被置空），<br>
     * 本插件随后才在 {@code onDisable} 里保存在线玩家，没有后备处理会把统计数据写成空值。
     * </p>
     * <p>按写入顺序淘汰，上限 512 份，避免长期运行时无界增长。</p>
     */
    private final Map<UUID, byte[]> mLastData = Collections.synchronizedMap(new LinkedHashMap<UUID, byte[]>(64, 0.75f, false) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, byte[]> pEldest) {
            return this.size() > 512;
        }
    });

    public DM_ServerUtilitiesStats(PlayerDataSQL pPlugin) {
        super(pPlugin);
    }

    @Override
    public String getModelId() {
        return "ServerUStats";
    }

    @Override
    public String getDesc() {
        return "ServerUtilities统计与排行榜\n含在线时长、死亡、AFK与最后在线时间";
    }

    @Override
    protected boolean initOnce() throws Exception {
        ServerUHelper.init();
        this.detectStatAccessor(ServerUHelper.method_ForgePlayer_stats.getReturnType());
        return true;
    }

    /**
     * 定位统计缓存的读写入口
     * <p>
     * {@code StatisticsFile} 与其父类 {@code StatFileWriter} 的成员名在运行期是 SRG 名，<br>
     * 因此按签名查找：{@code String f(Map)} 为序列化，{@code Map f(String)} 为反序列化。<br>
     * 定位失败不影响模块启用，只是退化为"仅同步最后在线时间"。
     * </p>
     */
    private void detectStatAccessor(Class<?> pStatsClazz) {
        for (Class<?> sClazz = pStatsClazz; sClazz != null && sClazz != Object.class; sClazz = sClazz.getSuperclass()) {
            for (Field sField : sClazz.getDeclaredFields()) {
                if (Modifier.isStatic(sField.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(sField.getType())) continue;
                sField.setAccessible(true);
                if (this.field_Stats_map == null) this.field_Stats_map = sField;
            }

            for (Method sMethod : sClazz.getDeclaredMethods()) {
                if (Modifier.isStatic(sMethod.getModifiers())) continue;
                Class<?>[] tParams = sMethod.getParameterTypes();
                if (tParams.length != 1) continue;

                if (this.method_Stats_dump == null && sMethod.getReturnType() == String.class
                        && Map.class.isAssignableFrom(tParams[0])) {
                    sMethod.setAccessible(true);
                    this.method_Stats_dump = sMethod;
                } else if (this.method_Stats_load == null && Map.class.isAssignableFrom(sMethod.getReturnType())
                        && tParams[0] == String.class) {
                    sMethod.setAccessible(true);
                    this.method_Stats_load = sMethod;
                }
            }
        }

        if (this.field_Stats_map == null || this.method_Stats_dump == null || this.method_Stats_load == null) {
            this.mStatAccessorUsable = false;
            Log.warn("未能定位 " + pStatsClazz.getSimpleName() + " 的统计缓存读写方法,跨服只同步最后在线时间");
        } else {
            Log.info("统计缓存定位成功: " + pStatsClazz.getSimpleName()
                    + " field=" + this.field_Stats_map.getName()
                    + ", dump=" + this.method_Stats_dump.getName()
                    + ", load=" + this.method_Stats_load.getName());
        }
    }

    @Override
    public byte[] getData(CPlayer pPlayer, Map<String, byte[]> pLoadedData) throws Exception {
        Object tUniverse = ServerUHelper.getUniverse();
        if (tUniverse == null) return this.getLastData(pPlayer.getUniqueId());

        Object tPlayer = ServerUHelper.getPlayer(tUniverse, pPlayer.getUniqueId());
        if (tPlayer == null) return this.getLastData(pPlayer.getUniqueId());

        long tAge = Math.max(0L, ServerUHelper.getNowTicks(tUniverse) - ServerUHelper.getPlayerLastTimeSeen(tPlayer));

        ByteArrayOutputStream tBAOStream = new ByteArrayOutputStream();
        DataOutputStream tOut = new DataOutputStream(tBAOStream);
        tOut.writeByte(DATA_VERSION);
        tOut.writeLong(tAge);
        DataHelper.writeBytes(tOut, this.dumpStats(tPlayer).getBytes(UTF_8));
        tOut.flush();

        byte[] tData = tBAOStream.toByteArray();
        this.mLastData.put(pPlayer.getUniqueId(), tData);
        return tData;
    }

    private byte[] getLastData(UUID pId) {
        byte[] tData = pId == null ? null : this.mLastData.get(pId);
        return tData == null ? new byte[0] : tData;
    }

    @Override
    public void restore(CPlayer pPlayer, byte[] pData) throws Exception {
        if (pData == null || pData.length == 0) return;

        Object tUniverse = ServerUHelper.getUniverse();
        if (tUniverse == null) return;

        Object tPlayer = ServerUHelper.getPlayer(tUniverse, pPlayer.getUniqueId());
        if (tPlayer == null) return;

        DataInputStream tIn = new DataInputStream(new ByteArrayInputStream(pData));
        byte tVersion = tIn.readByte();
        if (tVersion != DATA_VERSION) {
            Log.warn("统计数据版本为 " + tVersion + ",当前插件只支持 " + DATA_VERSION + ",跳过还原");
            return;
        }

        long tAge = tIn.readLong();
        String tStatsJson = new String(DataHelper.readBytes(tIn), UTF_8);

        if (!tStatsJson.isEmpty()) this.loadStats(tPlayer, tStatsJson);

        ServerUHelper.setPlayerLastTimeSeen(tPlayer, Math.max(0L, ServerUHelper.getNowTicks(tUniverse) - tAge));
        ServerUHelper.markPlayerDirty(tPlayer);
    }

    /**
     * 清空玩家的统计缓存
     * <p>
     * 框架自 v1.3.0 起不再在进服时调用本方法；还原路径的清空由 {@link #loadStats}
     * 内部对目标 Map 执行 {@code clear + putAll} 完成，本方法保留为公开契约。
     * </p>
     */
    @Override
    public void cleanData(CPlayer pPlayer) {
        if (!this.mStatAccessorUsable) return;

        Object tUniverse = ServerUHelper.getUniverse();
        if (tUniverse == null) return;

        Object tPlayer = ServerUHelper.getPlayer(tUniverse, pPlayer.getUniqueId());
        if (tPlayer == null) return;

        try {
            Object tStats = ServerUHelper.getPlayerStats(tPlayer);
            Map<?, ?> tMap = (Map<?, ?>)ReflectHelper.get(this.field_Stats_map, tStats);
            if (tMap != null) tMap.clear();
        } catch (Throwable exp) {
            Log.severe("清空统计缓存失败: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
        }
    }

    private String dumpStats(Object pPlayer) {
        if (!this.mStatAccessorUsable) return "";
        try {
            Object tStats = ServerUHelper.getPlayerStats(pPlayer);
            Map<?, ?> tMap = (Map<?, ?>)ReflectHelper.get(this.field_Stats_map, tStats);
            Object tJson = ReflectHelper.invoke(this.method_Stats_dump, tStats, tMap);
            if (tJson instanceof String) {
                String tJsonStr = (String)tJson;
                // 一致性校验：签名匹配到的成员若不是真正的统计缓存，序列化结果会与缓存状态矛盾，
                // 此时降级为只同步最后在线时间，避免把错误数据搬去目标服。
                boolean tMapEmpty = tMap == null || tMap.isEmpty();
                if (tJsonStr.trim().startsWith("{") && tMapEmpty == "{}".equals(tJsonStr.trim())) {
                    return tJsonStr;
                }
            }

            this.mStatAccessorUsable = false;
            Log.warn("统计序列化结果与缓存状态不一致,跨服只同步最后在线时间");
        } catch (Throwable exp) {
            this.mStatAccessorUsable = false;
            Log.warn("读取统计缓存失败,跨服只同步最后在线时间: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private void loadStats(Object pPlayer, String pJson) {
        if (!this.mStatAccessorUsable) return;
        try {
            Object tStats = ServerUHelper.getPlayerStats(pPlayer);
            Map<Object, Object> tTarget = (Map<Object, Object>)ReflectHelper.get(this.field_Stats_map, tStats);
            Object tParsed = ReflectHelper.invoke(this.method_Stats_load, tStats, pJson);
            if (!(tParsed instanceof Map) || tParsed == tTarget) return;

            Map<Object, Object> tParsedMap = (Map<Object, Object>)tParsed;
            // 输入非空但解析结果为空说明成员定位有误，放弃覆盖以保护目标服现有数据
            if (tParsedMap.isEmpty() && !pJson.trim().isEmpty() && !pJson.trim().equals("{}")) {
                Log.severe("统计反序列化得到空 Map,放弃覆盖以保护现有数据");
                return;
            }

            tTarget.clear();
            tTarget.putAll(tParsedMap);
        } catch (Throwable exp) {
            Log.severe("还原统计缓存失败: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
        }
    }

}
