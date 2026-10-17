// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.CHANNEL;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Manages active mobile sprites (ships, helicopters, airplanes, trains, monsters, tornadoes,
 * explosions) and their lifecycle/movement in the city.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class SpriteManager {

  private static final int NORTH_EDGE = 5;
  private static final int EAST_EDGE = 7;
  private static final int SOUTH_EDGE = 1;
  private static final Sprite[] SPRITES = new Sprite[0];

  private final Micropolis city;
  private final Random random;
  private final List<Sprite> sprites = new ArrayList<>();

  public SpriteManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
    this.random = city.getRandom();
  }

  public Sprite[] allSprites() {
    return sprites.toArray(SPRITES);
  }

  public Iterable<Sprite> getSprites() {
    return sprites;
  }

  public void addSprite(Sprite sprite) {
    sprites.add(sprite);
  }

  public Sprite getSprite(SpriteKind kind) {
    for (Sprite s : sprites) {
      if (s.getKind() == kind) return s;
    }
    return null;
  }

  public boolean hasSprite(SpriteKind kind) {
    return getSprite(kind) != null;
  }

  /** Moves all active sprites and removes any whose animation has finished (frame == 0). */
  public void moveObjects() {
    for (Sprite sprite : allSprites()) {
      sprite.move();

      if (sprite.getFrame() == 0) {
        sprites.remove(sprite);
      }
    }
  }

  /** Attempts to spawn a ship sprite along a map edge containing a river channel. */
  public void generateShip() {
    int edge = random.nextInt(4);

    if (edge == 0) {
      for (int x = 4; x < city.getWidth() - 2; x++) {
        if (city.getTile(x, 0) == CHANNEL) {
          makeShipAt(x, 0, NORTH_EDGE);
          return;
        }
      }
    } else if (edge == 1) {
      for (int y = 1; y < city.getHeight() - 2; y++) {
        if (city.getTile(0, y) == CHANNEL) {
          makeShipAt(0, y, EAST_EDGE);
          return;
        }
      }
    } else if (edge == 2) {
      for (int x = 4; x < city.getWidth() - 2; x++) {
        if (city.getTile(x, city.getHeight() - 1) == CHANNEL) {
          makeShipAt(x, city.getHeight() - 1, SOUTH_EDGE);
          return;
        }
      }
    } else {
      for (int y = 1; y < city.getHeight() - 2; y++) {
        if (city.getTile(city.getWidth() - 1, y) == CHANNEL) {
          makeShipAt(city.getWidth() - 1, y, EAST_EDGE);
          return;
        }
      }
    }
  }

  private void makeShipAt(int xpos, int ypos, int edge) {
    assert !hasSprite(SpriteKind.SHI);

    sprites.add(new ShipSprite(city, xpos, ypos, edge));
  }

  public void generateCopter(int xpos, int ypos) {
    if (!hasSprite(SpriteKind.COP)) {
      sprites.add(new HelicopterSprite(city, xpos, ypos));
    }
  }

  public void generatePlane(int xpos, int ypos) {
    if (!hasSprite(SpriteKind.AIR)) {
      sprites.add(new AirplaneSprite(city, xpos, ypos));
    }
  }

  public void generateTrain(int xpos, int ypos) {
    if (city.getTotalPop() > 20 && !hasSprite(SpriteKind.TRA) && random.nextInt(26) == 0) {
      sprites.add(new TrainSprite(city, xpos, ypos));
    }
  }

  public void makeExplosion(int xpos, int ypos) {
    makeExplosionAt(xpos * 16 + 8, ypos * 16 + 8);
  }

  /** Uses x,y coordinates as 1/16th-length tiles. */
  public void makeExplosionAt(int x, int y) {
    sprites.add(new ExplosionSprite(city, x, y));
  }
}
