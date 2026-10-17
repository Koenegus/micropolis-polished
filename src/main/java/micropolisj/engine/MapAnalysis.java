// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.COMBASE;
import static micropolisj.engine.TileConstants.DIRT;
import static micropolisj.engine.TileConstants.HHTHR;
import static micropolisj.engine.TileConstants.INDBASE;
import static micropolisj.engine.TileConstants.LHTHR;
import static micropolisj.engine.TileConstants.PORTBASE;
import static micropolisj.engine.TileConstants.RESCLR;
import static micropolisj.engine.TileConstants.RUBBLE;
import static micropolisj.engine.TileConstants.commercialZonePop;
import static micropolisj.engine.TileConstants.getDescriptionNumber;
import static micropolisj.engine.TileConstants.getPollutionValue;
import static micropolisj.engine.TileConstants.industrialZonePop;
import static micropolisj.engine.TileConstants.isConstructed;
import static micropolisj.engine.TileConstants.isZoneCenter;
import static micropolisj.engine.TileConstants.residentialZonePop;

import java.util.Random;

/**
 * Manages spatial overlay grids (crime, pollution, land value, population density, traffic density,
 * rate of growth, fire/police coverage, commercial rate) and their periodic spatial scans.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class MapAnalysis {

  private final Micropolis city;
  private final Random random;

  // half-size arrays
  private int[][] pollutionMem;
  private int[][] crimeMem;
  private int[][] popDensity;
  private int[][] trfDensity;
  private int[][] landValueMem;

  // quarter-size arrays
  private int[][] terrainMem;

  // eighth-size arrays
  private int[][] rateOGMem;
  private int[][] fireStMap;
  private int[][] policeMap;
  private int[][] policeMapEffect;
  private int[][] fireRate;
  private int[][] comRate;

  private int centerMassX;
  private int centerMassY;
  private int crimeAverage;
  private int pollutionAverage;
  private int landValueAverage;
  private int trafficAverage;
  private int pollutionMaxLocationX;
  private int pollutionMaxLocationY;

  public MapAnalysis(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
    this.random = city.getRandom();
  }

  /** Allocates overlay matrices scaled to the given city dimensions. */
  public void init(int width, int height) {
    int hX = (width + 1) / 2;
    int hY = (height + 1) / 2;

    landValueMem = new int[hY][hX];
    pollutionMem = new int[hY][hX];
    crimeMem = new int[hY][hX];
    popDensity = new int[hY][hX];
    trfDensity = new int[hY][hX];

    int qX = (width + 3) / 4;
    int qY = (height + 3) / 4;

    terrainMem = new int[qY][qX];

    int smX = (width + 7) / 8;
    int smY = (height + 7) / 8;

    rateOGMem = new int[smY][smX];
    fireStMap = new int[smY][smX];
    policeMap = new int[smY][smX];
    policeMapEffect = new int[smY][smX];
    fireRate = new int[smY][smX];
    comRate = new int[smY][smX];

    centerMassX = hX;
    centerMassY = hY;
  }

  /** Clears temporary fire and police station coverage maps at the beginning of a scan cycle. */
  public void clearStationMaps() {
    for (int y = 0; y < fireStMap.length; y++) {
      for (int x = 0; x < fireStMap[y].length; x++) {
        fireStMap[y][x] = 0;
        policeMap[y][x] = 0;
      }
    }
  }

  private static int[][] doSmooth(int[][] tem) {
    int h = tem.length;
    int w = tem[0].length;
    int[][] tem2 = new int[h][w];

    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int z = tem[y][x];
        if (x > 0) z += tem[y][x - 1];
        if (x + 1 < w) z += tem[y][x + 1];
        if (y > 0) z += tem[y - 1][x];
        if (y + 1 < h) z += tem[y + 1][x];
        z /= 4;
        if (z > 255) z = 255;
        tem2[y][x] = z;
      }
    }

    return tem2;
  }

  private static int[][] smoothFirePoliceMap(int[][] omap) {
    int smX = omap[0].length;
    int smY = omap.length;
    int[][] nmap = new int[smY][smX];
    for (int sy = 0; sy < smY; sy++) {
      for (int sx = 0; sx < smX; sx++) {
        int edge = 0;
        if (sx > 0) {
          edge += omap[sy][sx - 1];
        }
        if (sx + 1 < smX) {
          edge += omap[sy][sx + 1];
        }
        if (sy > 0) {
          edge += omap[sy - 1][sx];
        }
        if (sy + 1 < smY) {
          edge += omap[sy + 1][sx];
        }
        edge = edge / 4 + omap[sy][sx];
        nmap[sy][sx] = edge / 2;
      }
    }
    return nmap;
  }

  private static int[][] smoothTerrain(int[][] qtem) {
    int qwx = qtem[0].length;
    int qwy = qtem.length;

    int[][] mem = new int[qwy][qwx];
    for (int y = 0; y < qwy; y++) {
      for (int x = 0; x < qwx; x++) {
        int z = 0;
        if (x > 0) z += qtem[y][x - 1];
        if (x + 1 < qwx) z += qtem[y][x + 1];
        if (y > 0) z += qtem[y - 1][x];
        if (y + 1 < qwy) z += qtem[y + 1][x];
        mem[y][x] = z / 4 + qtem[y][x] / 2;
      }
    }
    return mem;
  }

  private int computePopDen(int x, int y, char tile) {
    if (tile == RESCLR) return doFreePop(x, y);

    if (tile < COMBASE) return residentialZonePop(tile);

    if (tile < INDBASE) return commercialZonePop(tile) * 8;

    if (tile < PORTBASE) return industrialZonePop(tile) * 8;

    return 0;
  }

  /** Counts the population in a single-lot residential zone. */
  public int doFreePop(int xpos, int ypos) {
    int count = 0;

    for (int x = xpos - 1; x <= xpos + 1; x++) {
      for (int y = ypos - 1; y <= ypos + 1; y++) {
        if (city.testBounds(x, y)) {
          char loc = city.getTile(x, y);
          if (loc >= LHTHR && loc <= HHTHR) count++;
        }
      }
    }

    return count;
  }

  /** Scans population density, updates commercial rate and center of mass. */
  public void popDenScan() {
    int xtot = 0;
    int ytot = 0;
    int zoneCount = 0;
    int width = city.getWidth();
    int height = city.getHeight();
    int[][] tem = new int[(height + 1) / 2][(width + 1) / 2];

    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        char tile = city.getTile(x, y);
        if (isZoneCenter(tile)) {
          int den = computePopDen(x, y, tile) * 8;
          if (den > 254) den = 254;
          tem[y / 2][x / 2] = den;
          xtot += x;
          ytot += y;
          zoneCount++;
        }
      }
    }

    tem = doSmooth(tem);
    tem = doSmooth(tem);
    tem = doSmooth(tem);

    for (int x = 0; x < (width + 1) / 2; x++) {
      for (int y = 0; y < (height + 1) / 2; y++) {
        popDensity[y][x] = 2 * tem[y][x];
      }
    }

    distIntMarket(); // set ComRate

    // find center of mass for city
    if (zoneCount == 0) {
      centerMassX = (width + 1) / 2;
      centerMassY = (height + 1) / 2;
    } else {
      centerMassX = xtot / zoneCount;
      centerMassY = ytot / zoneCount;
    }

    city.fireMapOverlayDataChanged(); // PDMAP
    city.fireMapOverlayDataChanged(); // RGMAP
  }

  private void distIntMarket() {
    for (int y = 0; y < comRate.length; y++) {
      for (int x = 0; x < comRate[y].length; x++) {
        int z = getDisCC(x * 4, y * 4);
        z /= 4;
        z = 64 - z;
        comRate[y][x] = z;
      }
    }
  }

  // calculate manhattan distance (in 2-units) from center of city, capped at 32
  private int getDisCC(int x, int y) {
    assert x >= 0 && x <= city.getWidth() / 2;
    assert y >= 0 && y <= city.getHeight() / 2;

    int xdis = Math.abs(x - centerMassX / 2);
    int ydis = Math.abs(y - centerMassY / 2);

    int z = xdis + ydis;
    return Math.min(z, 32);
  }

  /** Tends to empty RateOGMem[][]. */
  public void decROGMem() {
    for (int y = 0; y < rateOGMem.length; y++) {
      for (int x = 0; x < rateOGMem[y].length; x++) {
        int z = rateOGMem[y][x];
        if (z == 0) continue;

        if (z > 0) {
          rateOGMem[y][x]--;
          if (z > 200) {
            rateOGMem[y][x] = 200;
          }
          continue;
        }

        rateOGMem[y][x]++;
        if (z < -200) {
          rateOGMem[y][x] = -200;
        }
      }
    }
  }

  /** Tends to empty trfDensity[][]. */
  public void decTrafficMem() {
    for (int y = 0; y < trfDensity.length; y++) {
      for (int x = 0; x < trfDensity[y].length; x++) {
        int z = trfDensity[y][x];
        if (z != 0) {
          if (z > 200) trfDensity[y][x] = z - 34;
          else if (z > 24) trfDensity[y][x] = z - 24;
          else trfDensity[y][x] = 0;
        }
      }
    }
  }

  /** Scans crime levels across the city based on land value, population density, and police. */
  public void crimeScan() {
    policeMap = smoothFirePoliceMap(policeMap);
    policeMap = smoothFirePoliceMap(policeMap);
    policeMap = smoothFirePoliceMap(policeMap);

    for (int sy = 0; sy < policeMap.length; sy++) {
      System.arraycopy(policeMap[sy], 0, policeMapEffect[sy], 0, policeMap[sy].length);
    }

    int count = 0;
    int sum = 0;
    int cmax = 0;
    for (int hy = 0; hy < landValueMem.length; hy++) {
      for (int hx = 0; hx < landValueMem[hy].length; hx++) {
        int val = landValueMem[hy][hx];
        if (val == 0) {
          crimeMem[hy][hx] = 0;
        } else {
          count++;
          int z = 128 - val + popDensity[hy][hx];
          z = Math.min(300, z);
          z -= policeMap[hy / 4][hx / 4];
          z = Math.min(250, z);
          z = Math.max(0, z);
          crimeMem[hy][hx] = z;

          sum += z;
          if (z > cmax || z == cmax && random.nextInt(4) == 0) {
            cmax = z;
          }
        }
      }
    }

    crimeAverage = count != 0 ? sum / count : 0;

    city.fireMapOverlayDataChanged();
  }

  /** Smooths fire station coverage map and updates fireRate overlay. */
  public void fireAnalysis() {
    fireStMap = smoothFirePoliceMap(fireStMap);
    fireStMap = smoothFirePoliceMap(fireStMap);
    fireStMap = smoothFirePoliceMap(fireStMap);
    for (int sy = 0; sy < fireStMap.length; sy++) {
      System.arraycopy(fireStMap[sy], 0, fireRate[sy], 0, fireStMap[sy].length);
    }

    city.fireMapOverlayDataChanged();
  }

  /** Scans pollution, terrain, and land value across the city. */
  public void ptlScan() {
    int qX = (city.getWidth() + 3) / 4;
    int qY = (city.getHeight() + 3) / 4;
    int[][] qtem = new int[qY][qX];

    int landValueTotal = 0;
    int landValueCount = 0;

    int hwldx = (city.getWidth() + 1) / 2;
    int hwldy = (city.getHeight() + 1) / 2;
    int[][] tem = new int[hwldy][hwldx];
    for (int x = 0; x < hwldx; x++) {
      for (int y = 0; y < hwldy; y++) {
        int plevel = 0;
        int lvflag = 0;
        int zx = 2 * x;
        int zy = 2 * y;

        for (int mx = zx; mx <= zx + 1; mx++) {
          for (int my = zy; my <= zy + 1; my++) {
            int tile = city.getTile(mx, my);
            if (tile != DIRT) {
              if (tile < RUBBLE) { // natural land features
                qtem[y / 2][x / 2] += 15;
                continue;
              }
              plevel += getPollutionValue(tile);
              if (isConstructed(tile)) lvflag++;
            }
          }
        }

        if (plevel < 0) plevel = 250;

        if (plevel > 255) plevel = 255;

        tem[y][x] = plevel;

        if (lvflag == 0) {
          landValueMem[y][x] = 0;
        } else {
          int dis = 34 - getDisCC(x, y);
          dis *= 4;
          dis += terrainMem[y / 2][x / 2];
          dis -= pollutionMem[y][x];
          if (crimeMem[y][x] > 190) {
            dis -= 20;
          }
          if (dis > 250) dis = 250;
          if (dis < 1) dis = 1;
          landValueMem[y][x] = dis;
          landValueTotal += dis;
          landValueCount++;
        }
      }
    }

    landValueAverage = landValueCount != 0 ? landValueTotal / landValueCount : 0;

    tem = doSmooth(tem);
    tem = doSmooth(tem);

    int pcount = 0;
    int ptotal = 0;
    int pmax = 0;
    for (int x = 0; x < hwldx; x++) {
      for (int y = 0; y < hwldy; y++) {
        int z = tem[y][x];
        pollutionMem[y][x] = z;

        if (z != 0) {
          pcount++;
          ptotal += z;

          if (z > pmax || z == pmax && random.nextInt(4) == 0) {
            pmax = z;
            pollutionMaxLocationX = 2 * x;
            pollutionMaxLocationY = 2 * y;
          }
        }
      }
    }

    pollutionAverage = pcount != 0 ? ptotal / pcount : 0;

    terrainMem = smoothTerrain(qtem);

    city.fireMapOverlayDataChanged(); // PLMAP
    city.fireMapOverlayDataChanged(); // LVMAP
  }

  /** Increase the traffic-density measurement at a particular spot. */
  public void addTraffic(int mapX, int mapY) {
    int z = trfDensity[mapY / 2][mapX / 2];
    z += 50;

    if (z > 240 && random.nextInt(6) == 0) {
      z = 240;

      HelicopterSprite copter = (HelicopterSprite) city.getSprite(SpriteKind.COP);
      if (copter != null) {
        copter.setDestX(mapX);
        copter.setDestY(mapY);
      }
    }

    trfDensity[mapY / 2][mapX / 2] = z;
  }

  /** Queries zone status metrics at a given map coordinate. */
  public ZoneStatus queryZoneStatus(int xpos, int ypos) {
    ZoneStatus zs = new ZoneStatus();
    zs.setBuilding(getDescriptionNumber(city.getTile(xpos, ypos)));

    int z;
    z = popDensity[ypos / 2][xpos / 2] / 64 % 4;
    zs.setPopDensity(z + 1);

    z = landValueMem[ypos / 2][xpos / 2];
    z = z < 30 ? 4 : z < 80 ? 5 : z < 150 ? 6 : 7;
    zs.setLandValue(z + 1);

    z = crimeMem[ypos / 2][xpos / 2] / 64 % 4 + 8;
    zs.setCrimeLevel(z + 1);

    z = Math.max(13, pollutionMem[ypos / 2][xpos / 2] / 64 % 4 + 12);
    zs.setPollution(z + 1);

    z = rateOGMem[ypos / 8][xpos / 8];
    z = z < 0 ? 16 : z == 0 ? 17 : z <= 100 ? 18 : 19;
    zs.setGrowthRate(z + 1);

    return zs;
  }

  public CityLocation getLocationOfMaxPollution() {
    return new CityLocation(pollutionMaxLocationX, pollutionMaxLocationY);
  }

  public int getFireStationCoverage(int xpos, int ypos) {
    return fireRate[ypos / 8][xpos / 8];
  }

  public int getLandValue(int xpos, int ypos) {
    return city.testBounds(xpos, ypos) ? landValueMem[ypos / 2][xpos / 2] : 0;
  }

  public int getTrafficDensity(int xpos, int ypos) {
    return city.testBounds(xpos, ypos) ? trfDensity[ypos / 2][xpos / 2] : 0;
  }

  public int getPopulationDensity(int xpos, int ypos) {
    return popDensity[ypos / 2][xpos / 2];
  }

  public int[][] getPollutionMem() {
    return pollutionMem;
  }

  public int[][] getCrimeMem() {
    return crimeMem;
  }

  public int[][] getPopDensity() {
    return popDensity;
  }

  public int[][] getRateOGMem() {
    return rateOGMem;
  }

  public int[][] getFireRate() {
    return fireRate;
  }

  public int[][] getPoliceMapEffect() {
    return policeMapEffect;
  }

  public int[][] getFireStMap() {
    return fireStMap;
  }

  public int[][] getPoliceMap() {
    return policeMap;
  }

  public int[][] getComRate() {
    return comRate;
  }

  public int getCenterMassX() {
    return centerMassX;
  }

  public int getCenterMassY() {
    return centerMassY;
  }

  public int getCrimeAverage() {
    return crimeAverage;
  }

  public void setCrimeAverage(int crimeAverage) {
    this.crimeAverage = crimeAverage;
  }

  public int getPollutionAverage() {
    return pollutionAverage;
  }

  public void setPollutionAverage(int pollutionAverage) {
    this.pollutionAverage = pollutionAverage;
  }

  public int getLandValueAverage() {
    return landValueAverage;
  }

  public void setLandValueAverage(int landValueAverage) {
    this.landValueAverage = landValueAverage;
  }

  public int getTrafficAverage() {
    return trafficAverage;
  }

  public void setTrafficAverage(int trafficAverage) {
    this.trafficAverage = trafficAverage;
  }
}
