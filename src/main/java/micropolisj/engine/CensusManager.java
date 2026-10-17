// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

/**
 * Manages census counters, city demographics, RCI demand valves, and historical graph series.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class CensusManager {

  public static final int CENSUSRATE = 4;

  private static final int[] TAX_TABLE = {
    200, 150, 120, 100, 80, 50, 30, 0, -10, -40, -100, -150, -200, -250, -300, -350, -400, -450,
    -500, -550, -600
  };

  private final Micropolis city;
  private final History history = new History();

  // census numbers, reset in phase 0 of each cycle, summed during map scan
  private int poweredZoneCount;
  private int unpoweredZoneCount;
  private int roadTotal;
  private int railTotal;
  private int firePop;
  private int resZoneCount;
  private int comZoneCount;
  private int indZoneCount;
  private int resPop;
  private int comPop;
  private int indPop;
  private int hospitalCount;
  private int churchCount;
  private int policeCount;
  private int fireStationCount;
  private int stadiumCount;
  private int coalCount;
  private int nuclearCount;
  private int seaportCount;
  private int airportCount;
  private int universityCount;
  private int totalPop;
  private int cityPopulation;
  private int needHospital; // -1 too many already, 0 just right, 1 not enough
  private int needChurch; // -1 too many already, 0 just right, 1 not enough

  private int resValve; // ranges between -2000 and 2000, updated by setValves
  private int comValve; // ranges between -1500 and 1500
  private int indValve; // ranges between -1500 and 1500
  private boolean resCap; // residents demand a stadium, caps resValve at 0
  private boolean comCap; // commerce demands airport, caps comValve at 0
  private boolean indCap; // industry demands sea port, caps indValve at 0

  private int crimeRamp;
  private int polluteRamp;

  public CensusManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
  }

  /** Resets per-cycle census counters before scanning the map. */
  public void clearCensus() {
    poweredZoneCount = 0;
    unpoweredZoneCount = 0;
    firePop = 0;
    roadTotal = 0;
    railTotal = 0;
    resPop = 0;
    comPop = 0;
    indPop = 0;
    resZoneCount = 0;
    comZoneCount = 0;
    indZoneCount = 0;
    hospitalCount = 0;
    churchCount = 0;
    policeCount = 0;
    fireStationCount = 0;
    stadiumCount = 0;
    coalCount = 0;
    nuclearCount = 0;
    seaportCount = 0;
    airportCount = 0;
    universityCount = 0;
    city.getPowerPlants().clear();
    city.getMapAnalysis().clearStationMaps();
  }

  /** Updates RCI demand valves based on employment, migration, birth rate, and tax effect. */
  public void setValves() {
    double normResPop = resPop / 8.0;
    totalPop = (int) (normResPop + comPop + indPop);

    double employment =
        normResPop != 0.0 ? (history.getCom()[1] + history.getInd()[1]) / normResPop : 1;

    double migration = normResPop * (employment - 1);
    final double birthRate = 0.02;
    double births = normResPop * birthRate;
    double projectedResPop = normResPop + migration + births;

    double temp = history.getCom()[1] + history.getInd()[1];
    double laborBase = temp != 0.0 ? history.getRes()[1] / temp : 1;

    // clamp laborBase to between 0.0 and 1.3
    laborBase = Math.max(0.0, Math.min(1.3, laborBase));

    double internalMarket = (normResPop + comPop + indPop) / 3.7;
    double projectedComPop = internalMarket * laborBase;

    int z = city.getGameLevel();
    temp = 1.0;
    switch (z) {
      case 0:
        temp = 1.2;
        break;
      case 1:
        temp = 1.1;
        break;
      case 2:
        temp = 0.98;
        break;
      default:
        break;
    }

    double projectedIndPop = indPop * laborBase * temp;
    if (projectedIndPop < 5.0) projectedIndPop = 5.0;

    double resRatio = normResPop != 0 ? projectedResPop / normResPop : 1.3;
    double comRatio = comPop != 0 ? projectedComPop / comPop : projectedComPop;
    double indRatio = indPop != 0 ? projectedIndPop / indPop : projectedIndPop;

    if (resRatio > 2.0) resRatio = 2.0;
    if (comRatio > 2.0) comRatio = 2.0;
    if (indRatio > 2.0) indRatio = 2.0;

    int z2 = city.getTaxEffect() + city.getGameLevel();
    if (z2 > 20) z2 = 20;

    resRatio = (resRatio - 1) * 600 + TAX_TABLE[z2];
    comRatio = (comRatio - 1) * 600 + TAX_TABLE[z2];
    indRatio = (indRatio - 1) * 600 + TAX_TABLE[z2];

    // ratios are velocity changes to valves
    resValve += (int) resRatio;
    comValve += (int) comRatio;
    indValve += (int) indRatio;

    if (resValve > 2000) resValve = 2000;
    else if (resValve < -2000) resValve = -2000;

    if (comValve > 1500) comValve = 1500;
    else if (comValve < -1500) comValve = -1500;

    if (indValve > 1500) indValve = 1500;
    else if (indValve < -1500) indValve = -1500;

    if (resCap && resValve > 0) {
      // residents demand stadium
      resValve = 0;
    }

    if (comCap && comValve > 0) {
      // commerce demands airport
      comValve = 0;
    }

    if (indCap && indValve > 0) {
      // industry demands sea port
      indValve = 0;
    }

    city.fireDemandChanged();
  }

  /** Records short-term census data into the history arrays and updates hospital/church needs. */
  public void takeCensus() {
    int resMax = 0;
    int comMax = 0;
    int indMax = 0;

    for (int i = 118; i >= 0; i--) {
      if (history.getRes()[i] > resMax) resMax = history.getRes()[i];
      if (history.getCom()[i] > comMax) comMax = history.getRes()[i];
      if (history.getInd()[i] > indMax) indMax = history.getInd()[i];

      history.getRes()[i + 1] = history.getRes()[i];
      history.getCom()[i + 1] = history.getCom()[i];
      history.getInd()[i + 1] = history.getInd()[i];
      history.getCrime()[i + 1] = history.getCrime()[i];
      history.getPollution()[i + 1] = history.getPollution()[i];
      history.getMoney()[i + 1] = history.getMoney()[i];
    }

    history.getRes()[0] = resPop / 8;
    history.getCom()[0] = comPop;
    history.getInd()[0] = indPop;

    crimeRamp += (city.getCrimeAverage() - crimeRamp) / 4;
    history.getCrime()[0] = Math.min(255, crimeRamp);

    polluteRamp += (city.getPollutionAverage() - polluteRamp) / 4;
    history.getPollution()[0] = Math.min(255, polluteRamp);

    int moneyScaled = city.getBudgetManager().getCashFlow() / 20 + 128;
    if (moneyScaled < 0) moneyScaled = 0;
    if (moneyScaled > 255) moneyScaled = 255;
    history.getMoney()[0] = moneyScaled;

    history.setCityTime(city.getCityTime());

    needHospital = Integer.compare(resPop / 256, hospitalCount);
    needChurch = Integer.compare(resPop / 256, churchCount);
  }

  /** Records long-term census data (120..239) into the history arrays. */
  public void takeCensus2() {
    int resMax = 0;
    int comMax = 0;
    int indMax = 0;

    for (int i = 238; i >= 120; i--) {
      if (history.getRes()[i] > resMax) resMax = history.getRes()[i];
      if (history.getCom()[i] > comMax) comMax = history.getRes()[i];
      if (history.getInd()[i] > indMax) indMax = history.getInd()[i];

      history.getRes()[i + 1] = history.getRes()[i];
      history.getCom()[i + 1] = history.getCom()[i];
      history.getInd()[i + 1] = history.getInd()[i];
      history.getCrime()[i + 1] = history.getCrime()[i];
      history.getPollution()[i + 1] = history.getPollution()[i];
      history.getMoney()[i + 1] = history.getMoney()[i];
    }

    history.getRes()[120] = resPop / 8;
    history.getCom()[120] = comPop;
    history.getInd()[120] = indPop;
    history.getCrime()[120] = history.getCrime()[0];
    history.getPollution()[120] = history.getPollution()[0];
    history.getMoney()[120] = history.getMoney()[0];
  }

  public History getHistory() {
    return history;
  }

  public int getPoweredZoneCount() {
    return poweredZoneCount;
  }

  public void setPoweredZoneCount(int poweredZoneCount) {
    this.poweredZoneCount = poweredZoneCount;
  }

  public int getUnpoweredZoneCount() {
    return unpoweredZoneCount;
  }

  public void setUnpoweredZoneCount(int unpoweredZoneCount) {
    this.unpoweredZoneCount = unpoweredZoneCount;
  }

  public int getRoadTotal() {
    return roadTotal;
  }

  public void setRoadTotal(int roadTotal) {
    this.roadTotal = roadTotal;
  }

  public int getRailTotal() {
    return railTotal;
  }

  public void setRailTotal(int railTotal) {
    this.railTotal = railTotal;
  }

  public int getFirePop() {
    return firePop;
  }

  public void setFirePop(int firePop) {
    this.firePop = firePop;
  }

  public int getResZoneCount() {
    return resZoneCount;
  }

  public void setResZoneCount(int resZoneCount) {
    this.resZoneCount = resZoneCount;
  }

  public int getComZoneCount() {
    return comZoneCount;
  }

  public void setComZoneCount(int comZoneCount) {
    this.comZoneCount = comZoneCount;
  }

  public int getIndZoneCount() {
    return indZoneCount;
  }

  public void setIndZoneCount(int indZoneCount) {
    this.indZoneCount = indZoneCount;
  }

  public int getResPop() {
    return resPop;
  }

  public void setResPop(int resPop) {
    this.resPop = resPop;
  }

  public int getComPop() {
    return comPop;
  }

  public void setComPop(int comPop) {
    this.comPop = comPop;
  }

  public int getIndPop() {
    return indPop;
  }

  public void setIndPop(int indPop) {
    this.indPop = indPop;
  }

  public int getHospitalCount() {
    return hospitalCount;
  }

  public void setHospitalCount(int hospitalCount) {
    this.hospitalCount = hospitalCount;
  }

  public int getChurchCount() {
    return churchCount;
  }

  public void setChurchCount(int churchCount) {
    this.churchCount = churchCount;
  }

  public int getPoliceCount() {
    return policeCount;
  }

  public void setPoliceCount(int policeCount) {
    this.policeCount = policeCount;
  }

  public int getFireStationCount() {
    return fireStationCount;
  }

  public void setFireStationCount(int fireStationCount) {
    this.fireStationCount = fireStationCount;
  }

  public int getStadiumCount() {
    return stadiumCount;
  }

  public void setStadiumCount(int stadiumCount) {
    this.stadiumCount = stadiumCount;
  }

  public int getCoalCount() {
    return coalCount;
  }

  public void setCoalCount(int coalCount) {
    this.coalCount = coalCount;
  }

  public int getNuclearCount() {
    return nuclearCount;
  }

  public void setNuclearCount(int nuclearCount) {
    this.nuclearCount = nuclearCount;
  }

  public int getSeaportCount() {
    return seaportCount;
  }

  public void setSeaportCount(int seaportCount) {
    this.seaportCount = seaportCount;
  }

  public int getAirportCount() {
    return airportCount;
  }

  public void setAirportCount(int airportCount) {
    this.airportCount = airportCount;
  }

  public int getUniversityCount() {
    return universityCount;
  }

  public void setUniversityCount(int universityCount) {
    this.universityCount = universityCount;
  }

  public int getTotalPop() {
    return totalPop;
  }

  public int getCityPopulation() {
    return cityPopulation;
  }

  public void setCityPopulation(int cityPopulation) {
    this.cityPopulation = cityPopulation;
  }

  public int getNeedHospital() {
    return needHospital;
  }

  public void setNeedHospital(int needHospital) {
    this.needHospital = needHospital;
  }

  public int getNeedChurch() {
    return needChurch;
  }

  public void setNeedChurch(int needChurch) {
    this.needChurch = needChurch;
  }

  public int getResValve() {
    return resValve;
  }

  public void setResValve(int resValve) {
    this.resValve = resValve;
  }

  public int getComValve() {
    return comValve;
  }

  public void setComValve(int comValve) {
    this.comValve = comValve;
  }

  public int getIndValve() {
    return indValve;
  }

  public void setIndValve(int indValve) {
    this.indValve = indValve;
  }

  public boolean isResCap() {
    return resCap;
  }

  public void setResCap(boolean resCap) {
    this.resCap = resCap;
  }

  public boolean isComCap() {
    return comCap;
  }

  public void setComCap(boolean comCap) {
    this.comCap = comCap;
  }

  public boolean isIndCap() {
    return indCap;
  }

  public void setIndCap(boolean indCap) {
    this.indCap = indCap;
  }

  public int getCrimeRamp() {
    return crimeRamp;
  }

  public void setCrimeRamp(int crimeRamp) {
    this.crimeRamp = crimeRamp;
  }

  public int getPolluteRamp() {
    return polluteRamp;
  }

  public void setPolluteRamp(int polluteRamp) {
    this.polluteRamp = polluteRamp;
  }
}
