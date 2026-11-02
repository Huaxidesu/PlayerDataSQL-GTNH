package cc.bukkitPlugin.pds.dmodel.v1_7_10;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import cc.bukkitPlugin.pds.util.ReflectHelper;

/**
 * ServerUtilities(GTNH 2.9.0) 的反射访问入口。
 * <p>
 * 模组类不在编译期依赖里，所有跨模组访问都集中在本类，版本变更时单点修正。<br>
 * {@code serverutils.*} 是模组类，成员名运行期不变，可以按名字查找；<br>
 * {@code net.minecraft.*} 的成员名运行期是 SRG 名，本类不按名字访问它们。
 * </p>
 * <p>
 * 只暴露"个人数据"相关入口。队伍（{@code Universe.teams}/{@code ForgeTeam}）刻意不在这里提供：
 * 队伍关系、权限与领地都是各子服独立维护的对象，跨服覆盖的风险远大于收益。
 * </p>
 * <p>
 * 线程约束：{@code Universe.players} 不是线程安全的，本类只允许在主线程调用。
 * </p>
 */
final class ServerUHelper {

    private static Throwable mInitError = null;
    private static boolean mInited = false;

    static Class<?> clazz_Universe;
    static Class<?> clazz_ForgePlayer;

    static Method method_Universe_getNullable;
    static Method method_Universe_getPlayer;
    static Field field_Universe_ticks;
    static Method method_Ticks_ticks;

    static Method method_ForgePlayer_stats;
    static Method method_ForgePlayer_getLastTimeSeen;
    static Method method_ForgePlayer_markDirty;
    static Field field_ForgePlayer_lastTimeSeen;

    private ServerUHelper() {}

    /**
     * 解析并缓存反射入口,只会真正执行一次
     * 
     * @throws ClassNotFoundException
     *             未安装 ServerUtilities
     * @throws NoSuchMethodException
     *             模组版本不匹配
     */
    static synchronized void init() throws Exception {
        if (mInited) return;
        if (mInitError != null) throw new IllegalStateException(mInitError);

        try {
            clazz_Universe = Class.forName("serverutils.lib.data.Universe");
            clazz_ForgePlayer = Class.forName("serverutils.lib.data.ForgePlayer");

            method_Universe_getNullable = clazz_Universe.getMethod("getNullable");
            method_Universe_getPlayer = clazz_Universe.getMethod("getPlayer", UUID.class);
            field_Universe_ticks = clazz_Universe.getField("ticks");
            method_Ticks_ticks = field_Universe_ticks.getType().getMethod("ticks");

            method_ForgePlayer_stats = clazz_ForgePlayer.getMethod("stats");
            method_ForgePlayer_getLastTimeSeen = clazz_ForgePlayer.getMethod("getLastTimeSeen");
            method_ForgePlayer_markDirty = clazz_ForgePlayer.getMethod("markDirty");
            field_ForgePlayer_lastTimeSeen = clazz_ForgePlayer.getField("lastTimeSeen");

            mInited = true;
        } catch (Throwable exp) {
            mInitError = exp;
            if (exp instanceof ClassNotFoundException) throw (ClassNotFoundException)exp;
            if (exp instanceof NoSuchMethodException) throw (NoSuchMethodException)exp;
            throw new IllegalStateException(exp);
        }
    }

    /** 当前 {@code Universe},服务端未载入时为 null */
    static Object getUniverse() {
        try {
            return method_Universe_getNullable.invoke(null);
        } catch (Throwable exp) {
            return null;
        }
    }

    static Object getPlayer(Object pUniverse, UUID pId) {
        if (pUniverse == null || pId == null) return null;
        return ReflectHelper.invoke(method_Universe_getPlayer, pUniverse, pId);
    }

    /** 当前世界的总刻数,与 {@code ForgePlayer.lastTimeSeen} 同一坐标系 */
    static long getNowTicks(Object pUniverse) {
        Object tTicks = ReflectHelper.get(field_Universe_ticks, pUniverse);
        if (tTicks == null) return 0L;
        return ((Number)ReflectHelper.invoke(method_Ticks_ticks, tTicks)).longValue();
    }

    /** {@code ForgePlayer.stats()} ,返回值是 ServerUtilities 排行榜实际读取的统计对象 */
    static Object getPlayerStats(Object pPlayer) {
        return ReflectHelper.invoke(method_ForgePlayer_stats, pPlayer);
    }

    /** 玩家最后在线刻数,在线时返回当前刻 */
    static long getPlayerLastTimeSeen(Object pPlayer) {
        return ((Number)ReflectHelper.invoke(method_ForgePlayer_getLastTimeSeen, pPlayer)).longValue();
    }

    static void setPlayerLastTimeSeen(Object pPlayer, long pTicks) {
        ReflectHelper.set(field_ForgePlayer_lastTimeSeen, pPlayer, Long.valueOf(pTicks));
    }

    static void markPlayerDirty(Object pPlayer) {
        ReflectHelper.invoke(method_ForgePlayer_markDirty, pPlayer);
    }

}
