// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.LOMASK;
import static micropolisj.engine.TileConstants.NUCLEAR;
import static micropolisj.engine.TileConstants.POWERPLANT;
import static micropolisj.engine.TileConstants.isAnimated;
import static micropolisj.engine.TileConstants.isCombustible;
import static micropolisj.engine.TileConstants.isConductive;
import static micropolisj.engine.TileConstants.isZoneCenter;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Handles binary serialization and deserialization (saving and loading) for the Micropolis city
 * simulation engine.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class CityPersistence {

  public static final int DEFAULT_WIDTH = 120;
  public static final int DEFAULT_HEIGHT = 100;
  private static final int HISTORY_LENGTH = 240;

  private final Micropolis city;

  public CityPersistence(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
  }

  /** Loads a city from a file, supporting classic SimCity files with optional 128-byte headers. */
  public void load(File filename) throws IOException {
    try (FileInputStream fis = new FileInputStream(filename);
        DataInputStream dis = new DataInputStream(fis)) {
      if (fis.getChannel().size() > 27120) {
        // Some editions of the classic SimCity game start with a 128-byte header
        byte[] bbHeader = new byte[128];
        int bytesRead = fis.read(bbHeader);
        if (bytesRead < 128) {
          throw new IOException("Truncated header in city file");
        }
      }
      load(dis);
    }
  }

  /** Deserializes city data from a DataInput stream. */
  public void load(DataInput dis) throws IOException {
    History history = city.getHistory();
    loadHistoryArray(history.getRes(), dis);
    loadHistoryArray(history.getCom(), dis);
    loadHistoryArray(history.getInd(), dis);
    loadHistoryArray(history.getCrime(), dis);
    loadHistoryArray(history.getPollution(), dis);
    loadHistoryArray(history.getMoney(), dis);
    loadMisc(dis);
    loadMap(dis);

    checkPowerMap();

    city.fireWholeMapChanged();
    city.fireDemandChanged();
    city.fireFundsChanged();
  }

  /** Saves the current city simulation state to a file. */
  public void save(File filename) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(filename)) {
      save(fos);
    }
  }

  /** Serializes city data to an OutputStream. */
  public void save(OutputStream outStream) throws IOException {
    DataOutputStream out = new DataOutputStream(outStream);
    History history = city.getHistory();
    writeHistoryArray(history.getRes(), out);
    writeHistoryArray(history.getCom(), out);
    writeHistoryArray(history.getInd(), out);
    writeHistoryArray(history.getCrime(), out);
    writeHistoryArray(history.getPollution(), out);
    writeHistoryArray(history.getMoney(), out);
    writeMisc(out);
    writeMap(out);
    out.flush();
  }

  private static void loadHistoryArray(int[] array, DataInput dis) throws IOException {
    for (int i = 0; i < HISTORY_LENGTH; i++) {
      array[i] = dis.readShort();
    }
  }

  private static void writeHistoryArray(int[] array, DataOutput out) throws IOException {
    for (int i = 0; i < HISTORY_LENGTH; i++) {
      out.writeShort(array[i]);
    }
  }

  private void loadMisc(DataInput dis) throws IOException {
    dis.readShort(); // [0] ignored
    dis.readShort(); // [1] externalMarket, ignored
    city.setResPop(dis.readShort()); // [2-4] populations
    city.setComPop(dis.readShort());
    city.setIndPop(dis.readShort());
    city.setResValve(dis.readShort()); // [5-7] valves
    city.setComValve(dis.readShort());
    city.setIndValve(dis.readShort());
    city.setCityTime(dis.readInt()); // [8-9] city time
    city.setCrimeRamp(dis.readShort()); // [10]
    city.setPolluteRamp(dis.readShort());
    city.setLandValueAverage(dis.readShort()); // [12]
    city.setCrimeAverage(dis.readShort());
    city.setPollutionAverage(dis.readShort()); // [14]
    int gameLevel = dis.readShort();
    city.getEvaluation().setCityClass(dis.readShort()); // [16]
    city.getEvaluation().setCityScore(dis.readShort());

    for (int i = 18; i < 50; i++) {
      dis.readShort();
    }

    city.getBudget().setTotalFunds(dis.readInt()); // [50-51] total funds
    city.setAutoBulldoze(dis.readShort() != 0); // 52
    city.setAutoBudget(dis.readShort() != 0);
    city.setAutoGo(dis.readShort() != 0); // 54
    dis.readShort(); // userSoundOn (setting not saved to game file)
    int cityTax = dis.readShort(); // 56
    city.setCityTax(cityTax);
    city.setTaxEffect(cityTax);
    int simSpeedAsInt = dis.readShort();
    city.setSpeed(
        simSpeedAsInt >= 0 && simSpeedAsInt < Speed.values().length
            ? Speed.values()[simSpeedAsInt]
            : Speed.NORMAL);

    // Read budget numbers and convert them to percentages
    long n = dis.readInt(); // 58, 59... police percent
    city.setPolicePercent(n / 65536.0);
    n = dis.readInt(); // 60, 61... fire percent
    city.setFirePercent(n / 65536.0);
    n = dis.readInt(); // 62, 63... road percent
    city.setRoadPercent(n / 65536.0);

    for (int i = 64; i < 120; i++) {
      dis.readShort();
    }

    if (city.getCityTime() < 0) {
      city.setCityTime(0);
    }
    if (city.getCityTax() < 0 || city.getCityTax() > 20) {
      city.setCityTax(7);
    }
    if (gameLevel < 0 || gameLevel > 2) {
      gameLevel = 0;
    }
    city.setGameLevel(gameLevel);

    if (city.getEvaluation().getCityClass() < 0 || city.getEvaluation().getCityClass() > 5) {
      city.getEvaluation().setCityClass(0);
    }
    if (city.getEvaluation().getCityScore() < 1 || city.getEvaluation().getCityScore() > 999) {
      city.getEvaluation().setCityScore(500);
    }

    city.setResCap(false);
    city.setComCap(false);
    city.setIndCap(false);
  }

  private void writeMisc(DataOutput out) throws IOException {
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(city.getResPop());
    out.writeShort(city.getComPop());
    out.writeShort(city.getIndPop());
    out.writeShort(city.getResValve());
    out.writeShort(city.getComValve());
    out.writeShort(city.getIndValve());
    // 8
    out.writeInt(city.getCityTime());
    out.writeShort(city.getCrimeRamp());
    out.writeShort(city.getPolluteRamp());
    // 12
    out.writeShort(city.getLandValueAverage());
    out.writeShort(city.getCrimeAverage());
    out.writeShort(city.getPollutionAverage());
    out.writeShort(city.getGameLevel());
    // 16
    out.writeShort(city.getEvaluation().getCityClass());
    out.writeShort(city.getEvaluation().getCityScore());
    // 18
    for (int i = 18; i < 50; i++) {
      out.writeShort(0);
    }
    // 50
    out.writeInt(city.getBudget().getTotalFunds());
    out.writeShort(city.isAutoBulldoze() ? 1 : 0);
    out.writeShort(city.isAutoBudget() ? 1 : 0);
    // 54
    out.writeShort(city.isAutoGo() ? 1 : 0);
    out.writeShort(1); // userSoundOn
    out.writeShort(city.getCityTax());
    out.writeShort(city.getSimSpeed().ordinal());

    // 58
    out.writeInt((int) (city.getPolicePercent() * 65536));
    out.writeInt((int) (city.getFirePercent() * 65536));
    out.writeInt((int) (city.getRoadPercent() * 65536));

    // 64
    for (int i = 64; i < 120; i++) {
      out.writeShort(0);
    }
  }

  private void loadMap(DataInput dis) throws IOException {
    char[][] map = city.getMap();
    for (int x = 0; x < DEFAULT_WIDTH; x++) {
      for (int y = 0; y < DEFAULT_HEIGHT; y++) {
        int z = dis.readShort();
        z &=
            ~(1024 | 2048 | 4096 | 8192
                | 16384); // clear ZONEBIT, ANIMBIT, BULLBIT, BURNBIT, CONDBIT
        map[y][x] = (char) z;
      }
    }
  }

  private void writeMap(DataOutput out) throws IOException {
    char[][] map = city.getMap();
    for (int x = 0; x < DEFAULT_WIDTH; x++) {
      for (int y = 0; y < DEFAULT_HEIGHT; y++) {
        int z = map[y][x];
        if (isConductive(z & LOMASK)) {
          z |= 16384; // CONDBIT
        }
        if (isCombustible(z & LOMASK)) {
          z |= 8192; // BURNBIT
        }
        if (city.isTileDozeable(x, y)) {
          z |= 4096; // BULLBIT
        }
        if (isAnimated(z & LOMASK)) {
          z |= 2048; // ANIMBIT
        }
        if (isZoneCenter(z & LOMASK)) {
          z |= 1024; // ZONEBIT
        }
        out.writeShort(z);
      }
    }
  }

  /**
   * Scans map to rebuild power plants stack and recount power plant counts, then runs powerScan.
   */
  public void checkPowerMap() {
    city.setCoalCount(0);
    city.setNuclearCount(0);
    city.getPowerPlants().clear();

    for (int y = 0; y < city.getHeight(); y++) {
      for (int x = 0; x < city.getWidth(); x++) {
        int tile = city.getTile(x, y);
        if (tile == NUCLEAR) {
          city.setNuclearCount(city.getNuclearCount() + 1);
          city.getPowerPlants().add(new CityLocation(x, y));
        } else if (tile == POWERPLANT) {
          city.setCoalCount(city.getCoalCount() + 1);
          city.getPowerPlants().add(new CityLocation(x, y));
        }
      }
    }

    city.powerScan();
  }
}
