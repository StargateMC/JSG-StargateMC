package tauri.dev.jsg.worldgen.structures.stargate;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import tauri.dev.jsg.JSG;
import tauri.dev.jsg.config.JSGConfig;
import tauri.dev.jsg.stargate.network.SymbolTypeEnum;
import tauri.dev.jsg.worldgen.structures.EnumStructures;
import tauri.dev.jsg.worldgen.util.GeneratedStargate;
import tauri.dev.jsg.worldgen.util.JSGStructurePos;
import tauri.dev.jsg.worldgen.structures.JSGStructuresGenerator;
import zmaster587.advancedRocketry.stargatemc.ARIntegration;
import zmaster587.advancedRocketry.stargatemc.Galaxy;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Random;


/**
 * @author MrJake222
 * @editedby MineDragonCZ_
 */
public class StargateGenerator {


    public static GeneratedStargate customGateSpawn(Galaxy g, boolean habitable, int attempts, @Nonnull EntityPlayer playerIn) {
        System.out.println("Attempting to generate gate and star system in : " + g.name() + " habitable: " + habitable + " attempts left: " + attempts);
        if (attempts == 0) return null; // Couldnt locate gate after retries.
        Random r = new Random();
        StellarBody star = ARIntegration.generateStarSystem(g, r.nextInt(8)+1,r.nextInt(4)+1);
        int dimID = -100000;
        int count = 0;
        while ((star.getPlanets().get(count).isGasGiant() || (habitable && !((DimensionProperties)star.getPlanets().get(count)).isHabitable())) && count < (star.getPlanets().size()-1)) {
                count++;
        }
        if (count == (star.getPlanets().size() -1)) {
            ARIntegration.cleanupStarSystem(star.getId());   
            return customGateSpawn(g, habitable, --attempts, playerIn);
        }        
        SymbolTypeEnum type = null;
        switch (g) {
            case MilkyWay:
                type = SymbolTypeEnum.MILKYWAY;
                break;
            case Pegasus:
                type = SymbolTypeEnum.PEGASUS;
                break;
            case Destiny:
                type = SymbolTypeEnum.UNIVERSE;
                break;
            case Ida:
                type = SymbolTypeEnum.MILKYWAY;
                break;
            case Othala:
                type = SymbolTypeEnum.MILKYWAY;
                break;
            case Alterran:
                type = SymbolTypeEnum.UNIVERSE;
                break;
            case GalacticVoid:
                type = SymbolTypeEnum.UNIVERSE;
                break;
        }
        GeneratedStargate sg = StargateGenerator.mystPageGeneration(playerIn.world, type, star.getPlanets().get(count).getId(), playerIn);
        if (sg == null) {
            return customGateSpawn(g, habitable, --attempts, playerIn);
        }
        return sg;
    }

    /**
     * Method used to generate stargate in random position by mysterious page
     */
    public static GeneratedStargate mystPageGeneration(World pWorld, SymbolTypeEnum symbolType, int dimensionToSpawn, @Nonnull EntityPlayer playerIn) {
        Random rand = new Random();
        int tries = 0;
        WorldServer worldToSpawn = Objects.requireNonNull(pWorld.getMinecraftServer()).getWorld(dimensionToSpawn);
        EnumStructures structure = null;

        int min = 100;
        int max = 5000;

        int x = ((min + (int) (rand.nextFloat() * max)));
        int z = ((min + (int) (rand.nextFloat() * max)));

        if (rand.nextBoolean()) x *= -1;
        if (rand.nextBoolean()) z *= -1;

        JSGStructurePos structurePos = null;
        int chunkX = x / 16;
        int chunkZ = z / 16;
        int bestCount = 0;
        do {

            if (structurePos != null && structurePos.bestAttemptPos != null) {
                x = structurePos.bestAttemptPos.getX();
                z = structurePos.bestAttemptPos.getZ();

                if (x / 16 == chunkX) {
                    if (tries % 2 == 0)
                        x += (16 * (x < 0 ? 1 : -1));
                }
                if (z / 16 == chunkZ) {
                    if (tries % 2 == 0)
                        z += (-16 * (z < 0 ? 1 : -1));
                    else
                        z += 16 * (z < 0 ? 1 : -1);
                }
            } else if(tries > 0){
                x = ((min + (int) (rand.nextFloat() * max)));
                z = ((min + (int) (rand.nextFloat() * max)));
            }


            chunkX = x / 16;
            chunkZ = z / 16;

            Chunk chunk = worldToSpawn.getChunkFromChunkCoords(chunkX, chunkZ);
            int y = chunk.getHeightValue(8, 8);
            if (y > 240)
                continue;

            String biomeName = Objects.requireNonNull(worldToSpawn.getBiome(new BlockPos(x, y, z)).getRegistryName()).getResourcePath();
            structure = EnumStructures.getStargateStructureByBiome(biomeName, symbolType, dimensionToSpawn);
            if (structure != null) {
                structurePos = JSGStructuresGenerator.checkForPlace(worldToSpawn, chunkX, chunkZ, structure, dimensionToSpawn);
            }
            if(structurePos != null && structurePos.bestAttemptPos != null)
                bestCount++;
            tries++;
        } while ((structurePos == null || structurePos.foundPos == null) && tries < 50);
        if (structure == null || structurePos == null || structurePos.foundPos == null) {
            JSG.error("(" + playerIn.getDisplayNameString() + ") StargateGenerator: Failed to find place - myst page: Tries:" + tries + "; Structure:" + (structure != null));
            JSG.error("Best places count: " + bestCount);
            return null;
        }

        return structure.getActualStructure(dimensionToSpawn).generateStructure(pWorld, structurePos.foundPos, rand, worldToSpawn);
    }
}
