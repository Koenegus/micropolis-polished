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
import static micropolisj.engine.TileConstants.NUCLEAR;
import static micropolisj.engine.TileConstants.POWERPLANT;
import static micropolisj.engine.TileConstants.PWRBIT;
import static micropolisj.engine.TileConstants.isConductive;

import java.util.Arrays;
import java.util.List;
import java.util.Stack;

/**
 * Manages the city's electrical power grid, power plant stack, power propagation scan, and zone
 * power state transitions.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class PowerManager {

  private final Micropolis city;
  private final Stack<CityLocation> powerPlants = new Stack<>();
  private boolean[][] powerMap;

  public PowerManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
  }

  /** Initializes the power map grid for the given city dimensions. */
  public void init(int width, int height) {
    powerMap = new boolean[height][width];
    powerPlants.clear();
  }

  public boolean hasPower(int x, int y) {
    return powerMap[y][x];
  }

  public boolean isTilePowered(int xpos, int ypos) {
    return (city.getTileRaw(xpos, ypos) & PWRBIT) == PWRBIT;
  }

  public void setTilePower(int xpos, int ypos, boolean power) {
    city.getMap()[ypos][xpos] =
        (char) (city.getMap()[ypos][xpos] & ~PWRBIT | (power ? PWRBIT : 0));
  }

  public List<CityLocation> getPowerPlants() {
    return powerPlants;
  }

  /** Scans the power grid starting from discovered power plants and updates {@code powerMap}. */
  public void powerScan() {
    for (boolean[] bb : powerMap) {
      Arrays.fill(bb, false);
    }

    // Note: brownouts are based on total number of power plants, not the number
    // of powerplants connected to your city.
    int maxPower = city.getCoalCount() * 700 + city.getNuclearCount() * 2000;
    int numPower = 0;

    while (!powerPlants.isEmpty()) {
      CityLocation loc = powerPlants.pop();

      int aDir = 4;
      int conNum;
      do {
        if (++numPower > maxPower) {
          // trigger notification
          city.sendMessage(MicropolisMessage.BROWNOUTS_REPORT);
          return;
        }
        movePowerLocation(loc, aDir);
        powerMap[loc.getY()][loc.getX()] = true;

        conNum = 0;
        int dir = 0;
        while (dir < 4 && conNum < 2) {
          if (testForCond(loc, dir)) {
            conNum++;
            aDir = dir;
          }
          dir++;
        }
        if (conNum > 1) {
          powerPlants.add(new CityLocation(loc.getX(), loc.getY()));
        }
      } while (conNum != 0);
    }
  }

  private boolean testForCond(CityLocation loc, int dir) {
    int xsave = loc.getX();
    int ysave = loc.getY();

    boolean rv = false;
    if (movePowerLocation(loc, dir)) {
      char t = city.getTile(loc.getX(), loc.getY());
      rv = isConductive(t) && t != NUCLEAR && t != POWERPLANT && !hasPower(loc.getX(), loc.getY());
    }

    loc.setX(xsave);
    loc.setY(ysave);
    return rv;
  }

  private boolean movePowerLocation(CityLocation loc, int dir) {
    switch (dir) {
      case 0:
        if (loc.getY() > 0) {
          loc.setY(loc.getY() - 1);
          return true;
        } else {
          return false;
        }
      case 1:
        if (loc.getX() + 1 < city.getWidth()) {
          loc.setX(loc.getX() + 1);
          return true;
        } else {
          return false;
        }
      case 2:
        if (loc.getY() + 1 < city.getHeight()) {
          loc.setY(loc.getY() + 1);
          return true;
        } else {
          return false;
        }
      case 3:
        if (loc.getX() > 0) {
          loc.setX(loc.getX() - 1);
          return true;
        } else {
          return false;
        }
      case 4:
        return true;
      default:
        return false;
    }
  }

  /**
   * If a zone has a different image (animation) for when it is powered, switch to that different
   * image here.
   */
  public void powerZone(int xpos, int ypos, CityDimension zoneSize) {
    assert zoneSize.width >= 3;
    assert zoneSize.height >= 3;

    for (int dx = 0; dx < zoneSize.width; dx++) {
      for (int dy = 0; dy < zoneSize.height; dy++) {
        int x = xpos - 1 + dx;
        int y = ypos - 1 + dy;
        int tile = city.getTileRaw(x, y);
        TileSpec ts = Tiles.get(tile & LOMASK);
        if (ts != null && ts.getOnPower() != null) {
          city.setTile(x, y, (char) (ts.getOnPower().getTileNumber() | tile & ALLBITS));
        }
      }
    }
  }

  /**
   * If a zone has a different image (animation) for when it is powered, switch back to the original
   * image.
   */
  public void shutdownZone(int xpos, int ypos, CityDimension zoneSize) {
    assert zoneSize.width >= 3;
    assert zoneSize.height >= 3;

    for (int dx = 0; dx < zoneSize.width; dx++) {
      for (int dy = 0; dy < zoneSize.height; dy++) {
        int x = xpos - 1 + dx;
        int y = ypos - 1 + dy;
        int tile = city.getTileRaw(x, y);
        TileSpec ts = Tiles.get(tile & LOMASK);
        if (ts != null && ts.getOnShutdown() != null) {
          city.setTile(x, y, (char) (ts.getOnShutdown().getTileNumber() | tile & ALLBITS));
        }
      }
    }
  }
}
