package tauri.dev.jsg.item.linkable.gdo;

import io.netty.buffer.ByteBuf;
import tauri.dev.jsg.JSG;
import tauri.dev.jsg.advancements.JSGAdvancements;
import tauri.dev.jsg.item.JSGItems;
import tauri.dev.jsg.stargate.codesender.PlayerCodeSender;
import tauri.dev.jsg.stargate.network.StargateNetwork;
import tauri.dev.jsg.tileentity.stargate.StargateAbstractBaseTile;
import tauri.dev.jsg.tileentity.stargate.StargateClassicBaseTile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class GDOActionPacketToServer implements IMessage {
	public GDOActionPacketToServer() {}
	
	private GDOActionEnum action;
	private EnumHand hand;
	private boolean next;
	private int code;
	
	public GDOActionPacketToServer(GDOActionEnum action, EnumHand hand, int code, boolean next) {
		this.action = action;
		this.hand = hand;
		this.code = code;
		this.next = next;
	}

	public GDOActionPacketToServer(GDOActionEnum action, EnumHand hand, boolean next) {
		this.action = action;
		this.hand = hand;
		this.code = -1;
		this.next = next;
	}
	
	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeInt(action.ordinal());
		buf.writeInt(hand == EnumHand.MAIN_HAND ? 0 : 1);
		buf.writeInt(code);
		buf.writeBoolean(next);
	}
	
	@Override
	public void fromBytes(ByteBuf buf) {
		action = GDOActionEnum.values()[buf.readInt()];
		hand = buf.readInt() == 0 ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
		code = buf.readInt();
		next = buf.readBoolean();
	}

	public static class GDOActionPacketServerHandler implements IMessageHandler<GDOActionPacketToServer, IMessage> {

		@Override
		public IMessage onMessage(GDOActionPacketToServer message, MessageContext ctx) {
			EntityPlayerMP player = ctx.getServerHandler().player;
			WorldServer world = player.getServerWorld();

			world.addScheduledTask(() -> {
				ItemStack stack = player.getHeldItem(message.hand);
				if (stack.getItem() == JSGItems.GDO && stack.hasTagCompound()) {
					NBTTagCompound compound = stack.getTagCompound();
					GDOMode mode = GDOMode.valueOf(compound.getByte("mode"));
					switch (message.action) {
						case SEND_CODE:
							if (compound.hasKey("linkedGate")) {
								try {
									BlockPos pos = BlockPos.fromLong(compound.getLong("linkedGate"));
									StargateClassicBaseTile gateTile = (StargateClassicBaseTile) world.getTileEntity(pos);
									if (gateTile == null || gateTile.getDialedAddress() == null) return;
									// todo both direction code sending
									StargateAbstractBaseTile targetGate = null;
									if (gateTile.getStargateState().initiating() || gateTile.getStargateState().engaged()) {
										targetGate = StargateNetwork.get(world).getStargate(gateTile.getDialedAddress()).getTileEntity();
										if (targetGate != null && targetGate instanceof StargateClassicBaseTile) {
											if(((StargateClassicBaseTile) targetGate).receiveIrisCode(new PlayerCodeSender(player), message.code)) {
												JSGAdvancements.GDO_USED.trigger(player);
											}
										}
									}
								}
								catch (NullPointerException e) {
									JSG.error("Exception in GDO Action packet", e);
								}
							}
							break;
						case MODE_CHANGE:
							if (message.next)
								mode = mode.next();
							else
								mode = mode.prev();

							compound.setByte("mode", mode.id);
							break;

						case ADDRESS_CHANGE:
							byte selected = compound.getByte("selected");
							int addressCount = compound.getTagList(mode.tagListName, Constants.NBT.TAG_COMPOUND).tagCount();

							if (message.next && selected < addressCount-1)
								compound.setByte("selected", (byte) (selected+1));

							if (!message.next && selected > 0)
								compound.setByte("selected", (byte) (selected-1));
							break;
					}
				}
			});
			
			return null;
		}
		
	}
}
