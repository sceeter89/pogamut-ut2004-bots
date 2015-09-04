package com.gamedesire.ut2004.manhunter;

import cz.cuni.amis.introspection.java.JProp;
import cz.cuni.amis.pogamut.base.agent.module.comm.PogamutJVMComm;
import cz.cuni.amis.pogamut.base.agent.navigation.IPathExecutorState;
import cz.cuni.amis.pogamut.base.communication.worldview.listener.annotation.EventListener;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.base3d.worldview.object.Location;
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.*;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004Bot;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.communication.messages.ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.UT2004ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Rotate;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.StopShooting;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.*;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.utils.collections.MyCollections;
import cz.cuni.amis.utils.exception.PogamutException;
import cz.cuni.amis.utils.flag.FlagListener;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@AgentScoped
public class ManHunter extends UT2004BotModuleController {

    private static final double GATHER_POINT_DISTANCE = 4 * UT2004Navigation.AT_PLAYER;
    private static final int COMM_CHANNEL = 1;
    // Navigation-related fields
    private TabooSet<NavPoint> _tabooNavPoints;
    private NavPoint _targetNavPoint;
    private UT2004PathAutoFixer _autoFixer;
    private UT2004Navigation _navigationAStar;
    private IUT2004Navigation _navigationToUse;
    @JProp
    public boolean _useAStar = false;
    private Level _navigationLogLevel = Level.WARNING;
    //Hunting related fields
    private int healthLevel = 75;
    private int frags = 0;
    private int deaths = 0;
    private Item item = null;
    private TabooSet<Item> tabooItems = null;
    private Location _recentlySeenLocation = null;

    private boolean areAllBotsReady() {
        boolean all_ready = true;
        for (Player player : players.getPlayers().values()) {
            if (player.getName().startsWith("ManHunter") && player.getName().contains("Waiting") == false) {
                return false;
            }
        }
        return true;
    }

    // Bot's state machine
    private enum State {

        Idle, Gathering, Waiting, CountDown, Exterminating
    }
    private State _currentState = State.Idle;
    private Location _gatherPointLocation = null;
    private Player _target = null;
    private static int _instanceCount = 0;

    @Override
    public Initialize getInitializeCommand() {
        return new Initialize().setName("ManHunter" + (++_instanceCount)).setDesiredSkill(5);
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
        PogamutJVMComm.getInstance().registerAgent(bot, COMM_CHANNEL);
    }

    @Override
    public void beforeFirstLogic() {
    }

    @Override
    public void prepareBot(UT2004Bot bot) {
        tabooItems = new TabooSet<Item>(bot);

        _autoFixer = new UT2004PathAutoFixer(bot, navigation.getPathExecutor(), fwMap, aStar, navBuilder); // auto-removes wrong navigation links between navpoints

        // listeners        
        navigation.getState().addListener(new FlagListener<NavigationState>() {

            @Override
            public void flagChanged(NavigationState changedValue) {
                switch (changedValue) {
                    case PATH_COMPUTATION_FAILED:
                    case STUCK:
                        if (item != null) {
                            tabooItems.add(item, 10);
                        }
                        reset();
                        break;

                    case TARGET_REACHED:
                        reset();
                        break;
                }
            }
        });

        // DEFINE WEAPON PREFERENCES
        // FIRST we DEFINE GENERAL WEAPON PREFERENCES
        weaponPrefs.addGeneralPref(UT2004ItemType.MINIGUN, false);
        weaponPrefs.addGeneralPref(UT2004ItemType.LINK_GUN, false);
        weaponPrefs.addGeneralPref(UT2004ItemType.LIGHTNING_GUN, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.SHOCK_RIFLE, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.ROCKET_LAUNCHER, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.ASSAULT_RIFLE, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.FLAK_CANNON, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.BIO_RIFLE, true);

        // AND THEN RANGED
        weaponPrefs.newPrefsRange(80).add(UT2004ItemType.SHIELD_GUN, true);

        weaponPrefs.newPrefsRange(1000).add(UT2004ItemType.FLAK_CANNON, true).add(UT2004ItemType.MINIGUN, true).add(UT2004ItemType.LINK_GUN, false).add(UT2004ItemType.ASSAULT_RIFLE, true);

        weaponPrefs.newPrefsRange(4000).add(UT2004ItemType.SHOCK_RIFLE, true).add(UT2004ItemType.MINIGUN, false);

        weaponPrefs.newPrefsRange(100000).add(UT2004ItemType.LIGHTNING_GUN, true).add(UT2004ItemType.SHOCK_RIFLE, true);
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

        _currentState = State.Idle;
        _target = null;
        if (_navigationToUse.isNavigating()) {
            _navigationToUse.stopNavigation();
        }
    }

    private void gatherAtNewEnemy(Player enemy) {
        _navigationToUse.navigate(enemy);
        _currentState = State.Gathering;
        if (_target != null && enemy.getId().equals(_target.getId()) && enemy.getLocation().equals(_gatherPointLocation)) {
            return;
        }

        _target = enemy;
        _gatherPointLocation = enemy.getLocation();
        PogamutJVMComm.getInstance().sendToOthers(new NewGameStart(enemy), COMM_CHANNEL, bot);
    }
    private final static double TARGET_LOOKUP_INTERVAL_SECONDS = 0.5d;
    private double _lastTargetLookupRequest = 0;
    private double _startTime = 0;

    @Override
    public void logic() {
        info.getBotName().setInfo("State", _currentState.toString());
        chooseNavigationToUse();

        if (_navigationToUse == null) {
            return;
        }

        if (_currentState.equals(State.Idle) == false && _target == null) {
            log.warning("Lost target... switching back to Idle");
            resetToIdle();
            return;
        }


        if (_currentState.equals(State.Idle) == false && players.getPlayers().containsKey(_target.getId()) == false) {
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
                    if (players.getVisiblePlayer(_target.getId()) == null) {
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
                move.turnTo(_target);
                if (info.getLocation().getDistance(_target.getLocation()) >= GATHER_POINT_DISTANCE) {
                    _gatherPointLocation = _target.getLocation();
                    _currentState = State.Gathering;
                    return;
                }
                if (_target.isVisible() == false) {
                    resetToIdle();
                }
                if (bot.getName().equals("ManHunter1")) {
                    boolean all_ready = areAllBotsReady();
                    if (all_ready) {
                        body.getCommunication().sendGlobalTextMessage("We're all ready! You have 10 seconds to escape. Run!");
                        _currentState = State.CountDown;
                        PogamutJVMComm.getInstance().sendToOthers(new CountdownStarted(), COMM_CHANNEL, bot);
                        _startTime = game.getTime() + 10;
                    }
                }
                break;
            case CountDown:
                if (bot.getName().equals("ManHunter1") == false) {
                    return;
                }
                if (!areAllBotsReady())
                    PogamutJVMComm.getInstance().sendToOthers(new NewGameStart(null), COMM_CHANNEL, bot);
                int secondsLeft = (int) (_startTime - game.getTime());
                if (secondsLeft <= 3 && secondsLeft > 0) {
                    body.getCommunication().sendGlobalTextMessage(Integer.toString(secondsLeft) + "...");
                }
                if (secondsLeft <= 0) {
                    body.getCommunication().sendGlobalTextMessage("START!");
                    body.getCommunication().sendGlobalTextMessage("Prepare to die " + _target.getName() + "!");
                    PogamutJVMComm.getInstance().sendToOthers(new StartGame(), COMM_CHANNEL, bot);
                    _currentState = State.Exterminating;
                }
                break;
            case Exterminating:
                if (_target.isVisible() && weaponry.hasLoadedWeapon()) {
                    PogamutJVMComm.getInstance().sendToOthers(new TargetSpotted(_target.getLocation()), COMM_CHANNEL, bot);
                    stateEngage();
                    return;
                }

                if (info.isShooting() || info.isSecondaryShooting()) {
                    getAct().act(new StopShooting());
                }

                if (senses.isBeingDamaged()) {
                    this.stateHit();
                    return;
                }

                if (_recentlySeenLocation != null && weaponry.hasLoadedWeapon()) {  // !enemy.isVisible() because of 2)
                    this.statePursue();
                    return;
                }

                if (info.getHealth() < healthLevel) {
                    this.stateMedKit();
                    return;
                }

                stateRunAroundItems();
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
            }
        } else if (_navigationToUse == null || (!_useAStar)) {
            if (_navigationToUse != navigation) {
                if (_navigationToUse != null) {
                    _navigationToUse.stopNavigation();
                }
                _navigationToUse = navigation;
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
            config.setName("ManHunter [CRASHED]");
            return;
        }

        _navigationToUse.navigate(_targetNavPoint);
    }

    @Override
    public void botKilled(BotKilled event) {
        navigation.stopNavigation();
        //resetToIdle();
        reset();
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
            _target = event.getEnemy();
            _gatherPointLocation = _target.getLocation();
            _currentState = State.Gathering;
            return;
        }
        if (_currentState == State.Gathering) {
            _target = event.getEnemy();
            _gatherPointLocation = _target.getLocation();
        }
        if(_currentState == State.CountDown){
            resetToIdle();
        }
    }

    //Communication handling
    @EventListener(eventClass = DoesAnyoneSeeTarget.class)
    public void targetLookupRequestReceived(DoesAnyoneSeeTarget event) {
        if (_currentState != State.Waiting) {
            return;
        }
        if (_target == null) {
            resetToIdle();
        } else {
            PogamutJVMComm.getInstance().sendToOthers(new NewGameStart(_target), COMM_CHANNEL, bot);
        }
    }

    @EventListener(eventClass = StartGame.class)
    public void newGameHasStarted(StartGame event) {
        if (_currentState != State.CountDown) {
            return;
        }

        _currentState = State.Exterminating;
    }

    @EventListener(eventClass = CountdownStarted.class)
    public void startCountdown(CountdownStarted event) {
        if (_currentState != State.Waiting) {
            return;
        }

        _currentState = State.CountDown;
    }

    @EventListener(eventClass = TargetSpotted.class)
    public void targetSpotted(TargetSpotted event) {
        if (_currentState != State.Exterminating) {
            return;
        }

        _recentlySeenLocation = event.getEnemyLocation();
    }

    @EventListener(eventClass = PlayerKilled.class)
    public void playerKilled(PlayerKilled event) {
        if (_currentState.equals(State.Exterminating) == false) {
            return;
        }

        if (event.getId().equals(_target.getId())) {
            if (event.getKiller().equals(info.getId())) {
                body.getCommunication().sendGlobalTextMessage("Haha! You died looser!");
            }
            if (bot.getName().equals("ManHunter1")) {
                double score = game.getTime() - _startTime;
                body.getCommunication().sendGlobalTextMessage("Your score is: " + Double.toString(score) + " seconds. Congratulations!");
            }

            resetToIdle();
        }
    }

    @Override
    public void botShutdown() {
        PogamutJVMComm.getInstance().unregisterAgent(bot);
    }

    protected void stateEngage() {
        boolean shooting = false;
        double distance = Double.MAX_VALUE;
        pursueCount = 0;

        // 1) pick new enemy if the old one has been lost
        if (_target.isVisible() == false) {
            // pick new enemy
            if (_recentlySeenLocation != null) {
                _navigationToUse.navigate(_recentlySeenLocation);
            }
            if (info.isShooting() || info.isSecondaryShooting()) {
                // stop shooting
                getAct().act(new StopShooting());
            }
        } else {
            distance = info.getLocation().getDistance(_target.getLocation());
            if (shoot.shoot(weaponPrefs, _target) != null) {
                shooting = true;
            }
        }

        // 3) if enemy is far or not visible - run to him
        int decentDistance = Math.round(random.nextFloat() * 800) + 200;
        if (!shooting || decentDistance < distance) {
            if (_navigationToUse.isNavigating() == false) {
                navigation.navigate(_target);
            }
        } else {
            navigation.stopNavigation();
        }

        item = null;
    }

    protected void stateHit() {
        //log.info("Decision is: HIT");
        if (navigation.isNavigating()) {
            navigation.stopNavigation();
            item = null;
        }
        getAct().act(new Rotate().setAmount(32000));
    }

    protected void statePursue() {
        //log.info("Decision is: PURSUE");
        ++pursueCount;
        if (pursueCount > 30) {
            reset();
        }
        if (_target != null) {
            navigation.navigate(_target);
            item = null;
        } else {
            reset();
        }
    }
    protected int pursueCount = 0;

    protected void stateMedKit() {
        //log.info("Decision is: MEDKIT");
        Item newItem = items.getPathNearestSpawnedItem(ItemType.Category.HEALTH);
        if (newItem == null) {
            stateRunAroundItems();
        } else {
            navigation.navigate(newItem);
            this.item = newItem;
        }
    }
    protected List<Item> itemsToRunAround = null;

    protected void stateRunAroundItems() {
        //log.info("Decision is: ITEMS");
        //config.setName("Hunter [ITEMS]");
        if (navigation.isNavigatingToItem()) {
            return;
        }

        List<Item> interesting = new ArrayList<Item>();

        // ADD WEAPONS
        for (ItemType itemType : ItemType.Category.WEAPON.getTypes()) {
            if (!weaponry.hasLoadedWeapon(itemType)) {
                interesting.addAll(items.getSpawnedItems(itemType).values());
            }
        }
        // ADD ARMORS
        for (ItemType itemType : ItemType.Category.ARMOR.getTypes()) {
            interesting.addAll(items.getSpawnedItems(itemType).values());
        }
        // ADD QUADS
        interesting.addAll(items.getSpawnedItems(UT2004ItemType.U_DAMAGE_PACK).values());
        // ADD HEALTHS
        if (info.getHealth() < 100) {
            interesting.addAll(items.getSpawnedItems(UT2004ItemType.HEALTH_PACK).values());
        }

        Item newItem = MyCollections.getRandom(tabooItems.filter(interesting));
        if (newItem == null) {
            if (navigation.isNavigating()) {
                return;
            }
            navigation.navigate(navPoints.getRandomNavPoint());
        } else {
            item = newItem;
            navigation.navigate(item);
        }
    }

    protected void reset() {
        item = null;
        navigation.stopNavigation();
        itemsToRunAround = null;
    }

    public static void main(String args[]) throws PogamutException {
        // wrapped logic for bots executions, suitable to run single bot in single JVM
        new UT2004BotRunner(ManHunter.class, "ManHunter").setMain(true).setLogLevel(Level.WARNING).startAgents(5);
    }
}
