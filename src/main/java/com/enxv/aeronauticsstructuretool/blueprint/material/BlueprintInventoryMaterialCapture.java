package com.enxv.aeronauticsstructuretool.blueprint.material;

import com.enxv.aeronauticsstructuretool.BlueprintMaterialSummary;
import com.enxv.aeronauticsstructuretool.blueprint.geometry.PlotBlockTransform;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BlueprintInventoryMaterialCapture {
    private static final String PLOT_CHUNKS_TAG = "chunks";
    private static final String PLOT_BLOCK_ENTITIES_TAG = "block_entities";

    private BlueprintInventoryMaterialCapture() {
    }

    static Map<String, Long> captureBlockEntity(ServerLevel level, BlockEntity blockEntity) {
        if (level == null || blockEntity == null) {
            return Map.of();
        }
        if (blockEntity instanceof Container container) {
            return captureContainer(container, level.registryAccess());
        }

        IItemHandler unsided = getCapability(level, blockEntity, null);
        if (unsided != null) {
            return captureHandler(unsided, level.registryAccess());
        }

        Map<String, Long> visibleFromAnySide = new LinkedHashMap<>();
        for (Direction direction : Direction.values()) {
            IItemHandler sided = getCapability(level, blockEntity, direction);
            if (sided == null) {
                continue;
            }
            Map<String, Long> sidedCounts = captureHandler(sided, level.registryAccess());
            for (Map.Entry<String, Long> entry : sidedCounts.entrySet()) {
                visibleFromAnySide.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return visibleFromAnySide;
    }

    public static Map<String, Long> captureHandler(IItemHandler handler, HolderLookup.Provider registries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (handler == null || registries == null) {
            return counts;
        }
        int slots = handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            try {
                addStack(handler.getStackInSlot(slot), registries, counts);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("failed to capture item handler slot " + slot, exception);
            }
        }
        return counts;
    }

    static Map<String, Long> positiveDifference(Map<String, Long> actual, Map<String, Long> serialized) {
        Map<String, Long> difference = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : actual.entrySet()) {
            long missingFromNbt = entry.getValue() - serialized.getOrDefault(entry.getKey(), 0L);
            if (missingFromNbt > 0L) {
                difference.put(entry.getKey(), missingFromNbt);
            }
        }
        return difference;
    }

    static void mergeInto(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
                target.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
    }

    public static Map<String, Long> captureAdditionalLiveItems(
            ServerLevel level,
            PlotBlockTransform transform,
            CompoundTag plotTag
    ) {
        Map<BlockPos, CompoundTag> serializedByPosition = new LinkedHashMap<>();
        CompoundTag chunks = plotTag.getCompound(PLOT_CHUNKS_TAG);
        for (String chunkKey : chunks.getAllKeys()) {
            ListTag blockEntities = chunks.getCompound(chunkKey)
                    .getList(PLOT_BLOCK_ENTITIES_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < blockEntities.size(); i++) {
                CompoundTag blockEntityTag = blockEntities.getCompound(i);
                serializedByPosition.put(
                        new BlockPos(
                                blockEntityTag.getInt("x"),
                                blockEntityTag.getInt("y"),
                                blockEntityTag.getInt("z")
                        ),
                        blockEntityTag
                );
            }
        }

        Map<String, Long> additionalItems = new LinkedHashMap<>();
        for (BlockEntity blockEntity : transform.findBlockEntities(level)) {
            Map<String, Long> liveItems = captureBlockEntity(level, blockEntity);
            if (liveItems.isEmpty()) {
                continue;
            }
            CompoundTag serializedTag = serializedByPosition.get(blockEntity.getBlockPos());
            Map<String, Long> serializedItems = serializedTag == null
                    ? Map.of()
                    : BlueprintMaterialSummary.captureItemCounts(serializedTag);
            mergeInto(
                    additionalItems,
                    positiveDifference(liveItems, serializedItems)
            );
        }
        return additionalItems;
    }

    private static Map<String, Long> captureContainer(Container container, HolderLookup.Provider registries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        int slots = container.getContainerSize();
        for (int slot = 0; slot < slots; slot++) {
            try {
                addStack(container.getItem(slot), registries, counts);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("failed to capture container slot " + slot, exception);
            }
        }
        return counts;
    }

    private static IItemHandler getCapability(ServerLevel level, BlockEntity blockEntity, Direction direction) {
        try {
            return level.getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    blockEntity.getBlockPos(),
                    blockEntity.getBlockState(),
                    blockEntity,
                    direction
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "failed to query item capability at " + blockEntity.getBlockPos().toShortString()
                            + " side=" + direction,
                    exception
            );
        }
    }

    private static void addStack(ItemStack stack, HolderLookup.Provider registries, Map<String, Long> counts) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        // Original item count
        long originalCount = stack.getCount();

        // Container for the result of serialization
        Tag serialized;

        // Only do the funny saving method if stack exceeds expectations
        if(originalCount > 64) {
            // Spoofed copy of the item
            // Spoofing helps avoid mc enforcing <=99 item stack restriction
            ItemStack serializableStack = stack.copyWithCount(1);

            // Save spoofed
            serialized = serializableStack.save(registries);

            // Basic lowercase "count" should be enough but MaterialCountCodec::readStackCount
            // has a similar if statement so i'm just keeping things consistent
            if (serialized instanceof CompoundTag compound) {
                if (compound.contains("count", Tag.TAG_ANY_NUMERIC)) {
                    compound.putLong("count", originalCount);
                } else if (compound.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                    compound.putLong("Count", originalCount);
                }
            }
        } else {
            // Otherwise, save as normal
            serialized = stack.save(registries);
        }

        Map<String, Long> serializedCounts = BlueprintMaterialSummary.captureItemCounts(serialized);
        if (!serializedCounts.isEmpty()) {
            mergeInto(counts, serializedCounts);
            return;
        }

        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null && stack.getCount() > 0) {
            counts.merge(itemId.toString(), (long) stack.getCount(), Long::sum);
        }
    }
}
