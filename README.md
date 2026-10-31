# PlayerDataSQL-GTNH

> **源仓库声明**：本项目源自爱聪聪（Imcc）开源的 [PlayerDataSQL](https://gitee.com/Imcc/PlayerDataSQL)。  
> 本仓库针对 Minecraft 1.7.10 (GTNH / Forge 混合服) 进行维护与二次开发。

---

+ 本插件部分结构参照自梦梦的 [PlayerSQL](https://github.com/caoli5288/PlayerSQL/)
+ 插件特点为备份数据模块化，数据表只有一张
+ 当前支持跨服的数据有：神秘时代4、饰品栏、匠魂、时装工坊、血魔法、超能力、舰娘、巫术、魔法艺术2 以及原版数据（包含成就、状态等完整属性）
+ 可通过实现 [IDataModel](src/main/java/cc/bukkitPlugin/pds/api/IDataModel.java) 接口并注册来扩展更多 Mod 的跨服数据同步
+ 原作者构建历史参考：[Jenkins](https://ci.xjboss.net/job/PlayerDataSQL/)（已失效）
