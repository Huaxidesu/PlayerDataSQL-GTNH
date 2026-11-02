package cc.bukkitPlugin.pds.dmodel.v1_7_10;

import java.lang.reflect.Method;
import java.util.Map;

import cc.bukkitPlugin.commons.nmsutil.NMSUtil;
import cc.bukkitPlugin.pds.PlayerDataSQL;
import cc.bukkitPlugin.pds.util.CPlayer;
import cc.commons.util.reflect.MethodUtil;

/**
 * 旅行背包(AdventureBackpack2)。
 * <p>
 * 背包穿戴后不在玩家背包里，而是存在 Forge 扩展属性 {@code abp.property} 的物品槽 {@code wearable} 中，<br>
 * 背包内的 54 格物品与两个 4000mB 液体槽都在该 ItemStack 的 {@code wearableData} 里。<br>
 * 因此只要整份 {@code BackpackProperty} 对上，背包内容与流体槽就都跟着走。
 * </p>
 * <p>
 * 还原后必须调用 {@code BackpackProperty.sync(EntityPlayer)} 发送 {@code SyncPropertiesPacket}，<br>
 * 否则客户端与周围玩家看不到穿戴中的背包模型。
 * </p>
 */
public class DM_AdventureBackpack extends ADM_InVanilla {

    private static final String PROPERTY_CLASS = "com.darkona.adventurebackpack.playerProperties.BackpackProperty";
    private static final String PROPERTY_NAME = "abp.property";

    /** static void sync(EntityPlayer) */
    private Method method_BackpackProperty_sync;
    /** ItemStack getWearable() */
    private Method method_BackpackProperty_getWearable;

    public DM_AdventureBackpack(PlayerDataSQL pPlugin) {
        super(pPlugin, PROPERTY_CLASS, PROPERTY_NAME);
    }

    @Override
    public String getModelId() {
        return "AdventureBackpack";
    }

    @Override
    public String getDesc() {
        return "旅行背包";
    }

    @Override
    protected boolean initOnce() throws Exception {
        Class.forName("com.darkona.adventurebackpack.AdventureBackpack");
        this.initExProp();

        this.method_BackpackProperty_sync = MethodUtil.getMethod(this.mExPropClazz, "sync", NMSUtil.clazz_EntityPlayer, true);
        this.method_BackpackProperty_getWearable = MethodUtil.getMethod(this.mExPropClazz, "getWearable", true);

        return true;
    }

    /**
     * 未穿戴背包时不写库
     * <p>
     * 属性对象即使没有背包也会写出 {@code forceCampFire} 等字段，直接存会为每个玩家留下无意义的记录。
     * </p>
     */
    @Override
    public byte[] getData(CPlayer pPlayer, Map<String, byte[]> pLoadedData) throws Exception {
        Object tExProp = this.getExProp(pPlayer);
        if (tExProp == null) return new byte[0];
        if (MethodUtil.invokeMethod(this.method_BackpackProperty_getWearable, tExProp) == null) return new byte[0];

        return super.getData(pPlayer, pLoadedData);
    }

    /**
     * 卸下背包后也要同步一次
     * <p>
     * 父类的清空逻辑是"移除属性对象并重新注册"，客户端仍然渲染着旧的穿戴模型，必须补发同步包。
     * </p>
     */
    @Override
    public void cleanData(CPlayer pPlayer) {
        super.cleanData(pPlayer);
        this.syncToAround(pPlayer);
    }

    @Override
    protected void updateToAround(CPlayer pPlayer, Object pExProp) {
        this.syncToAround(pPlayer);
    }

    private void syncToAround(CPlayer pPlayer) {
        Object tNMSPlayer = pPlayer.getNMSPlayer();
        if (tNMSPlayer == null) return;

        MethodUtil.invokeStaticMethod(this.method_BackpackProperty_sync, tNMSPlayer);
    }

}
