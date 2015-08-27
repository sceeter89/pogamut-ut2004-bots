package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.pogamut.base.agent.module.comm.CommEvent;
import cz.cuni.amis.pogamut.base3d.worldview.object.Location;
import cz.cuni.amis.pogamut.unreal.communication.messages.UnrealId;

public class NewGameStart extends CommEvent {
	
	private UnrealId enemyId;
	
	private Location enemyLocation;

	public NewGameStart(UnrealId enemyId, Location enemyLocation) {
		this.enemyId = enemyId;
		this.enemyLocation = enemyLocation;
	}

	public UnrealId getEnemyId() {
		return this.enemyId;
	}

	public Location getEnemyLocation() {
		return this.enemyLocation;
	}	
}
