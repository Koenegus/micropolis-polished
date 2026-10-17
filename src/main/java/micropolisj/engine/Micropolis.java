// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.ALLBITS;
import static micropolisj.engine.TileConstants.LOMASK;
import static micropolisj.engine.TileConstants.getTileBehavior;
import static micropolisj.engine.TileConstants.getZoneSizeFor;
import static micropolisj.engine.TileConstants.isZoneCenter;

import java.io.DataInput;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The main simulation engine coordinator for Micropolis. The front-end should call {@link
 * #animate()} periodically to move the simulation forward in time.
 *
 * <p>Domain responsibilities are delegated to specialized collaborators:
 *
 * <ul>
 *   <li>{@link BudgetManager} - finances, taxes, service funding, and financial history
 *   <li>{@link CensusManager} - census counters, RCI demand valves, and historical graphs
 *   <li>{@link PowerManager} - electrical power grid propagation and zone power states
 *   <li>{@link MapAnalysis} - spatial overlay matrices and periodic scans
 *   <li>{@link SpriteManager} - mobile entities and explosion sprites
 *   <li>{@link CityNotificationManager} - status messages, growth alerts, sounds, and listeners
 *   <li>{@link DisasterManager} - disasters simulation and triggering
 *   <li>{@link CityPersistence} - binary save/load operations
 *   <li>{@link CityEval} - mayor approval and city problem evaluation
 * </ul>
 */
public class Micropolis {
  public static final int CENSUSRATE = CensusManager.CENSUSRATE;
  static final Random DEFAULT_PRNG = new Random();
  private static final int DEFAULT_WIDTH = 120;
  private static final int DEFAULT_HEIGHT = 100;
  private static final int TAXFREQ = BudgetManager.TAXFREQ;

  private final Random random;
  private final CityNotificationManager notificationManager;
  private final BudgetManager budgetManager;
  private final CensusManager censusManager;
  private final PowerManager powerManager;
  private final MapAnalysis mapAnalysis;
  private final SpriteManager spriteManager;
  private final CityEval evaluation;
  private final DisasterManager disasterManager;
  private final CityPersistence persistence;

  private char[][] map;
  private boolean autoBulldoze = true;
  private boolean autoGo;
  private Speed simSpeed = Speed.NORMAL;
  private int gameLevel;
  private int cityTime; // counts "weeks" (actually, 1/48'ths years)
  private int acycle; // animation cycle (mod 960)
  private int scycle; // same as cityTime, except mod 1024
  private int fcycle; // counts simulation steps (mod 1024)
  private Map<String, TileBehavior> tileBehaviors;

  public Micropolis() {
    random = DEFAULT_PRNG;
    notificationManager = new CityNotificationManager(this);
    budgetManager = new BudgetManager(this);
    censusManager = new CensusManager(this);
    powerManager = new PowerManager(this);
    mapAnalysis = new MapAnalysis(this);
    spriteManager = new SpriteManager(this);
    evaluation = new CityEval(this);
    disasterManager = new DisasterManager(this);
    persistence = new CityPersistence(this);
    init(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    initTileBehaviors();
  }

  static boolean isTileDozeable(ToolEffectIfc eff) {
    int myTile = eff.getTile(0, 0);
    TileSpec ts = Tiles.get(myTile);
    if (ts.isCanBulldoze()) {
      return true;
    }

    if (ts.getOwner() != null) {
      // part of a zone; only bulldozeable if the owner tile is
      // no longer intact.
      int baseTile = eff.getTile(-ts.getOwnerOffsetX(), -ts.getOwnerOffsetY());
      return ts.getOwner().getTileNumber() != baseTile;
    }

    return false;
  }

  private void init(int width, int height) {
    map = new char[height][width];
    powerManager.init(width, height);
    mapAnalysis.init(width, height);
  }

  private void initTileBehaviors() {
    Map<String, TileBehavior> bb = new HashMap<>();

    bb.put("FIRE", new TerrainBehavior(this, TerrainBehavior.B.FIRE));
    bb.put("FLOOD", new TerrainBehavior(this, TerrainBehavior.B.FLOOD));
    bb.put("RADIOACTIVE", new TerrainBehavior(this, TerrainBehavior.B.RADIOACTIVE));
    bb.put("ROAD", new TerrainBehavior(this, TerrainBehavior.B.ROAD));
    bb.put("RAIL", new TerrainBehavior(this, TerrainBehavior.B.RAIL));
    bb.put("EXPLOSION", new TerrainBehavior(this, TerrainBehavior.B.EXPLOSION));
    bb.put("RESIDENTIAL", new MapScanner(this, Behavior.RESIDENTIAL));
    bb.put("HOSPITAL_CHURCH", new MapScanner(this, Behavior.HOSPITAL_CHURCH));
    bb.put("COMMERCIAL", new MapScanner(this, Behavior.COMMERCIAL));
    bb.put("INDUSTRIAL", new MapScanner(this, Behavior.INDUSTRIAL));
    bb.put("COAL", new MapScanner(this, Behavior.COAL));
    bb.put("NUCLEAR", new MapScanner(this, Behavior.NUCLEAR));
    bb.put("FIRESTATION", new MapScanner(this, Behavior.FIRESTATION));
    bb.put("POLICESTATION", new MapScanner(this, Behavior.POLICESTATION));
    bb.put("STADIUM_EMPTY", new MapScanner(this, Behavior.STADIUM_EMPTY));
    bb.put("STADIUM_FULL", new MapScanner(this, Behavior.STADIUM_FULL));
    bb.put("AIRPORT", new MapScanner(this, Behavior.AIRPORT));
    bb.put("SEAPORT", new MapScanner(this, Behavior.SEAPORT));
    bb.put("UNIVERSITY", new MapScanner(this, Behavior.UNIVERSITY));

    tileBehaviors = bb;
  }

  // =========================================================================
  // Core Simulation Loop & Map Operations
  // =========================================================================

  public void animate() {
    acycle = (acycle + 1) % 960;
    if (acycle % 2 == 0) {
      step();
    }
    spriteManager.moveObjects();
    animateTiles();
  }

  private void step() {
    fcycle = (fcycle + 1) % 1024;
    simulate(fcycle % 16);
  }

  private void simulate(int mod16) {
    int band = getWidth() / 8;

    switch (mod16) {
      case 0:
        scycle = (scycle + 1) % 1024;
        cityTime++;
        if (scycle % 2 == 0) {
          censusManager.setValves();
        }
        censusManager.clearCensus();
        break;

      case 1:
        mapScan(0, band);
        break;

      case 2:
        mapScan(band, 2 * band);
        break;

      case 3:
        mapScan(2 * band, 3 * band);
        break;

      case 4:
        mapScan(3 * band, 4 * band);
        break;

      case 5:
        mapScan(4 * band, 5 * band);
        break;

      case 6:
        mapScan(5 * band, 6 * band);
        break;

      case 7:
        mapScan(6 * band, 7 * band);
        break;

      case 8:
        mapScan(7 * band, getWidth());
        break;

      case 9:
        if (cityTime % CENSUSRATE == 0) {
          censusManager.takeCensus();

          if (cityTime % (CENSUSRATE * 12) == 0) {
            censusManager.takeCensus2();
          }

          fireCensusChanged();
        }

        budgetManager.collectTaxPartial();

        if (cityTime % TAXFREQ == 0) {
          budgetManager.collectTax();
          evaluation.cityEvaluation();
        }
        break;

      case 10:
        if (scycle % 5 == 0) { // every ~10 weeks
          mapAnalysis.decROGMem();
        }
        mapAnalysis.decTrafficMem();
        fireMapOverlayDataChanged(); // TDMAP
        fireMapOverlayDataChanged(); // RDMAP
        fireMapOverlayDataChanged(); // ALMAP
        fireMapOverlayDataChanged(); // REMAP
        fireMapOverlayDataChanged(); // COMAP
        fireMapOverlayDataChanged(); // INMAP
        notificationManager.doMessages();
        break;

      case 11:
        powerManager.powerScan();
        fireMapOverlayDataChanged();
        break;

      case 12:
        mapAnalysis.ptlScan();
        break;

      case 13:
        mapAnalysis.crimeScan();
        break;

      case 14:
        mapAnalysis.popDenScan();
        break;

      case 15:
        mapAnalysis.fireAnalysis();
        disasterManager.doDisasters();
        break;

      default:
        throw new RuntimeException("unreachable");
    }
  }

  private void mapScan(int x0, int x1) {
    for (int x = x0; x < x1; x++) {
      for (int y = 0; y < getHeight(); y++) {
        mapScanTile(x, y);
      }
    }
  }

  private void mapScanTile(int xpos, int ypos) {
    int tile = getTile(xpos, ypos);
    String behaviorStr = getTileBehavior(tile);
    if (behaviorStr == null) {
      return; // nothing to do
    }

    TileBehavior b = tileBehaviors.get(behaviorStr);
    if (b != null) {
      b.processTile(xpos, ypos);
    } else {
      throw new RuntimeException("Unknown behavior: " + behaviorStr);
    }
  }

  private void animateTiles() {
    for (int y = 0; y < map.length; y++) {
      for (int x = 0; x < map[y].length; x++) {
        char tilevalue = map[y][x];
        TileSpec spec = Tiles.get(tilevalue & LOMASK);
        if (spec != null && spec.getAnimNext() != null) {
          int flags = tilevalue & ALLBITS;
          setTile(x, y, (char) (spec.getAnimNext().getTileNumber() | flags));
        }
      }
    }
  }

  public int getWidth() {
    return map[0].length;
  }

  public int getHeight() {
    return map.length;
  }

  public char getTile(int xpos, int ypos) {
    return (char) (map[ypos][xpos] & LOMASK);
  }

  char getTileRaw(int xpos, int ypos) {
    return map[ypos][xpos];
  }

  boolean isTileDozeable(int xpos, int ypos) {
    return isTileDozeable(new ToolEffect(this, xpos, ypos));
  }

  /** Note: this method clears the PWRBIT of the given location. */
  public void setTile(int xpos, int ypos, char newTile) {
    assert (newTile & LOMASK) == newTile;

    if (map[ypos][xpos] != newTile) {
      map[ypos][xpos] = newTile;
      fireTileChanged(xpos, ypos);
    }
  }

  public boolean testBounds(int xpos, int ypos) {
    return xpos >= 0 && xpos < getWidth() && ypos >= 0 && ypos < getHeight();
  }

  public char[][] getMap() {
    return map;
  }

  void killZone(int xpos, int ypos, int zoneTile) {
    mapAnalysis.getRateOGMem()[ypos / 8][xpos / 8] -= 20;

    assert isZoneCenter(zoneTile);
    CityDimension dim = getZoneSizeFor(zoneTile);
    assert dim != null;
    assert dim.width >= 3;
    assert dim.height >= 3;

    // this will take care of stopping smoke animations
    shutdownZone(xpos, ypos, dim);
  }

  // =========================================================================
  // Simulation Options & Clock Accessors
  // =========================================================================

  public void toggleAutoBulldoze() {
    autoBulldoze = !autoBulldoze;
    fireOptionsChanged();
  }

  public boolean isAutoBulldoze() {
    return autoBulldoze;
  }

  public void setAutoBulldoze(boolean autoBulldoze) {
    this.autoBulldoze = autoBulldoze;
    fireOptionsChanged();
  }

  public boolean isAutoGo() {
    return autoGo;
  }

  public void setAutoGo(boolean autoGo) {
    this.autoGo = autoGo;
  }

  public Speed getSimSpeed() {
    return simSpeed;
  }

  public void setSpeed(Speed newSpeed) {
    simSpeed = newSpeed;
    fireOptionsChanged();
  }

  public int getGameLevel() {
    return gameLevel;
  }

  public void setGameLevel(int newLevel) {
    assert GameLevel.isValid(newLevel);

    gameLevel = newLevel;
    fireOptionsChanged();
  }

  public int getCityTime() {
    return cityTime;
  }

  public void setCityTime(int cityTime) {
    this.cityTime = cityTime;
  }

  public int getAcycle() {
    return acycle;
  }

  public Random getRandom() {
    return random;
  }

  // =========================================================================
  // Extracted Collaborators Accessors
  // =========================================================================

  public BudgetManager getBudgetManager() {
    return budgetManager;
  }

  public CensusManager getCensusManager() {
    return censusManager;
  }

  public PowerManager getPowerManager() {
    return powerManager;
  }

  public MapAnalysis getMapAnalysis() {
    return mapAnalysis;
  }

  public SpriteManager getSpriteManager() {
    return spriteManager;
  }

  public CityNotificationManager getNotificationManager() {
    return notificationManager;
  }

  public CityEval getEvaluation() {
    return evaluation;
  }

  public DisasterManager getDisasterManager() {
    return disasterManager;
  }

  public CityPersistence getPersistence() {
    return persistence;
  }

  // =========================================================================
  // Delegated Methods: CityNotificationManager (Listeners, Messages & Sounds)
  // =========================================================================

  private void fireCensusChanged() {
    notificationManager.fireCensusChanged();
  }

  void fireDemandChanged() {
    notificationManager.fireDemandChanged();
  }

  void fireEarthquakeStarted() {
    notificationManager.fireEarthquakeStarted();
  }

  void fireEvaluationChanged() {
    notificationManager.fireEvaluationChanged();
  }

  void fireFundsChanged() {
    notificationManager.fireFundsChanged();
  }

  void fireMapOverlayDataChanged() {
    notificationManager.fireMapOverlayDataChanged();
  }

  void fireOptionsChanged() {
    notificationManager.fireOptionsChanged();
  }

  void fireSpriteMoved(Sprite sprite) {
    notificationManager.fireSpriteMoved(sprite);
  }

  private void fireTileChanged(int xpos, int ypos) {
    notificationManager.fireTileChanged(xpos, ypos);
  }

  void fireWholeMapChanged() {
    notificationManager.fireWholeMapChanged();
  }

  public void addListener(CityListener l) {
    notificationManager.addListener(l);
  }

  public void removeListener(CityListener l) {
    notificationManager.removeListener(l);
  }

  public void addEarthquakeListener(EarthquakeListener l) {
    notificationManager.addEarthquakeListener(l);
  }

  public void removeEarthquakeListener(EarthquakeListener l) {
    notificationManager.removeEarthquakeListener(l);
  }

  public void addMapListener(MapListener l) {
    notificationManager.addMapListener(l);
  }

  public void removeMapListener(MapListener l) {
    notificationManager.removeMapListener(l);
  }

  void sendMessage(MicropolisMessage message) {
    notificationManager.sendMessage(message);
  }

  void sendMessageAt(MicropolisMessage message, int x, int y) {
    notificationManager.sendMessageAt(message, x, y);
  }

  void makeSound(int x, int y, Sound sound) {
    notificationManager.makeSound(x, y, sound);
  }

  // =========================================================================
  // Delegated Methods: PowerManager
  // =========================================================================

  public boolean isTilePowered(int xpos, int ypos) {
    return powerManager.isTilePowered(xpos, ypos);
  }

  public void setTilePower(int xpos, int ypos, boolean power) {
    powerManager.setTilePower(xpos, ypos, power);
  }

  boolean hasPower(int x, int y) {
    return powerManager.hasPower(x, y);
  }

  void powerScan() {
    powerManager.powerScan();
  }

  void powerZone(int xpos, int ypos, CityDimension zoneSize) {
    powerManager.powerZone(xpos, ypos, zoneSize);
  }

  void shutdownZone(int xpos, int ypos, CityDimension zoneSize) {
    powerManager.shutdownZone(xpos, ypos, zoneSize);
  }

  public List<CityLocation> getPowerPlants() {
    return powerManager.getPowerPlants();
  }

  // =========================================================================
  // Delegated Methods: BudgetManager
  // =========================================================================

  public void spend(int amount) {
    budgetManager.spend(amount);
  }

  public void setFunds(int totalFunds) {
    budgetManager.setFunds(totalFunds);
  }

  /** Checks whether the next call to animate() will collect taxes and process the budget. */
  public boolean isBudgetTime() {
    return budgetManager.isBudgetTime(cityTime, fcycle, acycle);
  }

  /** Calculate the current budget numbers. */
  public BudgetNumbers generateBudget() {
    return budgetManager.generateBudget();
  }

  public void toggleAutoBudget() {
    budgetManager.toggleAutoBudget();
  }

  public boolean isAutoBudget() {
    return budgetManager.isAutoBudget();
  }

  public void setAutoBudget(boolean autoBudget) {
    budgetManager.setAutoBudget(autoBudget);
  }

  public CityBudget getBudget() {
    return budgetManager.getBudget();
  }

  public List<FinancialHistory> getFinancialHistory() {
    return budgetManager.getFinancialHistory();
  }

  public int getCityTax() {
    return budgetManager.getCityTax();
  }

  public void setCityTax(int cityTax) {
    budgetManager.setCityTax(cityTax);
  }

  public double getRoadPercent() {
    return budgetManager.getRoadPercent();
  }

  public void setRoadPercent(double roadPercent) {
    budgetManager.setRoadPercent(roadPercent);
  }

  public double getPolicePercent() {
    return budgetManager.getPolicePercent();
  }

  public void setPolicePercent(double policePercent) {
    budgetManager.setPolicePercent(policePercent);
  }

  public double getFirePercent() {
    return budgetManager.getFirePercent();
  }

  public void setFirePercent(double firePercent) {
    budgetManager.setFirePercent(firePercent);
  }

  public int getRoadEffect() {
    return budgetManager.getRoadEffect();
  }

  public int getPoliceEffect() {
    return budgetManager.getPoliceEffect();
  }

  public int getFireEffect() {
    return budgetManager.getFireEffect();
  }

  public int getTaxEffect() {
    return budgetManager.getTaxEffect();
  }

  public void setTaxEffect(int taxEffect) {
    budgetManager.setTaxEffect(taxEffect);
  }

  // =========================================================================
  // Delegated Methods: MapAnalysis
  // =========================================================================

  public void calculateCenterMass() {
    mapAnalysis.popDenScan();
  }

  int doFreePop(int xpos, int ypos) {
    return mapAnalysis.doFreePop(xpos, ypos);
  }

  /** Increase the traffic-density measurement at a particular spot. */
  void addTraffic(int mapX, int mapY) {
    mapAnalysis.addTraffic(mapX, mapY);
  }

  /** Accessor method for fireRate[]. */
  public int getFireStationCoverage(int xpos, int ypos) {
    return mapAnalysis.getFireStationCoverage(xpos, ypos);
  }

  /** Accessor method for landValueMem overlay. */
  public int getLandValue(int xpos, int ypos) {
    return mapAnalysis.getLandValue(xpos, ypos);
  }

  public int getTrafficDensity(int xpos, int ypos) {
    return mapAnalysis.getTrafficDensity(xpos, ypos);
  }

  int getPopulationDensity(int xpos, int ypos) {
    return mapAnalysis.getPopulationDensity(xpos, ypos);
  }

  public CityLocation getLocationOfMaxPollution() {
    return mapAnalysis.getLocationOfMaxPollution();
  }

  public ZoneStatus queryZoneStatus(int xpos, int ypos) {
    return mapAnalysis.queryZoneStatus(xpos, ypos);
  }

  public int[][] getPollutionMem() {
    return mapAnalysis.getPollutionMem();
  }

  public int[][] getCrimeMem() {
    return mapAnalysis.getCrimeMem();
  }

  public int[][] getPopDensity() {
    return mapAnalysis.getPopDensity();
  }

  public int[][] getRateOGMem() {
    return mapAnalysis.getRateOGMem();
  }

  public int[][] getFireRate() {
    return mapAnalysis.getFireRate();
  }

  public int[][] getPoliceMapEffect() {
    return mapAnalysis.getPoliceMapEffect();
  }

  public int[][] getFireStMap() {
    return mapAnalysis.getFireStMap();
  }

  public int[][] getPoliceMap() {
    return mapAnalysis.getPoliceMap();
  }

  public int[][] getComRate() {
    return mapAnalysis.getComRate();
  }

  public int getCenterMassX() {
    return mapAnalysis.getCenterMassX();
  }

  public int getCenterMassY() {
    return mapAnalysis.getCenterMassY();
  }

  public int getCrimeAverage() {
    return mapAnalysis.getCrimeAverage();
  }

  public void setCrimeAverage(int crimeAverage) {
    mapAnalysis.setCrimeAverage(crimeAverage);
  }

  public int getPollutionAverage() {
    return mapAnalysis.getPollutionAverage();
  }

  public void setPollutionAverage(int pollutionAverage) {
    mapAnalysis.setPollutionAverage(pollutionAverage);
  }

  public int getLandValueAverage() {
    return mapAnalysis.getLandValueAverage();
  }

  public void setLandValueAverage(int landValueAverage) {
    mapAnalysis.setLandValueAverage(landValueAverage);
  }

  public int getTrafficAverage() {
    return mapAnalysis.getTrafficAverage();
  }

  public void setTrafficAverage(int trafficAverage) {
    mapAnalysis.setTrafficAverage(trafficAverage);
  }

  // =========================================================================
  // Delegated Methods: CensusManager
  // =========================================================================

  public History getHistory() {
    return censusManager.getHistory();
  }

  public int getResValve() {
    return censusManager.getResValve();
  }

  public void setResValve(int resValve) {
    censusManager.setResValve(resValve);
  }

  public int getComValve() {
    return censusManager.getComValve();
  }

  public void setComValve(int comValve) {
    censusManager.setComValve(comValve);
  }

  public int getIndValve() {
    return censusManager.getIndValve();
  }

  public void setIndValve(int indValve) {
    censusManager.setIndValve(indValve);
  }

  public boolean isResCap() {
    return censusManager.isResCap();
  }

  public void setResCap(boolean resCap) {
    censusManager.setResCap(resCap);
  }

  public boolean isComCap() {
    return censusManager.isComCap();
  }

  public void setComCap(boolean comCap) {
    censusManager.setComCap(comCap);
  }

  public boolean isIndCap() {
    return censusManager.isIndCap();
  }

  public void setIndCap(boolean indCap) {
    censusManager.setIndCap(indCap);
  }

  public int getPoweredZoneCount() {
    return censusManager.getPoweredZoneCount();
  }

  public void setPoweredZoneCount(int poweredZoneCount) {
    censusManager.setPoweredZoneCount(poweredZoneCount);
  }

  public int getUnpoweredZoneCount() {
    return censusManager.getUnpoweredZoneCount();
  }

  public void setUnpoweredZoneCount(int unpoweredZoneCount) {
    censusManager.setUnpoweredZoneCount(unpoweredZoneCount);
  }

  public int getRoadTotal() {
    return censusManager.getRoadTotal();
  }

  public void setRoadTotal(int roadTotal) {
    censusManager.setRoadTotal(roadTotal);
  }

  public int getRailTotal() {
    return censusManager.getRailTotal();
  }

  public void setRailTotal(int railTotal) {
    censusManager.setRailTotal(railTotal);
  }

  public int getFirePop() {
    return censusManager.getFirePop();
  }

  public void setFirePop(int firePop) {
    censusManager.setFirePop(firePop);
  }

  public int getResZoneCount() {
    return censusManager.getResZoneCount();
  }

  public void setResZoneCount(int resZoneCount) {
    censusManager.setResZoneCount(resZoneCount);
  }

  public int getComZoneCount() {
    return censusManager.getComZoneCount();
  }

  public void setComZoneCount(int comZoneCount) {
    censusManager.setComZoneCount(comZoneCount);
  }

  public int getIndZoneCount() {
    return censusManager.getIndZoneCount();
  }

  public void setIndZoneCount(int indZoneCount) {
    censusManager.setIndZoneCount(indZoneCount);
  }

  public int getResPop() {
    return censusManager.getResPop();
  }

  public void setResPop(int resPop) {
    censusManager.setResPop(resPop);
  }

  public int getComPop() {
    return censusManager.getComPop();
  }

  public void setComPop(int comPop) {
    censusManager.setComPop(comPop);
  }

  public int getIndPop() {
    return censusManager.getIndPop();
  }

  public void setIndPop(int indPop) {
    censusManager.setIndPop(indPop);
  }

  public int getHospitalCount() {
    return censusManager.getHospitalCount();
  }

  public void setHospitalCount(int hospitalCount) {
    censusManager.setHospitalCount(hospitalCount);
  }

  public int getChurchCount() {
    return censusManager.getChurchCount();
  }

  public void setChurchCount(int churchCount) {
    censusManager.setChurchCount(churchCount);
  }

  public int getPoliceCount() {
    return censusManager.getPoliceCount();
  }

  public void setPoliceCount(int policeCount) {
    censusManager.setPoliceCount(policeCount);
  }

  public int getFireStationCount() {
    return censusManager.getFireStationCount();
  }

  public void setFireStationCount(int fireStationCount) {
    censusManager.setFireStationCount(fireStationCount);
  }

  public int getStadiumCount() {
    return censusManager.getStadiumCount();
  }

  public void setStadiumCount(int stadiumCount) {
    censusManager.setStadiumCount(stadiumCount);
  }

  public int getCoalCount() {
    return censusManager.getCoalCount();
  }

  public void setCoalCount(int coalCount) {
    censusManager.setCoalCount(coalCount);
  }

  public int getNuclearCount() {
    return censusManager.getNuclearCount();
  }

  public void setNuclearCount(int nuclearCount) {
    censusManager.setNuclearCount(nuclearCount);
  }

  public int getSeaportCount() {
    return censusManager.getSeaportCount();
  }

  public void setSeaportCount(int seaportCount) {
    censusManager.setSeaportCount(seaportCount);
  }

  public int getAirportCount() {
    return censusManager.getAirportCount();
  }

  public void setAirportCount(int airportCount) {
    censusManager.setAirportCount(airportCount);
  }

  public int getUniversityCount() {
    return censusManager.getUniversityCount();
  }

  public void setUniversityCount(int universityCount) {
    censusManager.setUniversityCount(universityCount);
  }

  public int getTotalPop() {
    return censusManager.getTotalPop();
  }

  public int getCityPopulation() {
    return censusManager.getCityPopulation();
  }

  public int getNeedHospital() {
    return censusManager.getNeedHospital();
  }

  public void setNeedHospital(int needHospital) {
    censusManager.setNeedHospital(needHospital);
  }

  public int getNeedChurch() {
    return censusManager.getNeedChurch();
  }

  public void setNeedChurch(int needChurch) {
    censusManager.setNeedChurch(needChurch);
  }

  public int getCrimeRamp() {
    return censusManager.getCrimeRamp();
  }

  public void setCrimeRamp(int crimeRamp) {
    censusManager.setCrimeRamp(crimeRamp);
  }

  public int getPolluteRamp() {
    return censusManager.getPolluteRamp();
  }

  public void setPolluteRamp(int polluteRamp) {
    censusManager.setPolluteRamp(polluteRamp);
  }

  // =========================================================================
  // Delegated Methods: SpriteManager
  // =========================================================================

  public Sprite[] allSprites() {
    return spriteManager.allSprites();
  }

  public Iterable<Sprite> getSprites() {
    return spriteManager.getSprites();
  }

  void addSprite(Sprite sprite) {
    spriteManager.addSprite(sprite);
  }

  Sprite getSprite(SpriteKind kind) {
    return spriteManager.getSprite(kind);
  }

  boolean hasSprite(SpriteKind kind) {
    return spriteManager.hasSprite(kind);
  }

  void generateShip() {
    spriteManager.generateShip();
  }

  void generateCopter(int xpos, int ypos) {
    spriteManager.generateCopter(xpos, ypos);
  }

  void generatePlane(int xpos, int ypos) {
    spriteManager.generatePlane(xpos, ypos);
  }

  void generateTrain(int xpos, int ypos) {
    spriteManager.generateTrain(xpos, ypos);
  }

  void makeExplosion(int xpos, int ypos) {
    spriteManager.makeExplosion(xpos, ypos);
  }

  void makeExplosionAt(int x, int y) {
    spriteManager.makeExplosionAt(x, y);
  }

  // =========================================================================
  // Delegated Methods: DisasterManager
  // =========================================================================

  void doMeltdown(int xpos, int ypos) {
    disasterManager.doMeltdown(xpos, ypos);
  }

  public void toggleDisasters() {
    disasterManager.toggleDisasters();
  }

  public void setNoDisasters(boolean noDisasters) {
    disasterManager.setNoDisasters(noDisasters);
  }

  public boolean isNoDisasters() {
    return disasterManager.isNoDisasters();
  }

  public void makeEarthquake() {
    disasterManager.makeEarthquake();
  }

  public void setFire() {
    disasterManager.setFire();
  }

  public void makeFire() {
    disasterManager.makeFire();
  }

  public boolean makeMeltdown() {
    return disasterManager.makeMeltdown();
  }

  public void makeMonster() {
    disasterManager.makeMonster();
  }

  public void makeTornado() {
    disasterManager.makeTornado();
  }

  public void makeFlood() {
    disasterManager.makeFlood();
  }

  public int getFloodCnt() {
    return disasterManager.getFloodCnt();
  }

  public void setFloodCnt(int floodCnt) {
    disasterManager.setFloodCnt(floodCnt);
  }

  // =========================================================================
  // Delegated Methods: CityPersistence
  // =========================================================================

  public void load(File filename) throws IOException {
    persistence.load(filename);
  }

  public void load(DataInput dis) throws IOException {
    persistence.load(dis);
  }

  public void save(File filename) throws IOException {
    persistence.save(filename);
  }

  public void save(OutputStream outStream) throws IOException {
    persistence.save(outStream);
  }

  void checkPowerMap() {
    persistence.checkPowerMap();
  }
}
