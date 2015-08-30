package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.pogamut.base.agent.module.comm.CommEvent;
import cz.cuni.amis.pogamut.base3d.worldview.object.Location;

public class TargetSpotted extends CommEvent {

    private Location enemyLocation;

    public TargetSpotted(Location enemyLocation) {
        this.enemyLocation = enemyLocation;
    }

    public Location getEnemyLocation() {
        return this.enemyLocation;
    }
}
