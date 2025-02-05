package tauri.dev.jsg.tileentity.util;

import tauri.dev.jsg.util.EnumKeyInterface;
import tauri.dev.jsg.util.EnumKeyMap;

public enum ReactorStateEnum implements EnumKeyInterface<Integer> {
	ONLINE(0),
	NOT_LINKED(1),
	NO_FUEL(2),
	STANDBY(3),
	NO_CRYSTAL(4);
	
	private int id;

	private ReactorStateEnum(int id) {
		this.id = id;
	}
	
	@Override
	public Integer getKey() {
		return id;
	}
	
	private static EnumKeyMap<Integer, ReactorStateEnum> enumIdMap = new EnumKeyMap<Integer, ReactorStateEnum>(values());
	
	public static ReactorStateEnum valueOf(int id) {
		return enumIdMap.valueOf(id);
	}
}
