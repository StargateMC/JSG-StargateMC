package tauri.dev.jsg.item.currency;

import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.entity.player.*;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import net.minecraft.server.*;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import tauri.dev.jsg.JSG;

import tauri.dev.jsg.creativetabs.JSGCreativeTabsHandler;
import javax.annotation.Nullable;
import java.util.List;

public class CurrencyItem extends Item {

    int tier = 0;
    
    public String getNameFromTier(int tier) {
        switch (tier) {
            case 1:
                return "Unrefined Naquadah";
            case 2:
                return "Refined Naquadah";
            case 3:
                return "Enriched Naquadah";
            default:
                return "invalid currency";
        }
    }
    
    public CurrencyItem(int tier) {
        this.tier = tier;
        setMaxStackSize(50);
        setMaxDamage(0);
        setRegistryName(JSG.MOD_ID + ":" + getNameFromTier(tier).toLowerCase().replace(" ","_"));
        setUnlocalizedName(JSG.MOD_ID + "." + getNameFromTier(tier).toLowerCase().replace(" ","_"));

        setCreativeTab(JSGCreativeTabsHandler.JSG_ITEMS_CREATIVE_TAB);
    }

  @SideOnly(Side.CLIENT)
  @Override
  public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, ITooltipFlag advanced) {
          tooltip.add("Currency for use with non-player factions");
          tooltip.add(stack.getCount() + " " + this.getNameFromTier(tier) + ".");
    }
}