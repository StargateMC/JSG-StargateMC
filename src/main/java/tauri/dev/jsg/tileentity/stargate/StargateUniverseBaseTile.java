package tauri.dev.jsg.tileentity.stargate;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import tauri.dev.jsg.block.JSGBlocks;
import tauri.dev.jsg.config.JSGConfig;
import tauri.dev.jsg.config.stargate.StargateDimensionConfig;
import tauri.dev.jsg.config.stargate.StargateSizeEnum;
import tauri.dev.jsg.packet.JSGPacketHandler;
import tauri.dev.jsg.packet.StateUpdatePacketToClient;
import tauri.dev.jsg.renderer.biomes.BiomeOverlayEnum;
import tauri.dev.jsg.renderer.stargate.StargateClassicRendererState;
import tauri.dev.jsg.renderer.stargate.StargateUniverseRendererState;
import tauri.dev.jsg.sound.*;
import tauri.dev.jsg.stargate.*;
import tauri.dev.jsg.stargate.merging.StargateAbstractMergeHelper;
import tauri.dev.jsg.stargate.merging.StargateUniverseMergeHelper;
import tauri.dev.jsg.stargate.network.*;
import tauri.dev.jsg.stargate.power.StargateEnergyRequired;
import tauri.dev.jsg.state.State;
import tauri.dev.jsg.state.StateTypeEnum;
import tauri.dev.jsg.state.stargate.StargateRendererActionState;
import tauri.dev.jsg.state.stargate.StargateSpinState;
import tauri.dev.jsg.state.stargate.StargateUniverseSymbolState;
import tauri.dev.jsg.tileentity.props.DestinyCountDownTile;
import tauri.dev.jsg.tileentity.util.ScheduledTask;
import tauri.dev.jsg.util.ILinkable;
import tauri.dev.jsg.util.JSGAxisAlignedBB;
import tauri.dev.jsg.util.LinkingHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static tauri.dev.jsg.stargate.EnumStargateState.DIALING;
import static tauri.dev.jsg.stargate.EnumStargateState.FAILING;
import static tauri.dev.jsg.stargate.network.SymbolUniverseEnum.G1;
import static tauri.dev.jsg.stargate.network.SymbolUniverseEnum.TOP_CHEVRON;

public class StargateUniverseBaseTile extends StargateClassicBaseTile implements ILinkable {

    protected World fakeWorld;
    protected BlockPos fakePos;

    public World getFakeWorld() {
        if (fakeWorld == null) return world;
        return fakeWorld;
    }

    public void setFakeWorld(World world) {
        fakeWorld = world;
        markDirty();
    }

    public BlockPos getFakePos() {
        if (fakePos == null) return pos;
        return fakePos;
    }

    public void setFakePos(BlockPos pos) {
        fakePos = pos;
        markDirty();
    }

    // general
    private static final StargateSizeEnum defaultStargateSize = StargateSizeEnum.SMALL;
    private static final EnumSet<BiomeOverlayEnum> SUPPORTED_OVERLAYS = EnumSet.of(
            BiomeOverlayEnum.NORMAL,
            BiomeOverlayEnum.FROST,
            BiomeOverlayEnum.MOSSY,
            BiomeOverlayEnum.AGED
    );

    // dialing
    private StargateAddress addressToDial;
    private int symbolsToDialCount;
    private int addressPosition;

    // aborting
    private boolean abortingDialing = false;

    // fast dialing
    private boolean isFastDialing = false;
    private static final int fastDialingPeriod = 30; //ticks (1 symbol per this)

    // cooldown
    private int coolDown = 0;
    private static final int coolDownDelay = 60; // in ticks (3 seconds)

    // --------------------------------------------------------------------------------
    // CoolDown system - prevent from spamming uni dialer functions

    public boolean canContinue() {
        return !(coolDown > 0);
    }

    public void updateCoolDown() {
        if (coolDown > 0)
            coolDown--;
        if (coolDown < 0)
            coolDown = 0;
        markDirty();
    }

    public void setCoolDown() {
        coolDown = coolDownDelay;
        markDirty();
    }


    // --------------------------------------------------------------------------------
    // Update

    private BlockPos lastPos = BlockPos.ORIGIN;

    @Override
    public void update() {
        super.update();
        updateCoolDown();

        if (!world.isRemote) {
            if (!lastPos.equals(pos)) {
                lastPos = pos;
                markDirty();
            }
        }
    }

    @Override
    protected void onGateMerged() {
        super.onGateMerged();
        this.updateLinkStatus();
    }

    // --------------------------------------------------------------------------------
    // Dialing

    public StargateAddress getAddressToDial() {
        return addressToDial;
    }

    public void addSymbolToAddressDHD(SymbolInterface symbol) {
    }

    /**
     * Dial the address using DIALER
     *
     * @param address     - address to dial
     * @param symbolCount - symbols to engage
     */
    public boolean dialAddress(StargateAddress address, int symbolCount) {
        if (!canContinue()) return false;
        if (!stargateState.idle()) return false;

        this.addressToDial = address;
        this.symbolsToDialCount = symbolCount;
        this.addressPosition = -1;
        targetRingSymbol = G1;
        stargateState = DIALING;
        JSGSoundHelper.playSoundEvent(world, getGateCenterPos(), SoundEventEnum.GATE_UNIVERSE_DIAL_START);
        sendRenderingUpdate(StargateRendererActionState.EnumGateAction.LIGHT_UP_CHEVRONS, 9, true);
        addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_NEXT, 35, null));
        ringSpinContext = null;
        spinDirection = EnumSpinDirection.CLOCKWISE;
        setCoolDown();
        markDirty();
        return true;
    }

    public boolean getFastDialState() {
        return isFastDialing;
    }

    public void setFastDial(boolean state) {
        if (stargateState.idle()) isFastDialing = state;
        markDirty();
    }

    /**
     * get next symbol from addressToDial
     *
     * @return SymbolInterface
     */
    public SymbolInterface getNextSymbol(boolean addOne) {
        addressPosition++;
        int pos = addressPosition;
        if (!addOne) addressPosition--;

        markDirty();
        if (pos >= (addressToDial.getSize() + 1)) return getSymbolType().getTopSymbol();
        if (pos >= symbolsToDialCount && addOne)
            return getSymbolType().getOrigin(); // return origin when symbols is last one
        else if (pos >= symbolsToDialCount)
            return null;
        return addressToDial.get(pos);
    }

    /**
     * abort dialing
     */
    @Override
    public boolean abortDialingSequence() {
        if (stargateState.incoming()) return false;
        if (isIncoming) return false;
        if (canContinue() && (stargateState.dialingComputer() || stargateState.idle() || stargateState.dialing())) {
            abortingDialing = true;
            currentRingSymbol = targetRingSymbol;
            markDirty();
            addTask(new ScheduledTask(EnumScheduledTask.STARGATE_RESET, 5, null));
            spinStartTime = world.getTotalWorldTime() + 3000;
            isSpinning = false;
            JSGPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.SPIN_STATE, new StargateSpinState(targetRingSymbol, spinDirection, true, 0)), targetPoint);
            addFailedTaskAndPlaySound();
            playPositionedSound(StargateSoundPositionedEnum.GATE_RING_ROLL, false);
            // remove last spinning finished task
            if (lastSpinFinished != null && scheduledTasks.contains(lastSpinFinished))
                removeTask(lastSpinFinished);
            if (!isIncoming) disconnectGate();
            stargateState = FAILING;
            setCoolDown();
            markDirty();
            resetTargetIncomingAnimation();
            return true;
        }
        return false;
    }

    /**
     * fail the gate
     */
    @Override
    public void failGate() {
        if (stargateState.incoming()) return;
        isIncoming = false;
        markDirty();
        if (abortingDialing) {
            isFinalActive = false;
            markDirty();
            return;
        }
        super.failGate();
        addressToDial = null;
        if (!abortingDialing && targetRingSymbol != TOP_CHEVRON)
            addSymbolToAddressManual(TOP_CHEVRON, null);
    }

    @Override
    public void dialingFailed(StargateOpenResult reason) {
        playPositionedSound(StargateSoundPositionedEnum.GATE_RING_ROLL, false);
        super.dialingFailed(reason);
    }

    /**
     * disconnect gate
     */
    @Override
    protected void disconnectGate() {
        isIncoming = false;
        markDirty();
        super.disconnectGate();
        addressToDial = null;
        if (!abortingDialing)
            addSymbolToAddressManual(TOP_CHEVRON, null);
    }

    /**
     * Add symbol by spinning the gate
     *
     * @param targetSymbol - symbol to dial
     * @param context      - null if dialing with dialer
     */
    @Override
    public void addSymbolToAddressManual(SymbolInterface targetSymbol, Object context) {
        if (stargateState.incoming()) return;
        if (targetSymbol != getSymbolType().getTopSymbol()) {
            if (context != null) stargateState = EnumStargateState.DIALING_COMPUTER;
            else stargateState = DIALING;
        }

        if (dialedAddress.size() == 0 && targetSymbol != TOP_CHEVRON && stargateState.dialingComputer()) {
            JSGSoundHelper.playSoundEvent(world, getGateCenterPos(), SoundEventEnum.GATE_UNIVERSE_DIAL_START);
            sendRenderingUpdate(StargateRendererActionState.EnumGateAction.LIGHT_UP_CHEVRONS, 9, true);
            targetRingSymbol = targetSymbol;
            addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_NEXT, 35, null));
            ringSpinContext = context;
            spinDirection = EnumSpinDirection.CLOCKWISE;
        } else super.addSymbolToAddressManual(targetSymbol, context);

        if (targetSymbol == getSymbolType().getTopSymbol())
            stargateState = FAILING;
        markDirty();
    }

    /**
     * get energy required to dial specific address
     *
     * @param targetGatePos - position of target gate
     * @return energy
     */
    @Override
    protected StargateEnergyRequired getEnergyRequiredToDial(StargatePos targetGatePos) {
        BlockPos sPos = getFakePos();
        BlockPos tPos = targetGatePos.gatePos;
        DimensionType sourceDim = getFakeWorld().provider.getDimensionType();
        DimensionType targetDim = targetGatePos.getWorld().provider.getDimensionType();

        StargateAbstractBaseTile targetTile = targetGatePos.getTileEntity();
        if (targetTile instanceof StargateUniverseBaseTile) {
            tPos = ((StargateUniverseBaseTile) targetTile).getFakePos();
            targetDim = ((StargateUniverseBaseTile) targetTile).getFakeWorld().provider.getDimensionType();
        }

        if (sourceDim == DimensionType.OVERWORLD && targetDim == DimensionType.NETHER)
            tPos = new BlockPos(tPos.getX() * 8, tPos.getY(), tPos.getZ() * 8);
        else if (sourceDim == DimensionType.NETHER && targetDim == DimensionType.OVERWORLD)
            sPos = new BlockPos(sPos.getX() * 8, sPos.getY(), sPos.getZ() * 8);

        double distance = (int) sPos.getDistance(tPos.getX(), tPos.getY(), tPos.getZ());

        if (distance < 5000) distance *= 0.8;
        else distance = 5000 * Math.log10(distance) / Math.log10(5000);

        StargateEnergyRequired energyRequired = new StargateEnergyRequired(JSGConfig.powerConfig.openingBlockToEnergyRatio, JSGConfig.powerConfig.keepAliveBlockToEnergyRatioPerTick);
        energyRequired = energyRequired.mul(distance).add(StargateDimensionConfig.getCost(sourceDim, targetDim));

        if(dialedAddress.size() == 9)
            energyRequired.mul(JSGConfig.powerConfig.nineSymbolAddressMul);
        if(dialedAddress.size() == 8)
            energyRequired.mul(JSGConfig.powerConfig.eightSymbolAddressMul);

        return energyRequired.mul(JSGConfig.powerConfig.stargateUniverseEnergyMul);
    }

    @Override
    public SymbolTypeEnum getSymbolType() {
        return SymbolTypeEnum.UNIVERSE;
    }

    @Override
    protected int getMaxChevrons() {
        return 9;
    }

    @Override
    protected int getOpenSoundDelay() {
        return super.getOpenSoundDelay() + 10;
    }

    private void activateSymbolServer(SymbolInterface symbol) {
        JSGPacketHandler.INSTANCE.sendToAllTracking(new StateUpdatePacketToClient(pos, StateTypeEnum.STARGATE_UNIVERSE_ACTIVATE_SYMBOL, new StargateUniverseSymbolState((SymbolUniverseEnum) symbol, false)), targetPoint);
    }

    @Override
    protected void addSymbolToAddress(SymbolInterface symbol) {
        activateSymbolServer(symbol);
        if (isFastDialing)
            playSoundEvent(StargateSoundEventEnum.CHEVRON_SHUT);
        super.addSymbolToAddress(symbol);
    }

    // --------------------------------------------------------------------------------
    // Scheduled tasks

    @Override
    public void executeTask(EnumScheduledTask scheduledTask, NBTTagCompound customData) {
        boolean onlySpin = false;
        if (customData != null && customData.hasKey("onlySpin"))
            onlySpin = customData.getBoolean("onlySpin");
        switch (scheduledTask) {
            case LIGHT_UP_CHEVRONS:
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.LIGHT_UP_CHEVRONS, 9, true);
                break;
            case STARGATE_DIAL_NEXT:
                if (stargateState.incoming()) break;
                if (abortingDialing || stargateState.failing()) break;
                if (isFastDialing && stargateState.dialingDHD()) {
                    if (dialedAddress.size() == 0)
                        spinRing(1, false, true, fastDialingPeriod * symbolsToDialCount + (5 * 20));
                    SymbolInterface tempSymbol = getNextSymbol(true);
                    if (!canAddSymbol(tempSymbol) || tempSymbol == TOP_CHEVRON)
                        break;
                    addSymbolToAddress(tempSymbol);
                    addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_FINISHED, 10));
                    doIncomingAnimation(fastDialingPeriod * 2, true, getNextSymbol(false));
                    if (!stargateWillLock(tempSymbol)) {
                        addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_NEXT, fastDialingPeriod));
                    }
                    break;
                }
                if (stargateState.dialingComputer() && targetRingSymbol != getSymbolType().getTopSymbol())
                    super.addSymbolToAddressManual(targetRingSymbol, ringSpinContext);
                else if (targetRingSymbol != getSymbolType().getTopSymbol()) {
                    targetRingSymbol = getNextSymbol(true);
                    markDirty();
                    if (targetRingSymbol == TOP_CHEVRON) {
                        abortDialingSequence();
                        break;
                    }
                    addSymbolToAddressManual(targetRingSymbol, null);
                }
                break;
            case STARGATE_RESET:
                if (stargateState.incoming()) break;
                addSymbolToAddressManual(getSymbolType().getTopSymbol(), null);
                abortingDialing = false;
                break;

            case STARGATE_SPIN_FINISHED:
                if (onlySpin && stargateState.dialingComputer()) {
                    stargateState = EnumStargateState.IDLE;
                    sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CLEAR_CHEVRONS, 9, true);
                    markDirty();
                    break;
                } else if (onlySpin && stargateState.dialing() && isFastDialing) {
                    attemptOpenAndFail();
                    break;
                } else if (onlySpin || stargateState.incoming())
                    break;

                if ((targetRingSymbol != TOP_CHEVRON)) {
                    if (canAddSymbol(targetRingSymbol)) {
                        addSymbolToAddress(targetRingSymbol);
                        addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_FINISHED, 10));

                        if (stargateState.dialingComputer()) {
                            if (!abortingDialing) stargateState = EnumStargateState.IDLE;
                        } else {
                            if (!stargateWillLock(targetRingSymbol)) {
                                addTask(new ScheduledTask(EnumScheduledTask.STARGATE_DIAL_NEXT, 24));
                            } else
                                attemptOpenAndFail();
                        }
                    } else if (!stargateState.incoming()) {
                        dialingFailed(StargateOpenResult.ADDRESS_MALFORMED);
                        stargateState = EnumStargateState.IDLE;
                    }
                } else {
                    dialingFailed(StargateOpenResult.ABORTED);
                    stargateState = EnumStargateState.IDLE;
                    abortingDialing = false;
                }
                markDirty();
                break;

            case BEGIN_SPIN:
                if (customData != null && customData.hasKey("period")) {
                    int period = customData.getInteger("period");
                    spinRing(1, false, true, period);
                }
                break;

            case STARGATE_FAILED_SOUND:
                playSoundEvent(StargateSoundEventEnum.DIAL_FAILED);
                markDirty();
                break;

            case STARGATE_DIAL_FINISHED:
                sendSignal(ringSpinContext, "stargate_spin_chevron_engaged", new Object[]{dialedAddress.size(), stargateWillLock(targetRingSymbol), targetRingSymbol.getEnglishName()});
                break;

            default:
                break;
        }
        markDirty();
        super.executeTask(scheduledTask, customData);
    }

    // --------------------------------------------------------------------------------
    // States

    @Override
    public State createState(StateTypeEnum stateType) {
        if (stateType == StateTypeEnum.STARGATE_UNIVERSE_ACTIVATE_SYMBOL) {
            return new StargateUniverseSymbolState();
        }
        return super.createState(stateType);
    }

    @Override
    public void setState(StateTypeEnum stateType, State state) {
        if (getRendererStateClient() != null) {
            switch (stateType) {
                case STARGATE_UNIVERSE_ACTIVATE_SYMBOL:
                    StargateUniverseSymbolState symbolState = (StargateUniverseSymbolState) state;

                    if (symbolState.dimAll) getRendererStateClient().clearSymbols(world.getTotalWorldTime());
                    else getRendererStateClient().activateSymbol(world.getTotalWorldTime(), symbolState.symbol);

                    break;

                case RENDERER_UPDATE:
                    StargateRendererActionState gateActionState = (StargateRendererActionState) state;

                    if (gateActionState.action == StargateRendererActionState.EnumGateAction.CLEAR_CHEVRONS) {
                        getRendererStateClient().clearSymbols(world.getTotalWorldTime());
                    }

                    break;

                default:
                    break;
            }
        }

        super.setState(stateType, state);
    }

    // --------------------------------------------------------------------------------
    // Incoming animation

    @Override
    public void incomingWormhole(int dialedAddressSize) {
        startIncomingAnimation(dialedAddressSize, 10);
        super.incomingWormhole(9);
    }

    @Override
    public void incomingWormhole(int dialedAddressSize, int time) {
        time = time * dialedAddressSize;
        int period = time - 2000;
        if (period < 0) period = 0;
        startIncomingAnimation(dialedAddressSize, period);
        super.incomingWormhole(9, false);
    }

    @Override
    public void startIncomingAnimation(int addressSize, int period) {
        double ticks = (double) (period * 20) / 1000;
        incomingPeriod = (int) Math.round(ticks);
        incomingAddressSize = addressSize;
        incomingLastChevronLightUp = 0;
        stargateState = EnumStargateState.INCOMING;
        isIncoming = true;
        sendRenderingUpdate(StargateRendererActionState.EnumGateAction.CLEAR_CHEVRONS, 9, true);
        if (stargateState.dialing())
            abortDialingSequence();
        markDirty();
        this.lightUpChevronByIncoming(!config.getOption(ConfigOptions.ALLOW_INCOMING.id).getBooleanValue());
    }

    @Override
    protected void lightUpChevronByIncoming(boolean disableAnimation) {
        super.lightUpChevronByIncoming(disableAnimation);
        if (incomingPeriod == -1) return;

        boolean spin = config.getOption(ConfigOptions.SPIN_GATE_INCOMING.id).getBooleanValue();

        if (!disableAnimation && incomingLastChevronLightUp == 1) {
            stargateState = EnumStargateState.INCOMING;
            NBTTagCompound compound = new NBTTagCompound();
            int time = incomingPeriod - (8 + 7);
            compound.setInteger("period", time);
            if (spin)
                addTask(new ScheduledTask(EnumScheduledTask.BEGIN_SPIN, 8 + 7, compound));
            addTask(new ScheduledTask(EnumScheduledTask.LIGHT_UP_CHEVRONS, 8));
            sendSignal(null, "stargate_incoming_wormhole", new Object[]{incomingAddressSize});
            playSoundEvent(StargateSoundEventEnum.INCOMING);
            super.resetIncomingAnimation();
            isIncoming = false;
            if (irisMode == EnumIrisMode.AUTO && isIrisOpened()) {
                toggleIris();
            }
        } else {
            if (incomingLastChevronLightUp == 2 && disableAnimation) {
                stargateState = EnumStargateState.INCOMING;
                sendRenderingUpdate(StargateRendererActionState.EnumGateAction.LIGHT_UP_CHEVRONS, 9, true);
                sendSignal(null, "stargate_incoming_wormhole", new Object[]{incomingAddressSize});
                playSoundEvent(StargateSoundEventEnum.INCOMING);
                super.resetIncomingAnimation();
                isIncoming = false;
                if (irisMode == EnumIrisMode.AUTO && isIrisOpened()) {
                    toggleIris();
                }
            } else if (isIncoming && !stargateState.engaged())
                stargateState = EnumStargateState.INCOMING;
        }
        markDirty();
    }

    // --------------------------------------------------------------------------------
    // Overlays

    @Override
    public EnumSet<BiomeOverlayEnum> getSupportedOverlays() {
        return SUPPORTED_OVERLAYS;
    }

    // --------------------------------------------------------------------------------
    // Sounds

    @Nullable
    @Override
    protected SoundPositionedEnum getPositionedSound(StargateSoundPositionedEnum soundEnum) {
        switch (soundEnum) {
            case GATE_RING_ROLL:
                return SoundPositionedEnum.UNIVERSE_RING_ROLL;
            case GATE_RING_ROLL_START:
                return SoundPositionedEnum.UNIVERSE_RING_ROLL_START;
        }

        return null;
    }

    @Nullable
    @Override
    protected SoundEventEnum getSoundEvent(StargateSoundEventEnum soundEnum) {
        switch (soundEnum) {
            case OPEN:
                return SoundEventEnum.GATE_UNIVERSE_OPEN;
            case CLOSE:
                return SoundEventEnum.GATE_UNIVERSE_CLOSE;
            case DIAL_FAILED:
                return SoundEventEnum.GATE_UNIVERSE_DIAL_FAILED;
            case INCOMING:
                return SoundEventEnum.GATE_UNIVERSE_DIAL_START;
            case CHEVRON_SHUT:
                return targetRingSymbol == TOP_CHEVRON ? SoundEventEnum.GATE_UNIVERSE_CHEVRON_TOP_LOCK : SoundEventEnum.GATE_UNIVERSE_CHEVRON_LOCK;
        }

        return null;
    }

    // --------------------------------------------------------------------------------
    // Teleportation

    @Override
    protected JSGAxisAlignedBB getHorizonTeleportBox(boolean server) {
        return defaultStargateSize.teleportBox;
    }

    @Override
    public BlockPos getGateCenterPos() {
        return pos.up(4);
    }

    @Override
    protected JSGAxisAlignedBB getHorizonKillingBox(boolean server) {
        return defaultStargateSize.killingBox;
    }

    @Override
    protected int getHorizonSegmentCount(boolean server) {
        return defaultStargateSize.horizonSegmentCount;
    }

    @Override
    protected List<JSGAxisAlignedBB> getGateVaporizingBoxes(boolean server) {
        return defaultStargateSize.gateVaporizingBoxes;
    }

    // --------------------------------------------------------------------------------
    // Merging

    @Override
    public StargateAbstractMergeHelper getMergeHelper() {
        return StargateUniverseMergeHelper.INSTANCE;
    }

    @Override
    protected boolean onGateMergeRequested() {
        return StargateUniverseMergeHelper.INSTANCE.checkBlocks(world, pos, facing);
    }

    @Override
    public void setLinkedDHD(BlockPos dhdPos, int linkId) {
    }

    @Nonnull
    @Override
    protected StargateSizeEnum getStargateSize() {
        return defaultStargateSize;
    }


    // --------------------------------------------------------------------------------
    // Renderer states

    @Override
    protected StargateClassicRendererState.StargateClassicRendererStateBuilder getRendererStateServer() {
        return new StargateUniverseRendererState.StargateUniverseRendererStateBuilder(super.getRendererStateServer())
                .setDialedAddress((stargateState.initiating() || stargateState.dialing()) ? dialedAddress : new StargateAddressDynamic(getSymbolType()))
                .setActiveChevrons(stargateState.idle() ? 0 : 9);
    }

    @Override
    protected StargateUniverseRendererState createRendererStateClient() {
        return new StargateUniverseRendererState();
    }

    @Override
    public StargateUniverseRendererState getRendererStateClient() {
        return (StargateUniverseRendererState) super.getRendererStateClient();
    }

    @Override
    public int getSupportedCapacitors() {
        return getConfig().getOption(ConfigOptions.CAPACITORS_COUNT.id).getIntValue();
    }


    // --------------------------------------------------------------------------------
    // NBTs

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        if (addressToDial != null) compound.setTag("addressToDial", addressToDial.serializeNBT());
        compound.setInteger("symbolsToDialCount", symbolsToDialCount);
        compound.setInteger("addressPosition", addressPosition);
        compound.setInteger("coolDown", coolDown);
        compound.setBoolean("fastDialing", isFastDialing);
        compound.setBoolean("abortingDialing", abortingDialing);

        if (isLinked()) {
            compound.setLong("countDownPos", countDownPos.toLong());
            compound.setInteger("linkId", linkId);
        }

        if (fakePos != null) {
            compound.setInteger("fakeX", fakePos.getX());
            compound.setInteger("fakeY", fakePos.getY());
            compound.setInteger("fakeZ", fakePos.getY());
        }
        if (fakeWorld != null)
            compound.setInteger("fakeWorld", fakeWorld.provider.getDimension());

        return super.writeToNBT(compound);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);

        addressToDial = new StargateAddress(compound.getCompoundTag("addressToDial"));
        addressPosition = compound.getInteger("addressPosition");
        symbolsToDialCount = compound.getInteger("symbolsToDialCount");
        coolDown = compound.getInteger("coolDown");
        isFastDialing = compound.getBoolean("fastDialing");
        abortingDialing = compound.getBoolean("abortingDialing");

        if (compound.hasKey("countDownPos")) this.countDownPos = BlockPos.fromLong(compound.getLong("countDownPos"));
        if (compound.hasKey("linkId")) this.linkId = compound.getInteger("linkId");

        if (compound.hasKey("fakeX"))
            this.fakePos = new BlockPos(compound.getInteger("fakeX"), compound.getInteger("fakeY"), compound.getInteger("fakeZ"));
        if (compound.hasKey("fakeWorld") && world.getMinecraftServer() != null)
            this.fakeWorld = this.world.getMinecraftServer().getWorld(compound.getInteger("fakeWorld"));
    }

    // linking

    @Override
    public boolean canLinkTo() {
        return isMerged() && !isLinked();
    }

    private BlockPos countDownPos;
    private int linkId = -1;

    public boolean isLinked() {
        return countDownPos != null && world.getTileEntity(countDownPos) instanceof DestinyCountDownTile;
    }

    public void setLinkedCountdown(BlockPos dhdPos, int linkId) {
        this.countDownPos = dhdPos;
        this.linkId = linkId;

        markDirty();
    }

    public void updateLinkStatus() {
        if (!isMerged()) return;
        BlockPos closestUnlinked = LinkingHelper.findClosestUnlinked(world, pos, LinkingHelper.getDhdRange(), JSGBlocks.DESTINY_COUNTDOWN_BLOCK, this.getLinkId());
        int linkId = LinkingHelper.getLinkId();

        if (closestUnlinked != null) {
            DestinyCountDownTile destinyCountDownTile = (DestinyCountDownTile) world.getTileEntity(closestUnlinked);
            if (destinyCountDownTile != null) {
                destinyCountDownTile.setLinkedGate(pos, linkId);
                setLinkedCountdown(closestUnlinked, linkId);
                markDirty();
            }
        }
    }

    public StargateAddress getRandomNearbyGate() {
        double squaredGate = (double) JSGConfig.stargateConfig.universeGateNearbyReach * tauri.dev.jsg.config.JSGConfig.stargateConfig.universeGateNearbyReach;
        ArrayList<StargateAddress> addresses = new ArrayList<>();

        for (Map.Entry<StargateAddress, StargatePos> entry : StargateNetwork.get(getFakeWorld()).getMap().get(SymbolTypeEnum.UNIVERSE).entrySet()) {

            StargatePos stargatePos = entry.getValue();
            StargateAbstractBaseTile targetGateTile = stargatePos.getTileEntity();

            if (!(targetGateTile instanceof StargateClassicBaseTile))
                continue;

            if (!targetGateTile.isMerged())
                continue;

            // get only universe gates in nearby
            if (!(targetGateTile instanceof StargateUniverseBaseTile))
                continue;

            StargateUniverseBaseTile targetUniTile = (StargateUniverseBaseTile) targetGateTile;

            int targetDim = targetUniTile.getFakeWorld().provider.getDimension();
            BlockPos targetFoundPos = targetUniTile.getFakePos();

            if (targetDim != getFakeWorld().provider.getDimension())
                continue;

            if (targetFoundPos.distanceSq(getFakePos()) > squaredGate)
                continue;

            if (stargatePos.gatePos.equals(pos) && stargatePos.dimensionID == world.provider.getDimension())
                continue;

            StargateAddressDynamic addr3 = new StargateAddressDynamic(SymbolTypeEnum.UNIVERSE);
            int symbols = (StargateDimensionConfig.isGroupEqual(DimensionManager.getProviderType(stargatePos.dimensionID), world.provider.getDimensionType()) ? 6 : 7);
            addr3.addAll(entry.getKey().subList(0, symbols));
            addr3.addSymbol(targetGateTile.getSymbolType().getOrigin());

            if (checkAddressAndEnergy(addr3).ok())
                addresses.add(entry.getKey());
        }
        if (addresses.size() == 0) return null;

        int i = (int) Math.min(Math.floor(Math.random() * addresses.size()), (addresses.size() - 1));
        if (i < 0) i = 0;
        return addresses.get(i);
    }

    @Override
    public boolean prepare(ICommandSender sender, ICommand command) {
        setLinkedDHD(null, -1);
        setLinkedCountdown(null, -1);

        return super.prepare(sender, command);
    }

    @Override
    public int getLinkId() {
        return linkId;
    }
}