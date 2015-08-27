package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.pogamut.base.agent.module.comm.CommEvent;
import cz.cuni.amis.pogamut.unreal.communication.messages.UnrealId;

public class TargetEliminated extends CommEvent {
	
	private UnrealId enemyId;

	public TargetEliminated(UnrealId enemyId) {
		this.enemyId = enemyId;
	}

	public UnrealId getEnemyId() {
		return this.enemyId;
	}
}
