package pyre.tinkerslevellingaddon;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import pyre.tinkerslevellingaddon.config.Config;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.modifiers.hooks.IHarvestModifier;
import slimeknights.tconstruct.library.modifiers.hooks.IShearModifier;
import slimeknights.tconstruct.library.modifiers.impl.NoLevelsModifier;
import slimeknights.tconstruct.library.tools.SlotType;
import slimeknights.tconstruct.library.tools.context.EquipmentContext;
import slimeknights.tconstruct.library.tools.context.ToolAttackContext;
import slimeknights.tconstruct.library.tools.context.ToolHarvestContext;
import slimeknights.tconstruct.library.tools.context.ToolRebuildContext;
import slimeknights.tconstruct.library.tools.nbt.IModDataView;
import slimeknights.tconstruct.library.tools.nbt.IToolStackView;
import slimeknights.tconstruct.library.tools.nbt.ModDataNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;
import slimeknights.tconstruct.library.utils.RestrictedCompoundTag;
import slimeknights.tconstruct.tools.TinkerModifiers;

import javax.annotation.Nullable;
import java.util.List;

public class ImprovementModifier extends NoLevelsModifier implements IHarvestModifier, IShearModifier {

    public static final ResourceLocation EXPERIENCE_KEY = new ResourceLocation(TinkersLevellingAddon.MOD_ID, "experience");
    public static final ResourceLocation LEVEL_KEY = new ResourceLocation(TinkersLevellingAddon.MOD_ID, "level");

    @Override
    public void beforeRemoved(IToolStackView tool, RestrictedCompoundTag tag) {
        tool.getPersistentData().remove(EXPERIENCE_KEY);
        tool.getPersistentData().remove(LEVEL_KEY);
    }

    @Override
    public void addVolatileData(ToolRebuildContext context, int level, ModDataNBT volatileData) {
        IModDataView data = context.getPersistentData();
        List<SlotType> slotRotation = context.hasTag(TinkerTags.Items.ARMOR) ? Config.getArmorSlotsRotation() :
                Config.getToolsSlotsRotation();
        int lvl = data.getInt(LEVEL_KEY);
        for (int i = 0; i < lvl; i++) {
            volatileData.addSlots(slotRotation.get(i % slotRotation.size()), 1);
        }
    }

    @Override
    public void afterBlockBreak(IToolStackView tool, int level, ToolHarvestContext context) {
        ServerPlayer player = context.getPlayer();
        if (!Config.enableMiningXp.get() || player == null) {
            return;
        }
        ToolStack toolStack = getHeldTool(player, InteractionHand.MAIN_HAND);
        if (!isEqualTinkersItem(tool, toolStack)) {
            toolStack = getHeldTool(player, InteractionHand.OFF_HAND);
        }
        addExperience(toolStack, 1 + Config.bonusMiningXp.get(), player);
    }


    @Override
    public void afterHarvest(IToolStackView tool, int level, UseOnContext context, ServerLevel world,
                             BlockState state, BlockPos pos) {
        Player player = context.getPlayer();
        if (!Config.enableHarvestingXp.get() || player == null) {
            return;
        }
        ToolStack toolStack = getHeldTool(player, context.getHand());
        addExperience(toolStack, 1 + Config.bonusHarvestingXp.get(), player);
    }

    @Override
    public void afterShearEntity(IToolStackView tool, int level, Player player, Entity entity, boolean isTarget) {
        if (!Config.enableShearingXp.get() || player == null) {
            return;
        }
        ToolStack toolStack = getHeldTool(player, InteractionHand.MAIN_HAND);
        if (!isEqualTinkersItem(tool, toolStack)) {
            toolStack = getHeldTool(player, InteractionHand.OFF_HAND);
        }
        addExperience(toolStack, 1 + Config.bonusShearingXp.get(), player);
    }

    @Override
    public int afterEntityHit(IToolStackView tool, int level, ToolAttackContext context, float damageDealt) {
        if (!Config.enableAttackingXp.get() || context.getPlayerAttacker() == null ||
                (Config.enablePvp.get() && context.getLivingTarget() instanceof Player) || context.getLivingTarget() == null) {
            return 0;
        }
        int xp = (Config.damageDealt.get() ? Math.round(damageDealt) : 1) + Config.bonusAttackingXp.get();
        ToolStack toolStack = getHeldTool(context.getPlayerAttacker(), context.getSlotType());
        addExperience(toolStack, xp, context.getPlayerAttacker());
        return 0;
    }

    @Override
    public void onAttacked(IToolStackView tool, int level, EquipmentContext context, EquipmentSlot slotType,
                           DamageSource source, float amount, boolean isDirectDamage) {
        if (!Config.enableTakingDamageXp.get() || slotType.getType() != EquipmentSlot.Type.ARMOR ||
                !(context.getEntity() instanceof Player player) || !isValidDamageSource(source, player)) {
            return;
        }
        int xp = (Config.damageTaken.get() ? Math.round(amount) : 1) + Config.bonusTakingDamageXp.get() + getThornsBonus(tool);
        addExperience(getHeldTool(player, slotType), xp, player);
    }

    //currently no hooks for tilling, striping wood, making paths...

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getModule(Class<T> type) {
        if (type == IHarvestModifier.class || type == IShearModifier.class) {
            return (T) this;
        }
        return null;
    }

    private void addExperience(ToolStack tool, int amount, Player player) {
        if (tool == null) {
            return;
        }

        ModDataNBT data = tool.getPersistentData();
        int currentLevel = data.getInt(LEVEL_KEY);
        int currentExperience = data.getInt(EXPERIENCE_KEY) + amount;
        int experienceNeeded = getXpNeededForLevel(currentLevel + 1);

        while (currentExperience >= experienceNeeded) {
            if (!canLevelUp(currentLevel)) {
                return;
            }
            data.putInt(LEVEL_KEY, ++currentLevel);
            currentExperience -= experienceNeeded;
            experienceNeeded = getXpNeededForLevel(currentLevel + 1);

            //todo add player feedback (message, sound)
            tool.rebuildStats();
        }
        data.putInt(EXPERIENCE_KEY, currentExperience);
    }

    private boolean isEqualTinkersItem(IToolStackView item1, IToolStackView item2) {
        if(item1 == null || item2 == null || item1.getItem() != item2.getItem()) {
            return false;
        }
        return item1.getModifiers().equals(item2.getModifiers()) && item1.getMaterials().equals(item2.getMaterials());
    }

    private boolean isValidDamageSource(DamageSource source, Player player) {
        return !source.isBypassArmor() && source.getEntity() instanceof LivingEntity attacker &&
                !attacker.equals(player) && (Config.enablePvp.get() || !(attacker instanceof Player));
    }

    private int getThornsBonus(IToolStackView tool) {
        int thornsLevel = tool.getModifierLevel(TinkerModifiers.thorns.getId());
        if (Config.enableThornsXp.get() || thornsLevel == 0) {
            return 0;
        }
        return RANDOM.nextFloat() < (thornsLevel * 0.15f) ? 1 + RANDOM.nextInt(Config.bonusThornsXp.get() + 1) : 0;
    }

    public static int getXpNeededForLevel(int level) {
        int experienceNeeded = Config.baseExperience.get();
        if (level > 1) {
            experienceNeeded = (int) (getXpNeededForLevel(level - 1) * Config.levelMultiplier.get());
        }
        return experienceNeeded;
    }

    public static boolean canLevelUp(int level) {
        return Config.maxLevel.get() == 0 || Config.maxLevel.get() > level;
    }
}
