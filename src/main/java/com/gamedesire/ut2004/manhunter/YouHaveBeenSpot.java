#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import cz.cuni.amis.pogamut.base.agent.module.comm.CommEvent;
import cz.cuni.amis.pogamut.unreal.communication.messages.UnrealId;

public class YouHaveBeenSpot extends CommEvent {
	
	private UnrealId playerId;
	
	private UnrealId seePlayerId;

	public YouHaveBeenSpot(UnrealId playerId, UnrealId seePlayerId) {
		this.playerId = playerId;
		this.seePlayerId = seePlayerId;
	}

	public UnrealId getPlayerId() {
		return playerId;
	}

	public UnrealId getSeePlayerId() {
		return seePlayerId;
	}
	
	@Override
	public String toString() {
		return "YouHaveBeenSpot[" + playerId.getStringId() + " -> " + seePlayerId.getStringId() + "]";
	}
	
}
