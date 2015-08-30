package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.pogamut.base.agent.module.comm.CommEvent;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.Player;

public class NewGameStart extends CommEvent {
	private Player _enemy;
	
	public NewGameStart(Player enemy) {
		_enemy = enemy;
	}

	public Player getEnemy() {
		return _enemy;
	}
}
