package com.chengyi.xiewa.save.model;

/**
 * 背包中的一件物品（界面展示与改数量/强化的定位依据）。
 *
 * @param index      在所属数组里的下标（改数量时用它拼路径；穿戴件为 -1）
 * @param id         物品 ID
 * @param name       物品名称
 * @param count      数量（装备恒为 1）
 * @param section    所属分区：{@code items} / {@code materials} / {@code equipment}
 * @param strengthen 强化等级（非装备或未强化为 0）
 * @param part       穿戴部位（身上穿的装备才有，如「武器」；背包件为 null）
 */
public record ItemView(int index, int id, String name, long count, String section,
                       long strengthen, String part) {

    /**
     * 便捷构造：背包/仓库里的普通条目（无穿戴部位、未强化）。
     *
     * @param index   数组下标
     * @param id      物品 ID
     * @param name    名称
     * @param count   数量
     * @param section 分区
     */
    public ItemView(int index, int id, String name, long count, String section) {
        this(index, id, name, count, section, 0, null);
    }

    /**
     * 是否是穿在身上的装备。
     *
     * @return 穿戴件返回 true
     */
    public boolean worn() {
        return part != null;
    }
}
