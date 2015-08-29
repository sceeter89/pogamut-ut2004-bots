package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.introspection.java.JProp;
import cz.cuni.amis.pogamut.base.agent.module.comm.PogamutJVMComm;
import cz.cuni.amis.pogamut.base.agent.navigation.IPathExecutorState;
import cz.cuni.amis.pogamut.base.communication.worldview.listener.annotation.EventListener;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.base3d.worldview.object.Location;
import cz.cuni.amis.pogamut.unreal.communication.messages.UnrealId;
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

    public static final double GATHER_POINT_DISTANCE = 4 * UT2004Navigation.AT_PLAYER;
    private static final int COMM_CHANNEL = 1;
    // Navigation-related fields
    protected TabooSet<NavPoint> _tabooNavPoints;
    protected NavPoint _targetNavPoint;
    protected UT2004PathAutoFixer _autoFixer;
    protected UT2004Navigation _navigationAStar;
    protected IUT2004Navigation _navigationToUse;
    @JProp
    public boolean _useAStar = false;
    public Level _navigationLogLevel = Level.WARNING;

    // Bot's state machine
    private enum State {

        Idle, Gathering, Waiting, Seeking, Hunting, EnemyKilled
    }
    private State _currentState = State.Idle;
    private Location _gatherPointLocation = null;
    private UnrealId _targetId = null;

    @Override
    public Initialize getInitializeCommand() {
        return new Initialize();//.setName("NavigationBot");
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
        _tabooNavPoints = new TabooSet<NavPoint>(bot);
        _autoFixer = new UT2004PathAutoFixer(bot, navigation.getPathExecutor(), fwMap, aStar, navBuilder);
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

        _navigationAStar = new UT2004Navigation(bot, navigation.getPathExecutor(), aStar, navigation.getBackToNavGraph(), navigation.getRunStraight());
        _navigationAStar.getLog().setLevel(_navigationLogLevel);

        navigation.getLog().setLevel(_navigationLogLevel);

        nmNav.setLogLevel(_navigationLogLevel);
    }

    @Override
    public void botFirstSpawn(GameInfo gameInfo, ConfigChange config, InitedMessage init, Self self) {
        // receive logs from the navigation so you can get a grasp on how it is working
        //navigation.getPathExecutor().getLog().setLevel(Level.ALL);
        //nmNav.setLogLevel(Level.ALL);
        //navigationAStar.getPathExecutor().getLog().setLevel(Level.ALL);

        PogamutJVMComm.getInstance().registerAgent(bot, COMM_CHANNEL);
    }

    @Override
    public void beforeFirstLogic() {
    }

    private Player getEnemyInView() {
        for (Player player : players.getVisiblePlayers().values()) {
            if (player.getName().startsWith("ManHunter") == false) {
                return player;
            }
        }

        return null;
    }

    private void resetToIdle() {
        if (_navigationToUse == null) {
            return;
        }

        _targetId = null;
        if (_navigationToUse.isNavigating()) {
            _navigationToUse.stopNavigation();
        }
        _currentState = State.Idle;
    }

    private void gatherAtNewEnemy(Player enemy) {
        _navigationToUse.navigate(enemy);
        _currentState = State.Gathering;
        if (_targetId == enemy.getId() && enemy.getLocation().equals(_gatherPointLocation)) {
            return;
        }

        _targetId = enemy.getId();
        _gatherPointLocation = enemy.getLocation();
        PogamutJVMComm.getInstance().sendToOthers(new NewGameStart(enemy.getId(), enemy.getLocation()), COMM_CHANNEL, bot);
    }
    private final static double TARGET_LOOKUP_INTERVAL_SECONDS = 0.5d;
    private double _lastTargetLookupRequest = 0;

    @Override
    public void logic() {
        info.getBotName().setInfo("State", _currentState.toString());
        chooseNavigationToUse();

        if (_navigationToUse == null) {
            return;
        }
        if (_currentState != State.Idle && players.getPlayers().containsKey(_targetId) == false) {
            resetToIdle();
            return;
        }

        switch (_currentState) {
            case Idle:
                Player newEnemy = getEnemyInView();
                if (newEnemy == null) {
                    handleNavPointNavigation();
                    double currentTime = game.getTime();
                    if (currentTime - _lastTargetLookupRequest > TARGET_LOOKUP_INTERVAL_SECONDS) {
                        PogamutJVMComm.getInstance().sendToOthers(new DoesAnyoneSeeTarget(), COMM_CHANNEL, bot);
                        _lastTargetLookupRequest = currentTime;
                    }
                } else {
                    gatherAtNewEnemy(newEnemy);
                }
                break;
            case Gathering:
                if (info.getLocation().getDistance(_gatherPointLocation) < GATHER_POINT_DISTANCE) {
                    if (players.getVisiblePlayers().containsKey(_targetId) == false) {
                        resetToIdle();
                    }
                    _navigationToUse.stopNavigation();
                    _currentState = State.Waiting;
                    return;
                }
                if (_navigationToUse.isNavigating() && _navigationToUse.getCurrentTarget().equals(_gatherPointLocation) == false) {
                    _navigationToUse.stopNavigation();
                }
                if (_navigationToUse.isNavigating() == false && _gatherPointLocation != null) {
                    _navigationToUse.navigate(_gatherPointLocation);
                }
                if (_navigationToUse.isNavigating() == false) {
                    log.warning("Current gather point location is null. Switching to Idle.");
                    resetToIdle();
                }
                break;
            case Waiting:
                move.turnTo(players.getPlayer(_targetId));
                if (info.getLocation().getDistance(players.getPlayer(_targetId).getLocation()) >= GATHER_POINT_DISTANCE) {
                    _gatherPointLocation = players.getPlayer(_targetId).getLocation();
                    _currentState = State.Gathering;
                    return;
                }
                if (players.getVisiblePlayers().containsKey(_targetId) == false) {
                    resetToIdle();
                }
                break;
        }
    }

    private void chooseNavigationToUse() {
        if (_useAStar) {
            if (_navigationToUse != _navigationAStar) {
                if (_navigationToUse != null) {
                    _navigationToUse.stopNavigation();
                }
                _navigationToUse = _navigationAStar;
                info.getBotName().setInfo("UT2004-ASTAR");
            }
        } else if (_navigationToUse == null || (!_useAStar)) {
            if (_navigationToUse != navigation) {
                if (_navigationToUse != null) {
                    _navigationToUse.stopNavigation();
                }
                _navigationToUse = navigation;
                info.getBotName().setInfo("FW");
            }
        }
    }

    private void handleNavPointNavigation() {
        if (_navigationToUse.isNavigatingToNavPoint()) {
            while (_navigationToUse.getContinueTo() == null && _navigationToUse.getRemainingDistance() < 400) {
                NavPoint nextNavPoint = getRandomNavPoint();
                _navigationToUse.setContinueTo(nextNavPoint);
            }
            return;
        }

        _targetNavPoint = getRandomNavPoint();
        if (_targetNavPoint == null) {
            log.severe("COULD NOT CHOOSE ANY NAVIGATION POINT TO RUN TO!!!");
            if (world.getAll(NavPoint.class).isEmpty()) {
                log.severe("world.getAll(NavPoint.class).size() == 0, there are no navigation ponits to choose from! Is exporting of nav points enabled in GameBots2004.ini inside UT2004?");
            }
            config.setName("NavigationBot [CRASHED]");
            return;
        }

        _navigationToUse.navigate(_targetNavPoint);
    }

    @Override
    public void botKilled(BotKilled event) {
        navigation.stopNavigation();
        resetToIdle();
    }

    protected void pathExecutorStateChange(IPathExecutorState event) {
        switch (event.getState()) {
            case PATH_COMPUTATION_FAILED:
                _tabooNavPoints.add(_targetNavPoint, 180);
                break;

            case TARGET_REACHED:
                _tabooNavPoints.add(_targetNavPoint, 180);
                break;

            case STUCK:
                UT2004PathExecutorStuckState stuck = (UT2004PathExecutorStuckState) event;
                _tabooNavPoints.add(_targetNavPoint, 60);
                break;

            case STOPPED:
                // path execution has stopped
                _targetNavPoint = null;
                break;
        }
    }

    protected NavPoint getRandomNavPoint() {
        NavPoint chosen = MyCollections.getRandomFiltered(getWorldView().getAll(NavPoint.class).values(), _tabooNavPoints);

        if (chosen != null) {
            return chosen;
        }

        log.warning("All navpoints are tabooized at this moment, choosing navpoint randomly!");

        return MyCollections.getRandom(getWorldView().getAll(NavPoint.class).values());
    }

    //Communication handling
    @EventListener(eventClass = NewGameStart.class)
    public void newGameStartReceived(NewGameStart event) {
        if (_currentState == State.Idle) {
            _gatherPointLocation = event.getEnemyLocation();
            _targetId = event.getEnemyId();
            _currentState = State.Gathering;
            return;
        }
        if (_currentState == State.Gathering) {
            _gatherPointLocation = event.getEnemyLocation();
            _targetId = event.getEnemyId();
        }
    }

    //Communication handling
    @EventListener(eventClass = DoesAnyoneSeeTarget.class)
    public void targetLookupRequestReceived(DoesAnyoneSeeTarget event) {
        if (_currentState != State.Waiting) {
            return;
        }

        PogamutJVMComm.getInstance().sendToOthers(new NewGameStart(_targetId, _gatherPointLocation), COMM_CHANNEL, bot);
    }

    @Override
    public void botShutdown() {
        PogamutJVMComm.getInstance().unregisterAgent(bot);
    }

    public static void main(String args[]) throws PogamutException {
        // wrapped logic for bots executions, suitable to run single bot in single JVM
        new UT2004BotRunner(ManHunter.class, "ManHunter").setMain(true).setLogLevel(Level.WARNING).startAgents(5);
    }
}
