package cc.bukkitPlugin.pds.dmodel.v1_7_10;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

import org.bukkit.inventory.Inventory;

import cc.bukkitPlugin.commons.nmsutil.NMSUtil;
import cc.bukkitPlugin.commons.nmsutil.nbt.NBTUtil;
import cc.bukkitPlugin.pds.PlayerDataSQL;
import cc.bukkitPlugin.pds.dmodel.ADataModel;
import cc.bukkitPlugin.pds.util.CPlayer;
import cc.bukkitPlugin.pds.util.PDSNBTUtil;
import cc.commons.util.FileUtil;
import cc.commons.util.reflect.ClassUtil;
import cc.commons.util.reflect.MethodUtil;

/**
 * 饰品栏(Baubles-Expanded)。
 * <p>
 * GTNH 2.9.0 使用 Baubles-Expanded，槽位数由 {@code BaubleExpandedSlots} 在预初始化阶段动态分配，<br>
 * 常见配置为 18 槽(含心之容器等扩展槽)，而非原版 Baubles 固定的 4 槽。<br>
 * 因此所有与槽位数量相关的遍历都必须取自运行期实例，不能写死常量。
 * </p>
 */
public class DM_Baubles extends ADataModel {

    /** 无法探测到动态槽位时使用的兜底槽位数(原版 Baubles) */
    private static final int DEFAULT_SLOT_COUNT = 4;

    private Method method_BaublesApi_getBaubles = null;
    private Method method_InventoryBaubles_readNBT = null;
    private Method method_InventoryBaubles_saveNBT = null;
    private Method method_InventoryBaubles_syncSlotToClients = null;
    /** static int slotsCurrentlyUsed() ,仅存在于 Baubles-Expanded */
    private Method method_BaubleExpandedSlots_slotsCurrentlyUsed = null;

    /** 初始化阶段探测到的槽位数,仅作为取不到容器实例时的兜底 */
    private int mSlotCount = DEFAULT_SLOT_COUNT;

    private Boolean mInit = null;

    public DM_Baubles(PlayerDataSQL pPlugin) {
        super(pPlugin);
    }

    @Override
    public String getModelId() {
        return "Baubles";
    }

    @Override
    public String getDesc() {
        return "饰品背包";
    }

    @Override
    public boolean initOnce() throws Exception {
        Class<?> tClazz = null;
        Class.forName("baubles.common.Baubles");
        tClazz = Class.forName("baubles.api.BaublesApi");
        this.method_BaublesApi_getBaubles = tClazz.getMethod("getBaubles", NMSUtil.clazz_EntityPlayer);
        tClazz = Class.forName("baubles.common.container.InventoryBaubles");
        this.method_InventoryBaubles_readNBT = MethodUtil.getMethod(tClazz, "readNBT", NBTUtil.clazz_NBTTagCompound, true);
        this.method_InventoryBaubles_saveNBT = MethodUtil.getMethod(tClazz, "saveNBT", NBTUtil.clazz_NBTTagCompound, true);
        this.method_InventoryBaubles_syncSlotToClients = MethodUtil.getMethod(tClazz, "syncSlotToClients", int.class, true);

        this.mSlotCount = this.detectSlotCount();
        return true;
    }

    /**
     * 探测运行期的饰品槽位数
     * <p>
     * 优先使用 Baubles-Expanded 的 {@code BaubleExpandedSlots.slotsCurrentlyUsed()}，<br>
     * 该方法就是构造 {@code InventoryBaubles.stackList} 时使用的槽位数；<br>
     * 原版 Baubles 没有该 API，退回 {@value #DEFAULT_SLOT_COUNT} 槽。
     * </p>
     * 
     * @return 槽位数
     */
    protected int detectSlotCount() {
        try {
            Class<?> tClazz = Class.forName("baubles.api.expanded.BaubleExpandedSlots");
            this.method_BaubleExpandedSlots_slotsCurrentlyUsed = tClazz.getMethod("slotsCurrentlyUsed");
            int tCount = ((Number)this.method_BaubleExpandedSlots_slotsCurrentlyUsed.invoke(null)).intValue();
            if (tCount > 0) return tCount;
        } catch (Throwable ignore) {
            // 原版 Baubles 或 API 变更,使用兜底值
        }
        return DEFAULT_SLOT_COUNT;
    }

    @Override
    public byte[] getData(CPlayer pPlayer, Map<String, byte[]> pLoadedData) throws Exception {
        Object tNMSInv = this.getBaublesNMSInv(pPlayer);
        Object tNBTTag = NBTUtil.newNBTTagCompound();
        MethodUtil.invokeMethod(this.method_InventoryBaubles_saveNBT, tNMSInv, tNBTTag);
        return PDSNBTUtil.compressNBT(tNBTTag);
    }

    @Override
    public void restore(CPlayer pPlayer, byte[] pData) throws Exception {
        this.cleanData(pPlayer);
        Object tNMSInv = this.getBaublesNMSInv(pPlayer);
        MethodUtil.invokeMethod(this.method_InventoryBaubles_readNBT, tNMSInv, PDSNBTUtil.decompressNBT(pData));
        this.updateToAround(pPlayer.getNMSPlayer(), tNMSInv);
    }

    @Override
    public byte[] loadFileData(CPlayer pPlayer, Map<String, byte[]> pLoadedData) throws IOException {
        File tDataFile = this.getUUIDOrNameFile(pPlayer, this.mPlayerDataDir, "%name%.baub");
        if (!tDataFile.isFile()) return new byte[0];

        return FileUtil.readData(tDataFile);
    }

    @Override
    public void cleanData(CPlayer pPlayer) {
        Object tNMSInv = this.getBaublesNMSInv(pPlayer);
        Inventory tInv = this.toBukkitInventory(tNMSInv);
        for (int i = 0; i < tInv.getSize(); i++) {
            tInv.setItem(i, null);
        }
    }

    protected Object getBaublesNMSInv(CPlayer pPlayer) {
        return MethodUtil.invokeStaticMethod(this.method_BaublesApi_getBaubles, pPlayer.getNMSPlayer());
    }

    /**
     * 把饰品容器同步到客户端与周围玩家
     * <p>
     * 必须遍历容器全部槽位：旧实现固定同步前 4 槽，导致第 5 槽及之后的饰品(无线终端、翅膀、心之容器等)<br>
     * 跨服后客户端不渲染、属性也不生效。
     * </p>
     * 
     * @param pNMSPlayer
     *            NMS玩家,保留给子类重写使用
     * @param pBaublesInv
     *            饰品容器(IInventory)
     */
    protected void updateToAround(Object pNMSPlayer, Object pBaublesInv) {
        int tSlotCount = this.getSlotCount(pBaublesInv);
        for (int i = 0; i < tSlotCount; i++) {
            MethodUtil.invokeMethod(this.method_InventoryBaubles_syncSlotToClients, pBaublesInv, i);
        }
    }

    /**
     * 获取饰品容器的实际槽位数
     * 
     * @param pBaublesInv
     *            饰品容器(IInventory)
     * @return 槽位数
     */
    protected int getSlotCount(Object pBaublesInv) {
        if (pBaublesInv != null) {
            try {
                int tSize = this.toBukkitInventory(pBaublesInv).getSize();
                if (tSize > 0) return tSize;
            } catch (Throwable ignore) {
                // 容器包装失败时使用探测值
            }
        }
        return this.mSlotCount;
    }

    protected Inventory toBukkitInventory(Object pBaublesInv) {
        return (Inventory)ClassUtil.newInstance(NMSUtil.clazz_CraftInventory, NMSUtil.clazz_IInventory, pBaublesInv);
    }

}
