/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.annotation.Nonnull
 *  net.minecraft.core.BlockPos
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.item.ItemStack
 *  net.neoforged.neoforge.items.IItemHandler
 */
package com.easycraftchest.block;

import com.easycraftchest.server.storage.CraftChestManager;
import com.easycraftchest.storage.CraftChestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

public final class CraftChestItemHandler
implements IItemHandler {
    private static final int MAX_AUTOMATION_SLOTS = 54;
    private final Mode mode;
    private final ServerLevel level;
    private final BlockPos pos;
    private final CraftChestManager manager;
    private long cachedChangeCounter = Long.MIN_VALUE;
    private String[] cachedKeys = new String[0];
    private final Map<String, ItemStack> templateCache = new HashMap<String, ItemStack>();

    public CraftChestItemHandler(Mode mode, ServerLevel level, BlockPos pos) {
        this.mode = mode;
        this.level = level;
        this.pos = pos;
        this.manager = CraftChestManager.get(level);
    }

    private void ensureIndex(CraftChestData storage) {
        long cc = storage.getChangeCounter();
        if (cc == this.cachedChangeCounter) {
            return;
        }
        List<String> order = storage.getItemOrder();
        this.cachedKeys = order.toArray(new String[0]);
        this.cachedChangeCounter = cc;
        this.templateCache.clear();
    }

    private ItemStack templateFor(CraftChestData storage, String itemKey) {
        ItemStack cached = this.templateCache.get(itemKey);
        if (cached != null) {
            return cached;
        }
        ItemStack st = storage.createItemStackFromKey(itemKey);
        if (st.isEmpty()) {
            this.templateCache.put(itemKey, ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        st.setCount(1);
        this.templateCache.put(itemKey, st);
        return st;
    }

    public int getSlots() {
        if (this.mode == Mode.INSERT_ONLY) {
            return 1;
        }
        CraftChestData storage = this.manager.getStorage(this.pos);
        if (storage == null) {
            return 1;
        }
        this.ensureIndex(storage);
        return Math.max(1, Math.min(54, this.cachedKeys.length));
    }

    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        if (this.mode == Mode.INSERT_ONLY) {
            return ItemStack.EMPTY;
        }
        CraftChestData storage = this.manager.getStorage(this.pos);
        if (storage == null) {
            return ItemStack.EMPTY;
        }
        this.ensureIndex(storage);
        int limit = Math.min(54, this.cachedKeys.length);
        if (slot < 0 || slot >= limit) {
            return ItemStack.EMPTY;
        }
        String itemKey = this.cachedKeys[slot];
        long available = storage.getItemCount(itemKey);
        if (available <= 0L) {
            return ItemStack.EMPTY;
        }
        ItemStack template = this.templateFor(storage, itemKey);
        if (template.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack out = template.copy();
        int count = (int)Math.min(Math.min(available, (long)out.getMaxStackSize()), Integer.MAX_VALUE);
        out.setCount(count);
        return out;
    }

    @Nonnull
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        CraftChestData storage = this.manager.getOrCreateStorage(this.pos, this.level);
        if (storage == null) {
            return stack;
        }
        if (!simulate) {
            long added = storage.addItem(stack);
            this.manager.setDirty();
            if (added >= (long)stack.getCount()) {
                return ItemStack.EMPTY;
            }
            ItemStack remaining = stack.copy();
            remaining.setCount((int)((long)stack.getCount() - added));
            return remaining;
        }
        return ItemStack.EMPTY;
    }

    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (this.mode == Mode.INSERT_ONLY) {
            return ItemStack.EMPTY;
        }
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        CraftChestData storage = this.manager.getStorage(this.pos);
        if (storage == null) {
            return ItemStack.EMPTY;
        }
        this.ensureIndex(storage);
        int limit = Math.min(54, this.cachedKeys.length);
        if (slot < 0 || slot >= limit) {
            return ItemStack.EMPTY;
        }
        String itemKey = this.cachedKeys[slot];
        long available = storage.getItemCount(itemKey);
        if (available <= 0L) {
            return ItemStack.EMPTY;
        }
        ItemStack template = this.templateFor(storage, itemKey);
        if (template.isEmpty()) {
            return ItemStack.EMPTY;
        }
        long extractAmount = Math.min((long)amount, available);
        if ((extractAmount = Math.min(extractAmount, (long)template.getMaxStackSize())) <= 0L) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            ItemStack extracted = storage.removeItem(itemKey, extractAmount);
            this.manager.setDirty();
            return extracted;
        }
        ItemStack simulated = template.copy();
        simulated.setCount((int)extractAmount);
        return simulated;
    }

    public int getSlotLimit(int slot) {
        return 64;
    }

    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return true;
    }

    public static enum Mode {
        INSERT_ONLY,
        FULL;

    }
}

