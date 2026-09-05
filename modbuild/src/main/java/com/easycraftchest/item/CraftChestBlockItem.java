/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.component.DataComponents
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.network.chat.Component
 *  net.minecraft.world.item.BlockItem
 *  net.minecraft.world.item.Item$Properties
 *  net.minecraft.world.item.Item$TooltipContext
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.TooltipFlag
 *  net.minecraft.world.item.component.CustomData
 *  net.minecraft.world.level.block.Block
 */
package com.easycraftchest.item;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

public class CraftChestBlockItem
extends BlockItem {
    public CraftChestBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return;
        }
        CompoundTag storageTag = customData.copyTag();
        if (!storageTag.contains("StoredItems")) {
            return;
        }
        CompoundTag itemsTag = storageTag.getCompound("StoredItems");
        long totalItems = 0L;
        int totalTypes = 0;
        for (String key : itemsTag.getAllKeys()) {
            long count = itemsTag.getLong(key);
            if (count <= 0L) continue;
            ++totalTypes;
            if (Long.MAX_VALUE - totalItems < count) {
                totalItems = Long.MAX_VALUE;
                continue;
            }
            totalItems += count;
        }
        if (totalTypes <= 0) {
            return;
        }
        tooltipComponents.add((Component)Component.translatable((String)"tooltip.easycraftchest.storage_summary", (Object[])new Object[]{totalItems, totalTypes}));
    }
}

