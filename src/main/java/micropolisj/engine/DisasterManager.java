// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.DIRT;
import static micropolisj.engine.TileConstants.FIRE;
import static micropolisj.engine.TileConstants.FLOOD;
import static micropolisj.engine.TileConstants.LASTZONE;
import static micropolisj.engine.TileConstants.NUCLEAR;
import static micropolisj.engine.TileConstants.RADTILE;
import static micropolisj.engine.TileConstants.RIVER;
import static micropolisj.engine.TileConstants.RUBBLE;
import static micropolisj.engine.TileConstants.isArsonable;
import static micropolisj.engine.TileConstants.isCombustible;
import static micropolisj.engine.TileConstants.isFloodable;
import static micropolisj.engine.TileConstants.isRiverEdge;
import static micropolisj.engine.TileConstants.isVulnerable;
import static micropolisj.engine.TileConstants.isZoneCenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Manages simulation and triggering of disasters in Micropolis, such as earthquakes, fires, nuclear
 * meltdowns, monsters, tornadoes, and floods.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class DisasterManager {

  private final Micropolis city;
  private final Random random;
  private boolean noDisasters;
  private int floodCnt; // number of turns the flood will last

  public DisasterManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
    this.random = city.getRandom();
  }

  /** Periodic disaster evaluation called during simulation cycle (case 15). */
  public void doDisasters() {
    if (floodCnt > 0) {
      floodCnt--;
    }

    int[] disChance = {480, 240, 60};
    if (noDisasters) {
      return;
    }

    if (random.nextInt(disChance[city.getGameLevel()] + 1) != 0) {
      return;
    }

    switch (random.nextInt(9)) {
      case 0:
      case 1:
        setFire();
        break;
      case 2:
      case 3:
        makeFlood();
        break;
      case 4:
        break;
      case 5:
        makeTornado();
        break;
      case 6:
        makeEarthquake();
        break;
      case 7:
      case 8:
        if (city.getPollutionAverage() > 60) {
          makeMonster();
        }
        break;
      default:
        break;
    }
  }

  /** Simulates an earthquake across the city. */
  public void makeEarthquake() {
    city.makeSound(city.getCenterMassX(), city.getCenterMassY(), Sound.EXPLOSION_LOW);
    city.fireEarthquakeStarted();

    city.sendMessageAt(
        MicropolisMessage.EARTHQUAKE_REPORT, city.getCenterMassX(), city.getCenterMassY());
    int time = random.nextInt(701) + 300;
    for (int z = 0; z < time; z++) {
      int x = random.nextInt(city.getWidth());
      int y = random.nextInt(city.getHeight());
      assert city.testBounds(x, y);

      if (isVulnerable(city.getTile(x, y))) {
        if (random.nextInt(4) == 0) {
          city.setTile(x, y, (char) (FIRE + random.nextInt(8)));
        } else {
          city.setTile(x, y, (char) (RUBBLE + random.nextInt(4)));
        }
      }
    }
  }

  /** Starts a single spontaneous fire on an arsonable tile. */
  public void setFire() {
    int x = random.nextInt(city.getWidth());
    int y = random.nextInt(city.getHeight());
    int t = city.getTile(x, y);

    if (isArsonable(t)) {
      city.setTile(x, y, (char) (FIRE + random.nextInt(8)));
      city.sendMessageAt(MicropolisMessage.FIRE_REPORT, x, y);
    }
  }

  /** Attempts up to 40 times to find a combustible tile to ignite. */
  public void makeFire() {
    for (int t = 0; t < 40; t++) {
      int x = random.nextInt(city.getWidth());
      int y = random.nextInt(city.getHeight());
      int tile = city.getTile(x, y);
      if (!isZoneCenter(tile) && isCombustible(tile)) {
        if (tile > 21 && tile < LASTZONE) {
          city.setTile(x, y, (char) (FIRE + random.nextInt(8)));
          city.sendMessageAt(MicropolisMessage.FIRE_REPORT, x, y);
          return;
        }
      }
    }
  }

  /**
   * Force a nuclear meltdown to occur if a nuclear plant exists.
   *
   * @return true if a meltdown was initiated.
   */
  public boolean makeMeltdown() {
    List<CityLocation> candidates = new ArrayList<>();
    for (int y = 0; y < city.getHeight(); y++) {
      for (int x = 0; x < city.getWidth(); x++) {
        if (city.getTile(x, y) == NUCLEAR) {
          candidates.add(new CityLocation(x, y));
        }
      }
    }

    if (candidates.isEmpty()) {
      return false;
    }

    int i = random.nextInt(candidates.size());
    CityLocation p = candidates.get(i);
    doMeltdown(p.getX(), p.getY());
    return true;
  }

  /** Executes the nuclear meltdown effects around the specified location. */
  public void doMeltdown(int xpos, int ypos) {
    city.makeExplosion(xpos - 1, ypos - 1);
    city.makeExplosion(xpos - 1, ypos + 2);
    city.makeExplosion(xpos + 2, ypos - 1);
    city.makeExplosion(xpos + 2, ypos + 2);

    for (int x = xpos - 1; x < xpos + 3; x++) {
      for (int y = ypos - 1; y < ypos + 3; y++) {
        city.setTile(x, y, (char) (FIRE + random.nextInt(4)));
      }
    }

    for (int z = 0; z < 200; z++) {
      int x = xpos - 20 + random.nextInt(41);
      int y = ypos - 15 + random.nextInt(31);
      if (!city.testBounds(x, y)) {
        continue;
      }

      int t = city.getTile(x, y);
      if (isZoneCenter(t)) {
        continue;
      }
      if (isCombustible(t) || t == DIRT) {
        city.setTile(x, y, RADTILE);
      }
    }

    city.sendMessageAt(MicropolisMessage.MELTDOWN_REPORT, xpos, ypos);
  }

  /** Spawns or redirects the monster sprite. */
  public void makeMonster() {
    MonsterSprite monster = (MonsterSprite) city.getSprite(SpriteKind.GOD);
    if (monster != null) {
      monster.setSoundCount(1);
      monster.setCount(1000);
      monster.setFlag(false);
      CityLocation maxPol = city.getLocationOfMaxPollution();
      monster.setDestX(maxPol.getX());
      monster.setDestY(maxPol.getY());
      return;
    }

    for (int i = 0; i < 300; i++) {
      int x = random.nextInt(city.getWidth() - 19) + 10;
      int y = random.nextInt(city.getHeight() - 9) + 5;
      int t = city.getTile(x, y);
      if (t == RIVER) {
        makeMonsterAt(x, y);
        return;
      }
    }

    makeMonsterAt(city.getWidth() / 2, city.getHeight() / 2);
  }

  private void makeMonsterAt(int xpos, int ypos) {
    assert !city.hasSprite(SpriteKind.GOD);
    city.addSprite(new MonsterSprite(city, xpos, ypos));
    city.sendMessageAt(MicropolisMessage.MONSTER_REPORT, xpos, ypos);
  }

  /** Spawns a tornado sprite or extends an active one. */
  public void makeTornado() {
    TornadoSprite tornado = (TornadoSprite) city.getSprite(SpriteKind.TOR);
    if (tornado != null) {
      tornado.setCount(200);
      return;
    }

    int xpos = random.nextInt(city.getWidth() - 19) + 10;
    int ypos = random.nextInt(city.getHeight() - 19) + 10;
    city.addSprite(new TornadoSprite(city, xpos, ypos));
    city.sendMessageAt(MicropolisMessage.TORNADO_REPORT, xpos, ypos);
  }

  /** Triggers flood events along river edges. */
  public void makeFlood() {
    int[] dx = {0, 1, 0, -1};
    int[] dy = {-1, 0, 1, 0};

    for (int z = 0; z < 300; z++) {
      int x = random.nextInt(city.getWidth());
      int y = random.nextInt(city.getHeight());
      int tile = city.getTile(x, y);
      if (isRiverEdge(tile)) {
        for (int t = 0; t < 4; t++) {
          int xx = x + dx[t];
          int yy = y + dy[t];
          if (city.testBounds(xx, yy)) {
            int c = city.getTile(xx, yy);
            if (isFloodable(c)) {
              city.setTile(xx, yy, FLOOD);
              floodCnt = 30;
              city.sendMessageAt(MicropolisMessage.FLOOD_REPORT, xx, yy);
              return;
            }
          }
        }
      }
    }
  }

  public void toggleDisasters() {
    setNoDisasters(!isNoDisasters());
  }

  public boolean isNoDisasters() {
    return noDisasters;
  }

  public void setNoDisasters(boolean noDisasters) {
    this.noDisasters = noDisasters;
    city.fireOptionsChanged();
  }

  public int getFloodCnt() {
    return floodCnt;
  }

  public void setFloodCnt(int floodCnt) {
    this.floodCnt = floodCnt;
  }
}
