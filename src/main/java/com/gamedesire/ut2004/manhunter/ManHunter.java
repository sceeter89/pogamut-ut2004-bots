package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.introspection.java.JProp;
import cz.cuni.amis.pogamut.base.agent.navigation.IPathExecutorState;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.IUT2004Navigation;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004Navigation;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathAutoFixer;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathExecutorStuckState;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.*;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.utils.collections.MyCollections;
import cz.cuni.amis.utils.exception.PogamutException;
import cz.cuni.amis.utils.flag.FlagListener;
import java.util.logging.Level;

@AgentScoped
public class ManHunter extends UT2004BotModuleController {
    // Navigation-related fields

    protected TabooSet<NavPoint> tabooNavPoints;
    protected NavPoint targetNavPoint;
    protected UT2004PathAutoFixer autoFixer;
    protected UT2004Navigation navigationAStar;
    protected IUT2004Navigation navigationToUse;
    @JProp
    public boolean useAStar = false;
    public Level navigationLogLevel = Level.WARNING;

    @Override
    public Initialize getInitializeCommand() {
        return new Initialize().setName("NavigationBot");
    }

    @Override
    public void mapInfoObtained() {
        navMeshModule.setReloadNavMesh(true); // tells NavMesh to reconstruct OffMeshPoints    	
    }

    @SuppressWarnings("unchecked")
    @Override
    public void botInitialized(GameInfo gameInfo, ConfigChange config, InitedMessage init) {

        initializeNavigation();
    }

    private void initializeNavigation() throws SecurityException {
        tabooNavPoints = new TabooSet<NavPoint>(bot);
        autoFixer = new UT2004PathAutoFixer(bot, navigation.getPathExecutor(), fwMap, aStar, navBuilder);
        navigation.getPathExecutor().getState().addStrongListener(new FlagListener<IPathExecutorState>() {

            @Override
            public void flagChanged(IPathExecutorState changedValue) {
                pathExecutorStateChange(changedValue);
            }
        });
        nmNav.getPathExecutor().getState().addStrongListener(new FlagListener<IPathExecutorState>() {

            @Override
            public void flagChanged(IPathExecutorState changedValue) {
                pathExecutorStateChange(changedValue);
            }
        });

        navigationAStar = new UT2004Navigation(bot, navigation.getPathExecutor(), aStar, navigation.getBackToNavGraph(), navigation.getRunStraight());
        navigationAStar.getLog().setLevel(navigationLogLevel);

        navigation.getLog().setLevel(navigationLogLevel);

        nmNav.setLogLevel(navigationLogLevel);
    }

    @Override
    public void botFirstSpawn(GameInfo gameInfo, ConfigChange config, InitedMessage init, Self self) {
        // receive logs from the navigation so you can get a grasp on how it is working
        //navigation.getPathExecutor().getLog().setLevel(Level.ALL);
        //nmNav.setLogLevel(Level.ALL);
        //navigationAStar.getPathExecutor().getLog().setLevel(Level.ALL);
    }

    @Override
    public void beforeFirstLogic() {
    }

    @Override
    public void logic() {
        chooseNavigationToUse();

        if (players.canSeePlayers() || navigationToUse.getCurrentTargetPlayer() != null) {
            handlePlayerNavigation();
        } else {
            handleNavPointNavigation();
        }
    }

    private void chooseNavigationToUse() {
        if (useAStar) {
            if (navigationToUse != navigationAStar) {
                if (navigationToUse != null) {
                    navigationToUse.stopNavigation();
                }
                navigationToUse = navigationAStar;
                info.getBotName().setInfo("UT2004-ASTAR");
            }
        } else if (navigationToUse == null || (!useAStar)) {
            if (navigationToUse != navigation) {
                if (navigationToUse != null) {
                    navigationToUse.stopNavigation();
                }
                navigationToUse = navigation;
                info.getBotName().setInfo("FW");
            }
        }
    }

    private void handlePlayerNavigation() {
        if (navigationToUse.isNavigating() && navigationToUse.getCurrentTargetPlayer() != null) {
            return;
        }

        // NAVIGATION HAS STOPPED ... 
        // => we need to choose another player to navigate to

        Player player = players.getNearestVisiblePlayer();
        if (player == null) {
            // NO PLAYERS AT SIGHT
            // => navigate to random navpoint
            handleNavPointNavigation();
            return;
        }

        // CHECK DISTANCE TO THE PLAYER ...
        if (info.getLocation().getDistance(player.getLocation()) < UT2004Navigation.AT_PLAYER) {
            // PLAYER IS NEXT TO US... 
            // => talk to player	
            return;
        }

        navigationToUse.navigate(player);
    }

    private void handleNavPointNavigation() {
        if (navigationToUse.isNavigatingToNavPoint()) {
            // IS TARGET CLOSE & NEXT TARGET NOT SPECIFIED?
            while (navigationToUse.getContinueTo() == null && navigationToUse.getRemainingDistance() < 400) {
                // YES, THERE IS NO "next-target" SET AND WE'RE ABOUT TO REACH OUR TARGET!
                NavPoint nextNavPoint = getRandomNavPoint();
                navigationToUse.setContinueTo(nextNavPoint);
                // note that it is WHILE because navigation may immediately eat up "next target" and next target may be actually still too close!
            }

            return;
        }

        // NAVIGATION HAS STOPPED ... 
        // => we need to choose another navpoint to navigate to
        // => possibly follow some players ...

        targetNavPoint = getRandomNavPoint();
        if (targetNavPoint == null) {
            log.severe("COULD NOT CHOOSE ANY NAVIGATION POINT TO RUN TO!!!");
            if (world.getAll(NavPoint.class).isEmpty()) {
                log.severe("world.getAll(NavPoint.class).size() == 0, there are no navigation ponits to choose from! Is exporting of nav points enabled in GameBots2004.ini inside UT2004?");
            }
            config.setName("NavigationBot [CRASHED]");
            return;
        }


        navigationToUse.navigate(targetNavPoint);
    }

    /**
     * Called each time our bot die. Good for reseting all bot state dependent
     * variables.
     *
     * @param event
     */
    @Override
    public void botKilled(BotKilled event) {
        navigation.stopNavigation();
    }

    /**
     * Path executor has changed its state (note that {@link UT2004BotModuleController#getPathExecutor()}
     * is internally used by
     * {@link UT2004BotModuleController#getNavigation()} as well!).
     *
     * @param event
     */
    protected void pathExecutorStateChange(IPathExecutorState event) {
        switch (event.getState()) {
            case PATH_COMPUTATION_FAILED:
                tabooNavPoints.add(targetNavPoint, 180);
                break;

            case TARGET_REACHED:
                tabooNavPoints.add(targetNavPoint, 180);
                break;

            case STUCK:
                UT2004PathExecutorStuckState stuck = (UT2004PathExecutorStuckState) event;
                tabooNavPoints.add(targetNavPoint, 60);
                break;

            case STOPPED:
                // path execution has stopped
                targetNavPoint = null;
                break;
        }
    }

    /**
     * Randomly picks some navigation point to head to.
     *
     * @return randomly choosed navpoint
     */
    protected NavPoint getRandomNavPoint() {
        NavPoint chosen = MyCollections.getRandomFiltered(getWorldView().getAll(NavPoint.class).values(), tabooNavPoints);

        if (chosen != null) {
            return chosen;
        }

        log.warning("All navpoints are tabooized at this moment, choosing navpoint randomly!");

        return MyCollections.getRandom(getWorldView().getAll(NavPoint.class).values());
    }

    public static void main(String args[]) throws PogamutException {
        // wrapped logic for bots executions, suitable to run single bot in single JVM
        new UT2004BotRunner(ManHunter.class, "ManHunter").setMain(true).setLogLevel(Level.WARNING).startAgent();
    }
}
