package com.chengyi.xiewa.save.model;

import java.util.List;

/**
 * 一个角色卡槽的只读快照。
 *
 * @param slot     卡槽序号（0 起）
 * @param name     角色名
 * @param roleId   角色模板 ID
 * @param level    等级
 * @param bean     功夫豆
 * @param items    道具（背包 items 区）
 * @param materials 材料（背包 materials 区）
 * @param equipment 装备（背包 equipment 区，一件一条）
 */
public record CharacterView(int slot, String name, String roleId, long level, long bean,
                            List<ItemView> items, List<ItemView> materials,
                            List<ItemView> equipment) {

    /**
     * 道具 + 材料 + 装备的合计件数。
     *
     * @return 件数
     */
    public int itemCount() {
        return items.size() + materials.size() + equipment.size();
    }
}
