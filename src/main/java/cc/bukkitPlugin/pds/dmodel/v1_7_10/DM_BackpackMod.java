package cc.bukkitPlugin.pds.dmodel.v1_7_10;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import cc.bukkitPlugin.commons.Log;
import cc.bukkitPlugin.pds.PlayerDataSQL;
import cc.bukkitPlugin.pds.dmodel.ADataModel;
import cc.bukkitPlugin.pds.util.CPlayer;
import cc.bukkitPlugin.pds.util.DataHelper;
import cc.bukkitPlugin.pds.util.PDSNBTUtil;
import cc.bukkitPlugin.pds.util.ReflectHelper;

/**
 * 独立背包模组(Minecraft-Backpack-Mod)。
 * <p>
 * 该模组的背包内容不在物品 NBT 里：物品只带一个 {@code backpack-UID}，实际数据保存在<br>
 * {@code world/backpacks/backpacks/<UID>.dat}；玩家的快捷键个人背包槽保存在<br>
 * {@code world/backpacks/player/<玩家UUID>.dat}。跨服只搬物品会得到一个空背包。
 * </p>
 * <p>
 * GTNH 版本的该模组用 {@code SaveFileHandler} 做内存缓存，只在世界存档时批量落盘，<br>
 * 且读文件时优先返回缓存。因此这里必须走它的 {@code loadBackpack}/{@code saveBackpack}/{@code loadPlayer}/{@code savePlayer}，<br>
 * 直接读写 {@code .dat} 会被内存缓存覆盖。
 * </p>
 */
public class DM_BackpackMod extends ADataModel {

    private static final byte DATA_VERSION = 1;
    /** 物品 NBT 中保存背包数据文件名的标签 */
    private static final String TAG_BACKPACK_UID = "backpack-UID";
    /**
     * UID 白名单：只放行字母、数字、下划线与短横线。
     * <p>UID 来自物品 NBT，会被传给 {@code SaveFileHandler} 用作存档文件名，
     * 校验可以拒绝伪造 UID 中的路径分隔符，避免越出背包存档目录。</p>
     */
    private static final Pattern UID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** static Object CraftItemStack.asNMSCopy(ItemStack) */
    private Method method_CraftItemStack_asNMSCopy;
    /** static String NBTItemStackUtil.getString(ItemStack,String) */
    private Method method_NBTItemStackUtil_getString;
    /** Backpack.saveFileHandler */
    private Object mSaveFileHandler;
    private Method method_SaveFileHandler_loadBackpack;
    private Method method_SaveFileHandler_saveBackpack;
    private Method method_SaveFileHandler_loadPlayer;
    private Method method_SaveFileHandler_savePlayer;
    private Method method_SaveFileHandler_backpackSaveExists;
    private Method method_SaveFileHandler_queueDirtyFiles;
    /** PlayerSave(EntityPlayer) */
    private Constructor<?> ctor_PlayerSave;
    private Method method_PlayerSave_getPersonalBackpack;

    public DM_BackpackMod(PlayerDataSQL pPlugin) {
        super(pPlugin);
    }

    @Override
    public String getModelId() {
        return "MinecraftBackpack";
    }

    @Override
    public String getDesc() {
        return "背包模组\n独立 .dat 存档的背包与个人背包槽";
    }

    @Override
    protected boolean initOnce() throws Exception {
        Class<?> tBackpackClazz = Class.forName("de.eydamos.backpack.Backpack");
        Field tField = tBackpackClazz.getField("saveFileHandler");
        this.mSaveFileHandler = tField.get(null);

        Class<?> tHandlerClazz = tField.getType();
        Class<?> tNBTClazz = Class.forName("net.minecraft.nbt.NBTTagCompound");
        this.method_SaveFileHandler_loadBackpack = tHandlerClazz.getMethod("loadBackpack", String.class);
        this.method_SaveFileHandler_saveBackpack = tHandlerClazz.getMethod("saveBackpack", tNBTClazz, String.class);
        this.method_SaveFileHandler_loadPlayer = tHandlerClazz.getMethod("loadPlayer", String.class);
        this.method_SaveFileHandler_savePlayer = tHandlerClazz.getMethod("savePlayer", tNBTClazz, String.class);
        try {
            this.method_SaveFileHandler_backpackSaveExists = tHandlerClazz.getMethod("backpackSaveExists", String.class);
            this.method_SaveFileHandler_queueDirtyFiles = tHandlerClazz.getMethod("queueDirtyFiles");
        } catch (Throwable ignore) {
            // 旧版本没有这两个方法,退化为"总是读取、等世界存档落盘"
        }

        Class<?> tUtilClazz = Class.forName("de.eydamos.backpack.util.NBTItemStackUtil");
        this.method_NBTItemStackUtil_getString = tUtilClazz.getMethod("getString",
                Class.forName("net.minecraft.item.ItemStack"), String.class);

        String tCraftPackage = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> tCraftItemStack = Class.forName(tCraftPackage + ".inventory.CraftItemStack");
        this.method_CraftItemStack_asNMSCopy = tCraftItemStack.getMethod("asNMSCopy", ItemStack.class);

        Class<?> tPlayerSaveClazz = Class.forName("de.eydamos.backpack.saves.PlayerSave");
        this.ctor_PlayerSave = tPlayerSaveClazz.getConstructor(Class.forName("net.minecraft.entity.player.EntityPlayer"));
        this.method_PlayerSave_getPersonalBackpack = tPlayerSaveClazz.getMethod("getPersonalBackpack");

        return true;
    }

    @Override
    public byte[] getData(CPlayer pPlayer, Map<String, byte[]> pLoadedData) throws Exception {
        Player tPlayer = pPlayer.getPlayer();
        if (tPlayer == null) return new byte[0];

        Set<String> tUIDs = new LinkedHashSet<>();
        this.collectBackpackUIDs(tPlayer, tUIDs);
        this.collectPersonalBackpackUID(pPlayer, tUIDs);

        Map<String, byte[]> tFiles = new LinkedHashMap<>();
        for (String sUID : tUIDs) {
            byte[] tFile = this.readBackpackFile(sUID);
            if (tFile != null && tFile.length != 0) tFiles.put(sUID, tFile);
        }

        ByteArrayOutputStream tBAOStream = new ByteArrayOutputStream();
        DataOutputStream tOut = new DataOutputStream(tBAOStream);
        tOut.writeByte(DATA_VERSION);
        tOut.writeInt(tFiles.size());
        for (Map.Entry<String, byte[]> sEntry : tFiles.entrySet()) {
            tOut.writeUTF(sEntry.getKey());
            DataHelper.writeBytes(tOut, sEntry.getValue());
        }

        byte[] tPlayerFile = this.readPlayerFile(pPlayer.getUniqueId().toString());
        boolean tHasPlayerFile = tPlayerFile != null && tPlayerFile.length != 0;
        tOut.writeBoolean(tHasPlayerFile);
        if (tHasPlayerFile) DataHelper.writeBytes(tOut, tPlayerFile);

        tOut.flush();
        return tBAOStream.toByteArray();
    }

    @Override
    public void restore(CPlayer pPlayer, byte[] pData) throws Exception {
        if (pData == null || pData.length == 0) return;

        DataInputStream tIn = new DataInputStream(new ByteArrayInputStream(pData));
        byte tVersion = tIn.readByte();
        if (tVersion != DATA_VERSION) {
            Log.warn("背包数据版本为 " + tVersion + ",当前插件只支持 " + DATA_VERSION + ",跳过还原");
            return;
        }

        int tCount = tIn.readInt();
        for (int i = 0; i < tCount; i++) {
            String tUID = tIn.readUTF();
            if (!UID_PATTERN.matcher(tUID).matches()) {
                Log.warn("跳过非法背包 UID: " + tUID);
                DataHelper.readBytes(tIn);
                continue;
            }
            Object tNBT = PDSNBTUtil.decompressNBT(DataHelper.readBytes(tIn));
            ReflectHelper.invoke(this.method_SaveFileHandler_saveBackpack, this.mSaveFileHandler, tNBT, tUID);
        }

        if (tIn.readBoolean()) {
            Object tNBT = PDSNBTUtil.decompressNBT(DataHelper.readBytes(tIn));
            ReflectHelper.invoke(this.method_SaveFileHandler_savePlayer, this.mSaveFileHandler, tNBT,
                    pPlayer.getUniqueId().toString());
        }

        // 立即排队落盘,避免目标服在下次世界存档前崩服导致这批背包数据只存在于内存
        if (this.method_SaveFileHandler_queueDirtyFiles != null) {
            ReflectHelper.invoke(this.method_SaveFileHandler_queueDirtyFiles, this.mSaveFileHandler);
        }
        // 已知限制：目标服上同玩家旧 UID 的 .dat 不会被删除（SaveFileHandler 无枚举/删除接口），
        // 孤儿文件仅占用磁盘；见 doc/程序结构.md §9。
    }

    /**
     * 清空玩家的个人背包槽存档
     * <p>
     * 背包物品本身由原版数据模块还原，这里只清个人背包槽，避免上一位玩家的背包残留在本服。
     * </p>
     */
    @Override
    public void cleanData(CPlayer pPlayer) {
        Object tEmpty = PDSNBTUtil.decompressNBT(new byte[0]);
        ReflectHelper.invoke(this.method_SaveFileHandler_savePlayer, this.mSaveFileHandler, tEmpty,
                pPlayer.getUniqueId().toString());
    }

    /** 扫描玩家背包与装备栏中的背包物品 */
    private void collectBackpackUIDs(Player pPlayer, Set<String> pUIDs) {
        PlayerInventory tInv = pPlayer.getInventory();
        this.collectBackpackUIDs(tInv.getContents(), pUIDs);
        this.collectBackpackUIDs(tInv.getArmorContents(), pUIDs);
    }

    private void collectBackpackUIDs(ItemStack[] pItems, Set<String> pUIDs) {
        if (pItems == null) return;
        for (ItemStack sItem : pItems) {
            if (sItem == null) continue;
            String tUID = this.getBackpackUID(sItem);
            if (tUID != null) pUIDs.add(tUID);
        }
    }

    /** 个人背包槽里的背包同样只是一个带 UID 的物品，内容也需要同步 */
    private void collectPersonalBackpackUID(CPlayer pPlayer, Set<String> pUIDs) {
        Object tNMSPlayer = pPlayer.getNMSPlayer();
        if (tNMSPlayer == null) return;

        try {
            Object tPlayerSave = ReflectHelper.construct(this.ctor_PlayerSave, tNMSPlayer);
            Object tPersonal = ReflectHelper.invoke(this.method_PlayerSave_getPersonalBackpack, tPlayerSave);
            if (tPersonal == null) return;

            String tUID = (String)ReflectHelper.invoke(this.method_NBTItemStackUtil_getString, null, tPersonal,
                    TAG_BACKPACK_UID);
            if (tUID != null && UID_PATTERN.matcher(tUID).matches()) pUIDs.add(tUID);
        } catch (Throwable exp) {
            Log.warn("读取 " + pPlayer.getName() + " 的个人背包槽失败: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
        }
    }

    /**
     * 取出物品携带的背包数据标识
     * <p>
     * 只有背包物品带 {@code backpack-UID}，普通物品读到空串即视为非背包，<br>
     * 因此不需要再反射 {@code ItemStack.getItem()}(运行期是 SRG 名)。
     * </p>
     * 
     * @return 背包数据文件标识,非背包物品返回 null
     */
    private String getBackpackUID(ItemStack pItem) {
        try {
            Object tNMSItem = ReflectHelper.invoke(this.method_CraftItemStack_asNMSCopy, null, pItem);
            if (tNMSItem == null) return null;

            String tUID = (String)ReflectHelper.invoke(this.method_NBTItemStackUtil_getString, null, tNMSItem,
                    TAG_BACKPACK_UID);
            return tUID != null && UID_PATTERN.matcher(tUID).matches() ? tUID : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private byte[] readBackpackFile(String pUID) {
        try {
            if (this.method_SaveFileHandler_backpackSaveExists != null) {
                Object tExists = ReflectHelper.invoke(this.method_SaveFileHandler_backpackSaveExists,
                        this.mSaveFileHandler, pUID);
                if (!Boolean.TRUE.equals(tExists)) return new byte[0];
            }
            // loadBackpack 缓存未命中时会读盘，而本方法运行在主线程（见 getUserData 的线程约束），
            // 这里记录慢调用便于用实测数据决定是否需要异步预载（doc/开发计划书.md §5）。
            long tStart = System.nanoTime();
            Object tNBT = ReflectHelper.invoke(this.method_SaveFileHandler_loadBackpack, this.mSaveFileHandler, pUID);
            long tCostMs = (System.nanoTime() - tStart) / 1_000_000L;
            if (tCostMs > 20) Log.warn("loadBackpack(" + pUID + ") 耗时 " + tCostMs + "ms(主线程,缓存未命中)");
            return PDSNBTUtil.compressNBT(tNBT);
        } catch (Throwable exp) {
            Log.warn("读取背包数据 " + pUID + " 失败: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
            return new byte[0];
        }
    }

    private byte[] readPlayerFile(String pUUID) {
        try {
            long tStart = System.nanoTime();
            Object tNBT = ReflectHelper.invoke(this.method_SaveFileHandler_loadPlayer, this.mSaveFileHandler, pUUID);
            long tCostMs = (System.nanoTime() - tStart) / 1_000_000L;
            if (tCostMs > 20) Log.warn("loadPlayer(" + pUUID + ") 耗时 " + tCostMs + "ms(主线程,缓存未命中)");
            return PDSNBTUtil.compressNBT(tNBT);
        } catch (Throwable exp) {
            Log.warn("读取个人背包数据失败: " + exp.getLocalizedMessage());
            if (Log.isDebug()) Log.severe(exp);
            return new byte[0];
        }
    }

}
