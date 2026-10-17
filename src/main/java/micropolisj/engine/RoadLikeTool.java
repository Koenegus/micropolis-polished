// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import static micropolisj.engine.TileConstants.CHANNEL;
import static micropolisj.engine.TileConstants.DIRT;
import static micropolisj.engine.TileConstants.HBRIDGE;
import static micropolisj.engine.TileConstants.HPOWER;
import static micropolisj.engine.TileConstants.HRAIL;
import static micropolisj.engine.TileConstants.HRAILROAD;
import static micropolisj.engine.TileConstants.HROADPOWER;
import static micropolisj.engine.TileConstants.INTERSECTION;
import static micropolisj.engine.TileConstants.LHPOWER;
import static micropolisj.engine.TileConstants.LHRAIL;
import static micropolisj.engine.TileConstants.LVPOWER;
import static micropolisj.engine.TileConstants.LVRAIL;
import static micropolisj.engine.TileConstants.RAILHPOWERV;
import static micropolisj.engine.TileConstants.RAILVPOWERH;
import static micropolisj.engine.TileConstants.REDGE;
import static micropolisj.engine.TileConstants.RIVER;
import static micropolisj.engine.TileConstants.ROADS2;
import static micropolisj.engine.TileConstants.VBRIDGE;
import static micropolisj.engine.TileConstants.VPOWER;
import static micropolisj.engine.TileConstants.VRAIL;
import static micropolisj.engine.TileConstants.VRAILROAD;
import static micropolisj.engine.TileConstants.VROADPOWER;
import static micropolisj.engine.TileConstants.canAutoBulldozeRRW;
import static micropolisj.engine.TileConstants.neutralizeRoad;

import java.util.function.IntPredicate;

class RoadLikeTool extends ToolStroke {
  /** Extra cost of auto-bulldozing the rubble or similar that is in the way. */
  private static final int AUTO_BULLDOZE_COST = 1;

  /** Returned by {@link #clearingCost} when the tile in the way cannot be cleared. */
  private static final int CANNOT_CLEAR = -1;

  RoadLikeTool(Micropolis city, MicropolisTool tool, int xpos, int ypos) {
    super(city, tool, xpos, ypos);
  }

  @Override
  protected void applyArea(ToolEffectIfc eff) {
    while (true) {
      if (!applyForward(eff)) {
        break;
      }
      if (!applyBackward(eff)) {
        break;
      }
    }
  }

  private boolean applyBackward(ToolEffectIfc eff) {
    boolean anyChange = false;

    CityRect b = getBounds();
    for (int i = b.getHeight() - 1; i >= 0; i--) {
      for (int j = b.getWidth() - 1; j >= 0; j--) {
        ToolEffectIfc tte = new TranslatedToolEffect(eff, b.getX() + j, b.getY() + i);
        anyChange = anyChange || applySingle(tte);
      }
    }
    return anyChange;
  }

  private boolean applyForward(ToolEffectIfc eff) {
    boolean anyChange = false;

    CityRect b = getBounds();
    for (int i = 0; i < b.getHeight(); i++) {
      for (int j = 0; j < b.getWidth(); j++) {
        ToolEffectIfc tte = new TranslatedToolEffect(eff, b.getX() + j, b.getY() + i);
        anyChange = anyChange || applySingle(tte);
      }
    }
    return anyChange;
  }

  @Override
  public CityRect getBounds() {
    // constrain bounds to be a rectangle with
    // either width or height equal to one.

    assert getTool().getSize() == 1;
    if (Math.abs(getXdest() - getXpos()) >= Math.abs(getYdest() - getYpos())) {
      // horizontal line
      CityRect r = new CityRect();
      r.setX(Math.min(getXpos(), getXdest()));
      r.setWidth(Math.abs(getXdest() - getXpos()) + 1);
      r.setY(getYpos());
      r.setHeight(1);
      return r;
    } else {
      // vertical line
      CityRect r = new CityRect();
      r.setX(getXpos());
      r.setWidth(1);
      r.setY(Math.min(getYpos(), getYdest()));
      r.setHeight(Math.abs(getYdest() - getYpos()) + 1);
      return r;
    }
  }

  private boolean applySingle(ToolEffectIfc eff) {
    switch (getTool()) {
      case RAIL:
        return applyRailTool(eff);

      case ROADS:
        return applyRoadTool(eff);

      case WIRE:
        return applyWireTool(eff);

      default:
        throw new RuntimeException("Unexpected tool: " + getTool());
    }
  }

  private boolean applyRailTool(ToolEffectIfc eff) {
    if (layRail(eff)) {
      fixZone(eff);
      return true;
    } else {
      return false;
    }
  }

  private boolean applyRoadTool(ToolEffectIfc eff) {
    if (layRoad(eff)) {
      fixZone(eff);
      return true;
    } else {
      return false;
    }
  }

  private boolean applyWireTool(ToolEffectIfc eff) {
    if (layWire(eff)) {
      fixZone(eff);
      return true;
    } else {
      return false;
    }
  }

  private boolean layRail(ToolEffectIfc eff) {
    final int railCost = 20;
    final int tunnelCost = 100;

    int cost = railCost;

    char tile = (char) eff.getTile(0, 0);
    tile = neutralizeRoad(tile);

    switch (tile) {
      case RIVER: // rail on water
      case REDGE:
      case CHANNEL:
        cost = tunnelCost;
        if (!layAcrossWater(eff, TUNNEL)) {
          // cannot do rail here
          return false;
        }
        break;

      case LHPOWER: // rail on power
        eff.setTile(0, 0, RAILVPOWERH);
        break;

      case LVPOWER: // rail on power
        eff.setTile(0, 0, RAILHPOWERV);
        break;

      case TileConstants.ROADS: // rail on road (case 1)
        eff.setTile(0, 0, VRAILROAD);
        break;

      case ROADS2: // rail on road (case 2)
        eff.setTile(0, 0, HRAILROAD);
        break;

      default:
        int clearing = clearingCost(tile);
        if (clearing == CANNOT_CLEAR) {
          // cannot do rail here
          return false;
        }
        cost += clearing;

        // rail on dirt
        eff.setTile(0, 0, LHRAIL);
        break;
    }

    eff.spend(cost);
    return true;
  }

  private boolean layRoad(ToolEffectIfc eff) {
    final int roadCost = 10;
    final int bridgeCost = 50;

    int cost = roadCost;

    // unlike layRail and layWire, the tile is not neutralized here
    char tile = (char) eff.getTile(0, 0);
    switch (tile) {
      case RIVER: // road on water
      case REDGE:
      case CHANNEL: // check how to build bridges, if possible.
        cost = bridgeCost;
        if (!layAcrossWater(eff, BRIDGE)) {
          // cannot do road here
          return false;
        }
        break;

      case LHPOWER: // road on power
        eff.setTile(0, 0, VROADPOWER);
        break;

      case LVPOWER: // road on power #2
        eff.setTile(0, 0, HROADPOWER);
        break;

      case LHRAIL: // road on rail
        eff.setTile(0, 0, HRAILROAD);
        break;

      case LVRAIL: // road on rail #2
        eff.setTile(0, 0, VRAILROAD);
        break;

      default:
        int clearing = clearingCost(tile);
        if (clearing == CANNOT_CLEAR) {
          // cannot do road here
          return false;
        }
        cost += clearing;

        // road on dirt;
        // just build a plain road, fixZone will fix it.
        eff.setTile(0, 0, TileConstants.ROADS);
        break;
    }

    eff.spend(cost);
    return true;
  }

  private boolean layWire(ToolEffectIfc eff) {
    final int wireCost = 5;
    final int underwaterWireCost = 25;

    int cost = wireCost;

    char tile = (char) eff.getTile(0, 0);
    tile = neutralizeRoad(tile);

    switch (tile) {
      case RIVER: // wire on water
      case REDGE:
      case CHANNEL:
        cost = underwaterWireCost;
        if (!layAcrossWater(eff, UNDERWATER_WIRE)) {
          // cannot do wire here
          return false;
        }
        break;

      case TileConstants.ROADS: // wire on E/W road
        eff.setTile(0, 0, HROADPOWER);
        break;

      case ROADS2: // wire on N/S road
        eff.setTile(0, 0, VROADPOWER);
        break;

      case LHRAIL: // wire on E/W railroad tracks
        eff.setTile(0, 0, RAILHPOWERV);
        break;

      case LVRAIL: // wire on N/S railroad tracks
        eff.setTile(0, 0, RAILVPOWERH);
        break;

      default:
        int clearing = clearingCost(tile);
        if (clearing == CANNOT_CLEAR) {
          // cannot do wire here
          return false;
        }
        cost += clearing;

        // wire on dirt
        eff.setTile(0, 0, LHPOWER);
        break;
    }

    eff.spend(cost);
    return true;
  }

  /**
   * Extra cost of clearing {@code tile} to make room for a road, rail or wire: nothing for bare
   * dirt, {@link #AUTO_BULLDOZE_COST} for rubble and the like when auto-bulldoze is on, and {@link
   * #CANNOT_CLEAR} otherwise.
   */
  private int clearingCost(char tile) {
    if (tile == DIRT) {
      return 0;
    }
    if (getCity().isAutoBulldoze() && canAutoBulldozeRRW(tile)) {
      return AUTO_BULLDOZE_COST;
    }
    return CANNOT_CLEAR;
  }

  /**
   * Lays, over the water at (0, 0), the tile that carries a road, rail or wire, if a neighbor
   * allows it. The neighbors are tried in the order east, west, south, north, and the first match
   * wins.
   *
   * @return false, leaving the tile untouched, if no neighbor allows the crossing
   */
  private static boolean layAcrossWater(ToolEffectIfc eff, WaterCrossing crossing) {
    if (crossing.east().test(eff.getTile(1, 0)) || crossing.west().test(eff.getTile(-1, 0))) {
      eff.setTile(0, 0, crossing.eastWestTile());
      return true;
    }
    if (crossing.south().test(eff.getTile(0, 1)) || crossing.north().test(eff.getTile(0, -1))) {
      eff.setTile(0, 0, crossing.northSouthTile());
      return true;
    }
    return false;
  }

  /**
   * How a road, rail or wire crosses water: for each neighbor of the water tile, whether it lets
   * the crossing continue in that direction, and the tile placed over the water when it does.
   *
   * @param east tests the tile to the east of the water
   * @param west tests the tile to the west of the water
   * @param south tests the tile to the south of the water
   * @param north tests the tile to the north of the water
   * @param eastWestTile placed when the east or the west neighbor matches
   * @param northSouthTile placed when the south or the north neighbor matches
   */
  private record WaterCrossing(
      IntPredicate east,
      IntPredicate west,
      IntPredicate south,
      IntPredicate north,
      char eastWestTile,
      char northSouthTile) {}

  private static final WaterCrossing BRIDGE =
      new WaterCrossing(
          RoadLikeTool::bridgeReachesEast,
          RoadLikeTool::bridgeReachesWest,
          RoadLikeTool::bridgeReachesNorthOrSouth,
          RoadLikeTool::bridgeReachesNorthOrSouth,
          HBRIDGE,
          VBRIDGE);

  private static final WaterCrossing TUNNEL =
      new WaterCrossing(
          RoadLikeTool::tunnelReachesEastOrWest,
          RoadLikeTool::tunnelReachesEastOrWest,
          RoadLikeTool::tunnelReachesNorthOrSouth,
          RoadLikeTool::tunnelReachesNorthOrSouth,
          HRAIL,
          VRAIL);

  // As in the original code, an east or west neighbor places VPOWER, and a north or south one
  // places HPOWER.
  private static final WaterCrossing UNDERWATER_WIRE =
      new WaterCrossing(
          TileConstants::wireConnectsWest,
          TileConstants::wireConnectsEast,
          TileConstants::wireConnectsNorth,
          TileConstants::wireConnectsSouth,
          VPOWER,
          HPOWER);

  // The predicates below test the tile next to the water, to see whether a bridge or tunnel can
  // reach it. Road tiles that carry traffic are first converted to their plain equivalent.

  private static boolean bridgeReachesEast(int tile) {
    int t = neutralizeRoad(tile);
    return t == VRAILROAD || t == HBRIDGE || t >= TileConstants.ROADS && t <= HROADPOWER;
  }

  // NOTE: unlike bridgeReachesEast, this does not accept HROADPOWER. The asymmetry is kept as it
  // was in the original code.
  private static boolean bridgeReachesWest(int tile) {
    int t = neutralizeRoad(tile);
    return t == VRAILROAD || t == HBRIDGE || t >= TileConstants.ROADS && t <= INTERSECTION;
  }

  private static boolean bridgeReachesNorthOrSouth(int tile) {
    int t = neutralizeRoad(tile);
    return t == HRAILROAD || t == VROADPOWER || t >= VBRIDGE && t <= INTERSECTION;
  }

  private static boolean tunnelReachesEastOrWest(int tile) {
    int t = neutralizeRoad(tile);
    return t == RAILHPOWERV || t == HRAIL || t >= LHRAIL && t <= HRAILROAD;
  }

  private static boolean tunnelReachesNorthOrSouth(int tile) {
    int t = neutralizeRoad(tile);
    return t == RAILVPOWERH || t == VRAILROAD || t > HRAIL && t < HRAILROAD;
  }
}
