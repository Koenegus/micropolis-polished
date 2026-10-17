// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Manages city notifications, periodic status messages, population growth milestones, sounds, and
 * observer listeners ({@link CityListener}, {@link MapListener}, {@link EarthquakeListener}).
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class CityNotificationManager {

  private final Micropolis city;
  private final Collection<CityListener> cityListeners = new ArrayList<>();
  private final Collection<MapListener> mapListeners = new ArrayList<>();
  private final Collection<EarthquakeListener> earthquakeListeners = new ArrayList<>();

  public CityNotificationManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
  }

  /** Evaluates city conditions and dispatches periodic status/warning messages. */
  public void doMessages() {
    checkGrowth();

    int totalZoneCount =
        city.getResZoneCount() + city.getComZoneCount() + city.getIndZoneCount();
    int powerCount = city.getNuclearCount() + city.getCoalCount();

    int z = city.getCityTime() % 64;
    switch (z) {
      case 1:
        if (totalZoneCount / 4 >= city.getResZoneCount()) {
          sendMessage(MicropolisMessage.NEED_RES);
        }
        break;
      case 5:
        if (totalZoneCount / 8 >= city.getComZoneCount()) {
          sendMessage(MicropolisMessage.NEED_COM);
        }
        break;
      case 10:
        if (totalZoneCount / 8 >= city.getIndZoneCount()) {
          sendMessage(MicropolisMessage.NEED_IND);
        }
        break;
      case 14:
        if (totalZoneCount > 10 && totalZoneCount * 2 > city.getRoadTotal()) {
          sendMessage(MicropolisMessage.NEED_ROADS);
        }
        break;
      case 18:
        if (totalZoneCount > 50 && totalZoneCount > city.getRailTotal()) {
          sendMessage(MicropolisMessage.NEED_RAILS);
        }
        break;
      case 22:
        if (totalZoneCount > 10 && powerCount == 0) {
          sendMessage(MicropolisMessage.NEED_POWER);
        }
        break;
      case 26:
        city.setResCap(city.getResPop() > 500 && city.getStadiumCount() == 0);
        if (city.isResCap()) {
          sendMessage(MicropolisMessage.NEED_STADIUM);
        }
        break;
      case 28:
        city.setIndCap(city.getIndPop() > 70 && city.getSeaportCount() == 0);
        if (city.isIndCap()) {
          sendMessage(MicropolisMessage.NEED_SEAPORT);
        }
        break;
      case 30:
        city.setComCap(city.getComPop() > 100 && city.getAirportCount() == 0);
        if (city.isComCap()) {
          sendMessage(MicropolisMessage.NEED_AIRPORT);
        }
        break;
      case 32:
        int tm = city.getUnpoweredZoneCount() + city.getPoweredZoneCount();
        if (tm != 0) {
          if ((double) city.getPoweredZoneCount() / tm < 0.7) {
            sendMessage(MicropolisMessage.BLACKOUTS);
          }
        }
        break;
      case 35:
        if (city.getPollutionAverage() > 60) {
          sendMessage(MicropolisMessage.HIGH_POLLUTION);
        }
        break;
      case 42:
        if (city.getCrimeAverage() > 100) {
          sendMessage(MicropolisMessage.HIGH_CRIME);
        }
        break;
      case 45:
        if (city.getTotalPop() > 60 && city.getFireStationCount() == 0) {
          sendMessage(MicropolisMessage.NEED_FIRESTATION);
        }
        break;
      case 48:
        if (city.getTotalPop() > 60 && city.getPoliceCount() == 0) {
          sendMessage(MicropolisMessage.NEED_POLICE);
        }
        break;
      case 51:
        if (city.getCityTax() > 12) {
          sendMessage(MicropolisMessage.HIGH_TAXES);
        }
        break;
      case 54:
        if (city.getRoadEffect() < 20 && city.getRoadTotal() > 30) {
          sendMessage(MicropolisMessage.ROADS_NEED_FUNDING);
        }
        break;
      case 57:
        if (city.getFireEffect() < 700 && city.getTotalPop() > 20) {
          sendMessage(MicropolisMessage.FIRE_NEED_FUNDING);
        }
        break;
      case 60:
        if (city.getPoliceEffect() < 700 && city.getTotalPop() > 20) {
          sendMessage(MicropolisMessage.POLICE_NEED_FUNDING);
        }
        break;
      case 63:
        if (city.getTrafficAverage() > 60) {
          sendMessage(MicropolisMessage.HIGH_TRAFFIC);
        }
        break;
      default:
        // nothing
    }
  }

  private void checkGrowth() {
    if (city.getCityTime() % 4 == 0) {
      int newPop = (city.getResPop() + city.getComPop() * 8 + city.getIndPop() * 8) * 20;
      int cityPopulation = city.getCityPopulation();
      if (cityPopulation != 0) {
        MicropolisMessage z = null;
        if (cityPopulation < 500000 && newPop >= 500000) {
          z = MicropolisMessage.POP_500K_REACHED;
        } else if (cityPopulation < 100000 && newPop >= 100000) {
          z = MicropolisMessage.POP_100K_REACHED;
        } else if (cityPopulation < 50000 && newPop >= 50000) {
          z = MicropolisMessage.POP_50K_REACHED;
        } else if (cityPopulation < 10000 && newPop >= 10000) {
          z = MicropolisMessage.POP_10K_REACHED;
        } else if (cityPopulation < 2000 && newPop >= 2000) {
          z = MicropolisMessage.POP_2K_REACHED;
        }
        if (z != null) {
          sendMessage(z);
        }
      }
      city.getCensusManager().setCityPopulation(newPop);
    }
  }

  public void sendMessage(MicropolisMessage message) {
    fireCityMessage(message, null);
  }

  public void sendMessageAt(MicropolisMessage message, int x, int y) {
    fireCityMessage(message, new CityLocation(x, y));
  }

  public void makeSound(int x, int y, Sound sound) {
    fireCitySound(sound, new CityLocation(x, y));
  }

  public void addListener(CityListener l) {
    cityListeners.add(l);
  }

  public void removeListener(CityListener l) {
    cityListeners.remove(l);
  }

  public void addEarthquakeListener(EarthquakeListener l) {
    earthquakeListeners.add(l);
  }

  public void removeEarthquakeListener(EarthquakeListener l) {
    earthquakeListeners.remove(l);
  }

  public void addMapListener(MapListener l) {
    mapListeners.add(l);
  }

  public void removeMapListener(MapListener l) {
    mapListeners.remove(l);
  }

  public void fireCensusChanged() {
    for (CityListener l : cityListeners) {
      l.censusChanged();
    }
  }

  public void fireCityMessage(MicropolisMessage message, CityLocation loc) {
    for (CityListener l : cityListeners) {
      l.cityMessage(message, loc);
    }
  }

  public void fireCitySound(Sound sound, CityLocation loc) {
    for (CityListener l : cityListeners) {
      l.citySound(sound, loc);
    }
  }

  public void fireDemandChanged() {
    for (CityListener l : cityListeners) {
      l.demandChanged();
    }
  }

  public void fireEarthquakeStarted() {
    for (EarthquakeListener l : earthquakeListeners) {
      l.earthquakeStarted();
    }
  }

  public void fireEvaluationChanged() {
    for (CityListener l : cityListeners) {
      l.evaluationChanged();
    }
  }

  public void fireFundsChanged() {
    for (CityListener l : cityListeners) {
      l.fundsChanged();
    }
  }

  public void fireMapOverlayDataChanged() {
    for (MapListener l : mapListeners) {
      l.mapOverlayDataChanged();
    }
  }

  public void fireOptionsChanged() {
    for (CityListener l : cityListeners) {
      l.optionsChanged();
    }
  }

  public void fireSpriteMoved(Sprite sprite) {
    for (MapListener l : mapListeners) {
      l.spriteMoved(sprite);
    }
  }

  public void fireTileChanged(int xpos, int ypos) {
    for (MapListener l : mapListeners) {
      l.tileChanged(xpos, ypos);
    }
  }

  public void fireWholeMapChanged() {
    for (MapListener l : mapListeners) {
      l.wholeMapChanged();
    }
  }
}
