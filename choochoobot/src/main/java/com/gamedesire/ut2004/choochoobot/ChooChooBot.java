package com.gamedesire.ut2004.choochoobot;

import cz.cuni.amis.introspection.java.JProp;
import cz.cuni.amis.pogamut.base.agent.navigation.IPathExecutorState;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.IUT2004Navigation;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004Navigation;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathAutoFixer;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathExecutorStuckState;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.astar.UT2004AStar;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.floydwarshall.FloydWarshallMap;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.navmesh.NavMeshModule;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Configuration;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.RemoveRay;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.*;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.pogamut.ut2004.utils.UnrealUtils;
import cz.cuni.amis.utils.exception.PogamutException;
import cz.cuni.amis.utils.flag.FlagListener;
import java.util.logging.Level;
import javax.vecmath.Vector3d;


@AgentScoped
public class ChooChooBot extends UT2004BotModuleController {

  
    protected static final String FRONT = "frontRay";
    protected static final String LEFT45 = "left45Ray";
    protected static final String RIGHT45 = "right45Ray";
    private AutoTraceRay left, front, right;
    /**
     * Flag indicating that the bot has been just executed.
     */
    private boolean first = true;
    private boolean raysInitialized = false;
    /**
     * Whether the left45 sensor signalizes the collision. (Computed in the
     * doLogic()) <p><p> Using {@link RaycastingBot${symbol_pound}LEFT45} as the
     * key for the ray.
     */
    @JProp
    private boolean sensorLeft45 = false;
    /**
     * Whether the right45 sensor signalizes the collision. (Computed in the
     * doLogic()) <p><p> Using {@link RaycastingBot${symbol_pound}RIGHT45} as
     * the key for the ray.
     */
    @JProp
    private boolean sensorRight45 = false;
    /**
     * Whether the front sensor signalizes the collision. (Computed in the
     * doLogic()) <p><p> Using {@link RaycastingBot${symbol_pound}FRONT} as the
     * key for the ray.
     */
    @JProp
    private boolean sensorFront = false;
    /**
     * Whether the bot is moving. (Computed in the doLogic())
     */
    @JProp
    private boolean moving = false;
    /**
     * Whether any of the sensor signalize the collision. (Computed in the
     * doLogic())
     */
    @JProp
    private boolean sensor = false;
    /**
     * How much time should we wait for the rotation to finish (milliseconds).
     */
    @JProp
    private int turnSleep = 250;
    /**
     * How fast should we move? Interval <0, 1>.
     */
    private float moveSpeed = 0.6f;
    /**
     * Small rotation (degrees).
     */
    @JProp
    private int smallTurn = 30;
    /**
     * Big rotation (degrees).
     */
    @JProp
    private int bigTurn = 90;
    /**
     * Taboo set is working as "black-list", that is you might add some
     * NavPoints to it for a certain time, marking them as "unavailable".
     */
    protected TabooSet<NavPoint> tabooNavPoints;
    /**
     * Current navigation point we're navigating to.
     */
    protected NavPoint targetNavPoint;
    /**
     * Path auto fixer watches for navigation failures and if some navigation
     * link is found to be unwalkable, it removes it from underlying navigation
     * graph.
     *
     * Note that UT2004 navigation graphs are some times VERY stupid or contains
     * VERY HARD TO FOLLOW links...
     */
    protected UT2004PathAutoFixer autoFixer;
    /**
     * Standard {@link UT2004BotModuleController${symbol_pound}getNavigation()}
     * is using {@link FloydWarshallMap} to find the path. <p><p> This {@link UT2004Navigation}
     * is initialized using {@link UT2004BotModuleController${symbol_pound}getAStar()}
     * and can be used to confirm, that
     * {@link UT2004AStar} is working in the map.
     */
    protected UT2004Navigation navigationAStar;
    /**
     * Whether to use {@link ${symbol_pound}navigationAStar} and {@link UT2004AStar}
     * (== true). <p><p> Can be configured from NetBeans plugin during runtime.
     */
    @JProp
    public boolean useAStar = false;
    /**
     * Whether to use {@link ${symbol_pound}nmNav} or standard {@link UT2004BotModuleController${symbol_pound}getNavigation()}.
     * <p><p> Can be configured from NetBeans plugin during runtime. <p><p> Note
     * that you must have corresponding .navmesh file for a current played map
     * within directory ./navmesh, more info available at {@link NavMeshModule}.
     * <p><p> Note that navigation bot comes with only three navmeshes
     * DM-TrainingDay, DM-1on1-Albatross and DM-Flux2 (see ./navmesh folder
     * within the project folder).
     */
    @JProp
    public boolean useNavMesh = false;
    /**
     * Whether we should draw the navmesh before we start running using {@link ${symbol_pound}nmNav}
     * or standard {@link UT2004BotModuleController${symbol_pound}getNavigation()}.
     * <p><p> Can be configured from NetBeans plugin during runtime.
     */
    @JProp
    public boolean drawNavMesh = true;
    protected IUT2004Navigation navigationToUse;
    private boolean navMeshDrawn = false;
    private int waitForMesh;
    private double waitingForMesh;
    private boolean offMeshLinksDrawn = false;
    private int waitForOffMeshLinks;
    private double waitingForOffMeshLinks;
    private Player boss = null;

    @Override
    public void mapInfoObtained() {
        // YOU CAN USE navBuilder IN HERE

        // IN WHICH CASE YOU SHOULD UNCOMMENT FOLLOWING LINE AFTER EVERY CHANGE
        navMeshModule.setReloadNavMesh(true); // tells NavMesh to reconstruct OffMeshPoints    	
    }

    /**
     * The bot is initialized in the environment - a physical representation of
     * the bot is present in the game.
     *
     * @param config information about configuration
     * @param init information about configuration
     */
    @Override
    public void botInitialized(GameInfo info, ConfigChange currentConfig, InitedMessage init) {
        // initialize rays for raycasting
        final int rayLength = (int) (UnrealUtils.CHARACTER_COLLISION_RADIUS * 10);
        // settings for the rays
        boolean fastTrace = true;        // perform only fast trace == we just need true/false information
        boolean floorCorrection = false; // provide floor-angle correction for the ray (when the bot is running on the skewed floor, the ray gets rotated to match the skew)
        boolean traceActor = false;      // whether the ray should collid with other actors == bots/players as well

        // 1. remove all previous rays, each bot starts by default with three
        // rays, for educational purposes we will set them manually
        getAct().act(new RemoveRay("All"));

        // 2. create new rays
        raycasting.createRay(LEFT45, new Vector3d(1, -1, 0), rayLength, fastTrace, floorCorrection, traceActor);
        raycasting.createRay(FRONT, new Vector3d(1, 0, 0), rayLength, fastTrace, floorCorrection, traceActor);
        raycasting.createRay(RIGHT45, new Vector3d(1, 1, 0), rayLength, fastTrace, floorCorrection, traceActor);
 
        // register listener called when all rays are set up in the UT engine
        raycasting.getAllRaysInitialized().addListener(new FlagListener<Boolean>() {

            @Override
            public void flagChanged(Boolean changedValue) {
                // once all rays were initialized store the AutoTraceRay objects
                // that will come in response in local variables, it is just
                // for convenience
                left = raycasting.getRay(LEFT45);
                front = raycasting.getRay(FRONT);
                right = raycasting.getRay(RIGHT45);
            }
        });
        // have you noticed the FlagListener interface? The Pogamut is often using {@link Flag} objects that
        // wraps some iteresting values that user might respond to, i.e., whenever the flag value is changed,
        // all its listeners are informed

        // 3. declare that we are not going to setup any other rays, so the 'raycasting' object may know what "all" is        
        raycasting.endRayInitSequence();

        // change bot's default speed
        config.setSpeedMultiplier(moveSpeed);

        // IMPORTANT:
        // The most important thing is this line that ENABLES AUTO TRACE functionality,
        // without ".setAutoTrace(true)" the AddRay command would be useless as the bot won't get
        // trace-lines feature activated
        getAct().act(new Configuration().setDrawTraceLines(true).setAutoTrace(true));

        // FINAL NOTE: the ray initialization must be done inside botInitialized method or later on inside
        //             botSpawned method or anytime during doLogic method

        // initialize taboo set where we store temporarily unavailable navpoints
        tabooNavPoints = new TabooSet<NavPoint>(bot);

        // auto-removes wrong navigation links between navpoints
        autoFixer = new UT2004PathAutoFixer(bot, navigation.getPathExecutor(), fwMap, aStar, navBuilder);

        // IMPORTANT
        // adds a listener to the path executor for its state changes, it will allow you to 
        // react on stuff like "PATH TARGET REACHED" or "BOT STUCK"
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
        navigationAStar.getLog().setLevel(Level.INFO);

        navigation.getLog().setLevel(Level.INFO);

        nmNav.setLogLevel(Level.INFO);
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
        } else if (useNavMesh) {
            if (nmNav.isAvailable()) {
                if (navigationToUse != nmNav) {
                    if (navigationToUse != null) {
                        navigationToUse.stopNavigation();
                    }
                    navigationToUse = nmNav;
                    info.getBotName().setInfo("NAVMESH");
                }
            } else {
                log.warning("NavMesh not available! See startup log for more details.");
            }
        }
        if (navigationToUse == null || (!useAStar && !useNavMesh)) {
            if (navigationToUse != navigation) {
                if (navigationToUse != null) {
                    navigationToUse.stopNavigation();
                }
                navigationToUse = navigation;
                info.getBotName().setInfo("FW");
            }
        }
    }

    /**
     * Main method that controls the bot.
     *
     * @throws cz.cuni.amis.pogamut.base.exceptions.PogamutException
     */
    @Override
    public void logic() throws PogamutException {
        // if the rays are not initialized yet, do nothing and wait for their initialization 
        if (!raycasting.getAllRaysInitialized().getFlag()) {
            return;
        }
        // once the rays are up and running, move according to them

        chooseNavigationToUse();
        Player newBoss = null;
        if (players.canSeePlayers()) {
            // We can see some players, let's check if we see new boss
            int bossIdHash = boss == null ? info.getId().hashCode()
                                          : boss.getId().hashCode();
            for (Player player : players.getVisiblePlayers().values()) {
                int currentIdHash = player.getId().hashCode();
                if (bossIdHash < currentIdHash) {
                    newBoss = player;
                    bossIdHash = currentIdHash;
                }
            }

            if (newBoss != null && (boss == null || newBoss.getId().equals(boss.getId()) == false)) {
                //We spotted player who should become new boss, so let's stop
                navigationToUse.stopNavigation();
                boss = newBoss;
                log.info("My new boss is: {0}", boss.getName());
            }
            if (navigationToUse.isNavigating() == false && boss != null) {
                navigationToUse.navigate(boss);
            }
        }
        else{
            boss = null;
        }
        
        if(boss == null){
            
            //We do not see any player so we run around to find someone
            sensorFront = front.isResult();
            sensorLeft45 = left.isResult();
            sensorRight45 = right.isResult();

            // is any of the sensor signalig?
            sensor = sensorFront || sensorLeft45 || sensorRight45;

            if (!sensor) {
                // no sensor are signalizes - just proceed with forward movement
                goForward();
                return;
            }

            // some sensor/s is/are signaling

            // if we're moving
            if (moving) {
                // stop it, we have to turn probably
                move.stopMovement();
                moving = false;
            }

            // according to the signals, take action...
            // 8 cases that might happen follows
            if (sensorFront) {
                if (sensorLeft45) {
                    if (sensorRight45) {
                        // LEFT45, RIGHT45, FRONT are signaling
                        move.turnHorizontal(bigTurn);
                    } else {
                        // LEFT45, FRONT45 are signaling
                        move.turnHorizontal(smallTurn);
                    }
                } else {
                    if (sensorRight45) {
                        // RIGHT45, FRONT are signaling
                        move.turnHorizontal(-smallTurn);
                    } else {
                        // FRONT is signaling
                        move.turnHorizontal(smallTurn);
                    }
                }
            } else {
                if (sensorLeft45) {
                    if (sensorRight45) {
                        // LEFT45, RIGHT45 are signaling
                        goForward();
                    } else {
                        // LEFT45 is signaling
                        move.turnHorizontal(smallTurn);
                    }
                } else {
                    if (sensorRight45) {
                        // RIGHT45 is signaling
                        move.turnHorizontal(-smallTurn);
                    } else {
                        // no sensor is signaling
                        goForward();
                    }
                }
            }
        }
    }

    /**
     * Simple method that starts continuous movement forward + marking the
     * situation (i.e., setting {@link RaycastingBot${symbol_pound}moving} to
     * true, which might be utilized later by the logic).
     */
    protected void goForward() {
        move.moveContinuos();
        moving = true;
    }

    private boolean drawNavMesh() {
        if (!navMeshDrawn) {
            navMeshDrawn = true;
            navMeshModule.getNavMeshDraw().clearAll();
            navMeshModule.getNavMeshDraw().draw(true, false);

            waitForMesh = navMeshModule.getNavMesh().getPolys().size() / 35;
            waitingForMesh = -info.getTimeDelta();
        }

        if (waitForMesh > 0) {
            waitForMesh -= info.getTimeDelta();
            waitingForMesh += info.getTimeDelta();
            if (waitingForMesh > 2) {
                waitingForMesh = 0;
            }
            if (waitForMesh > 0) {
                return false;
            }
        }

        return true;
    }

    private boolean drawOffMeshLinks() {
        if (!offMeshLinksDrawn) {
            offMeshLinksDrawn = true;

            if (navMeshModule.getNavMesh().getOffMeshPoints().isEmpty()) {
                return true;
            }

            navMeshModule.getNavMeshDraw().draw(false, true);
            waitForOffMeshLinks = navMeshModule.getNavMesh().getOffMeshPoints().size() / 10;
            waitingForOffMeshLinks = -info.getTimeDelta();
        }

        if (waitForOffMeshLinks > 0) {
            waitForOffMeshLinks -= info.getTimeDelta();
            waitingForOffMeshLinks += info.getTimeDelta();
            if (waitingForOffMeshLinks > 2) {
                waitingForOffMeshLinks = 0;
            }
            if (waitForOffMeshLinks > 0) {
                return false;
            }
        }

        return true;
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
        boss = null;
    }

    /**
     * Path executor has changed its state (note that {@link UT2004BotModuleController${symbol_pound}getPathExecutor()}
     * is internally used by
     * {@link UT2004BotModuleController${symbol_pound}getNavigation()} as
     * well!).
     *
     * @param event
     */
    protected void pathExecutorStateChange(IPathExecutorState event) {
        switch (event.getState()) {
            case PATH_COMPUTATION_FAILED:
                // if path computation fails to whatever reason, just try another navpoint
                // taboo bad navpoint for 3 minutes
                tabooNavPoints.add(targetNavPoint, 180);
                break;

            case TARGET_REACHED:
                // taboo reached navpoint for 3 minutes
                tabooNavPoints.add(targetNavPoint, 180);
                break;

            case STUCK:
                UT2004PathExecutorStuckState stuck = (UT2004PathExecutorStuckState) event;
                if (stuck.isGlobalTimeout()) {
                    //say("UT2004PathExecutor GLOBAL TIMEOUT!");
                } else {
                    //say(stuck.getStuckDetector() + " reported STUCK!");
                }
                if (stuck.getLink() == null) {
                    //say("STUCK LINK is NOT AVAILABLE!");
                } else {
                    //say("Bot has stuck while running from " + stuck.getLink().getFromNavPoint().getId() + " -> " + stuck.getLink().getToNavPoint().getId());
                }

                // the bot has stuck! ... target nav point is unavailable currently
                tabooNavPoints.add(targetNavPoint, 60);
                break;

            case STOPPED:
                // path execution has stopped
                targetNavPoint = null;
                break;
        }
    }

    public static void main(String args[]) throws PogamutException {
        // wrapped logic for bots executions, suitable to run single bot in single JVM
        new UT2004BotRunner(ChooChooBot.class, "ChooChooBot").setMain(true).startAgents(10);
    }
}