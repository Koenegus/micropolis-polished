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
import static micropolisj.engine.TileConstants.LOMASK;
import static micropolisj.engine.TileConstants.REDGE;
import static micropolisj.engine.TileConstants.RIVEDGE;
import static micropolisj.engine.TileConstants.RIVER;
import static micropolisj.engine.TileConstants.WOODS;
import static micropolisj.engine.TileConstants.WOODS_HIGH;
import static micropolisj.engine.TileConstants.WOODS_LOW;
import static micropolisj.engine.TileConstants.isTree;

import java.util.Arrays;
import java.util.Random;

/**
 * Contains the code for generating a random map terrain.
 *
 * <p>Refatorado com base nas técnicas de "Refatoração: Melhorando a Qualidade de Código
 * Pré-Existente" (Martin Fowler / Profa. Andréa Bordin):
 *
 * <ul>
 *   <li><b>Duplicated Code &rarr; Extract Method / Parameterize Method:</b> Unificação da navegação
 *       dos rios ({@code doRiver}) e do carimbo de matrizes ({@code plopRiver}), eliminando
 *       duplicação entre {@code doBRiv}/{@code doSRiv} e {@code BRivPlop}/{@code SRivPlop}.
 *   <li><b>Duplicated Code &rarr; Extract Method:</b> Extração do cálculo da máscara de vizinhança
 *       de 4 direções ({@code compute4NeighborMask}), compartilhada entre {@code smoothRiver()} e
 *       {@code smoothTrees()}.
 *   <li><b>Decompose Conditional / Extract Method:</b> Isolamento da condição complexa de vizinhos
 *       em {@code isWaterOrNonTree()} com intenção clara.
 *   <li><b>Eliminação de Shadowing de Atributos e Clean Code:</b> Remoção de variáveis locais
 *       {@code mapX}/{@code mapY} que sombreavam os atributos de instância.
 *   <li><b>Replace Magic Numbers with Constants:</b> Substituição de literais mágicos de
 *       probabilidade por constantes nomeadas.
 * </ul>
 */
public class MapGenerator {

  @FunctionalInterface
  private interface TilePredicate {
    boolean test(char tile);
  }

  // Constantes de probabilidade e variação de direção dos rios
  private static final int RIVER_RESET_CHANCE_BOUND = 100;
  private static final int RIVER_RESET_THRESHOLD = 10;
  private static final int RIVER_WANDER_BOUND = 200;
  private static final int RIVER_WANDER_THRESHOLD = 90;

  private static final char[][] BRMatrix = {
    {0, 0, 0, 3, 3, 3, 0, 0, 0},
    {0, 0, 3, 2, 2, 2, 3, 0, 0},
    {0, 3, 2, 2, 2, 2, 2, 3, 0},
    {3, 2, 2, 2, 2, 2, 2, 2, 3},
    {3, 2, 2, 2, 4, 2, 2, 2, 3},
    {3, 2, 2, 2, 2, 2, 2, 2, 3},
    {0, 3, 2, 2, 2, 2, 2, 3, 0},
    {0, 0, 3, 2, 2, 2, 3, 0, 0},
    {0, 0, 0, 3, 3, 3, 0, 0, 0}
  };

  private static final char[][] SRMatrix = {
    {0, 0, 3, 3, 0, 0},
    {0, 3, 2, 2, 3, 0},
    {3, 2, 2, 2, 2, 3},
    {3, 2, 2, 2, 2, 3},
    {0, 3, 2, 2, 3, 0},
    {0, 0, 3, 3, 0, 0}
  };

  private static final char[] REdTab = {
    RIVEDGE + 8,
    RIVEDGE + 8,
    RIVEDGE + 12,
    RIVEDGE + 10,
    RIVEDGE,
    RIVER,
    RIVEDGE + 14,
    RIVEDGE + 12,
    RIVEDGE + 4,
    RIVEDGE + 6,
    RIVER,
    RIVEDGE + 8,
    RIVEDGE + 2,
    RIVEDGE + 4,
    RIVEDGE,
    RIVER
  };

  private static final int[] DIRECTION_TABX = {0, 1, 1, 1, 0, -1, -1, -1};
  private static final int[] DIRECTION_TABY = {-1, -1, 0, 1, 1, 1, 0, -1};
  private static final int[] DX = {-1, 0, 1, 0};
  private static final int[] DY = {0, 1, 0, -1};

  private static final char[] TEdTab = {
    0, 0, 0, 34,
    0, 0, 36, 35,
    0, 32, 0, 33,
    30, 31, 29, 37
  };

  private final Micropolis engine;
  private final char[][] map;
  private final CreateIsland createIsland = CreateIsland.SELDOM;
  private Random random;
  private int xStart;
  private int yStart;
  private int mapX;
  private int mapY;
  private int dir;
  private int lastDir;

  public MapGenerator(Micropolis engine) {
    assert engine != null;
    this.engine = engine;
    map = engine.getMap();
  }

  private int getWidth() {
    return map[0].length;
  }

  private int getHeight() {
    return map.length;
  }

  /** Generate a random map terrain. */
  public void generateNewCity() {
    long r = Micropolis.DEFAULT_PRNG.nextLong();
    generateSomeCity(r);
  }

  private void generateSomeCity(long r) {
    generateMap(r);
    engine.fireWholeMapChanged();
  }

  private void generateMap(long r) {
    random = new Random(r);

    if (createIsland == CreateIsland.SELDOM) {
      if (random.nextInt(100) < 10) { // chance that island is generated
        makeIsland();
        return;
      }
    }

    if (createIsland == CreateIsland.ALWAYS) {
      makeNakedIsland();
    } else {
      clearMap();
    }

    getRandStart();
    doRivers();
    makeLakes();
    smoothRiver();
    doTrees();
  }

  private void makeIsland() {
    makeNakedIsland();
    smoothRiver();
    doTrees();
  }

  private int erand() {
    return Math.min(random.nextInt(19), random.nextInt(19));
  }

  private void makeNakedIsland() {
    int worldX = getWidth();
    int worldY = getHeight();

    for (int y = 0; y < worldY; y++) {
      for (int x = 0; x < worldX; x++) {
        map[y][x] = RIVER;
      }
    }

    for (int y = 5; y < worldY - 5; y++) {
      for (int x = 5; x < worldX - 5; x++) {
        map[y][x] = DIRT;
      }
    }

    for (int x = 0; x < worldX - 5; x += 2) {
      plopHorizontalIslandBorder(x, worldY);
    }

    for (int y = 0; y < worldY - 5; y += 2) {
      plopVerticalIslandBorder(y, worldX);
    }
  }

  private void plopHorizontalIslandBorder(int x, int worldY) {
    mapX = x;
    mapY = erand();
    BRivPlop();
    mapY = worldY - 10 - erand();
    BRivPlop();
    mapY = 0;
    SRivPlop();
    mapY = worldY - 6;
    SRivPlop();
  }

  private void plopVerticalIslandBorder(int y, int worldX) {
    mapY = y;
    mapX = erand();
    BRivPlop();
    mapX = worldX - 10 - erand();
    BRivPlop();
    mapX = 0;
    SRivPlop();
    mapX = worldX - 6;
    SRivPlop();
  }

  private void clearMap() {
    for (char[] chars : map) {
      Arrays.fill(chars, DIRT);
    }
  }

  private void getRandStart() {
    xStart = 40 + random.nextInt(getWidth() - 79);
    yStart = 33 + random.nextInt(getHeight() - 66);

    mapX = xStart;
    mapY = yStart;
  }

  private void makeLakes() {
    int lim1 = random.nextInt(11);

    for (int t = 0; t < lim1; t++) {
      int x = random.nextInt(getWidth() - 20) + 10;
      int y = random.nextInt(getHeight() - 19) + 10;
      int lim2 = random.nextInt(13) + 2;

      for (int z = 0; z < lim2; z++) {
        mapX = x - 6 + random.nextInt(13);
        mapY = y - 6 + random.nextInt(13);

        if (random.nextInt(5) == 0) {
          BRivPlop();
        } else {
          SRivPlop();
        }
      }
    }
  }

  private void doRivers() {
    dir = lastDir = random.nextInt(4);
    doBRiv();

    mapX = xStart;
    mapY = yStart;
    dir = lastDir ^= 4;
    doBRiv();

    mapX = xStart;
    mapY = yStart;
    lastDir = random.nextInt(4);
    doSRiv();
  }

  /**
   * Refatoração Extract Method / Parameterize Method: Unifica a navegação do rio para qualquer
   * matriz (BRMatrix ou SRMatrix), eliminando código duplicado entre doBRiv() e doSRiv().
   */
  private void doRiver(char[][] riverMatrix) {
    int margin = riverMatrix.length / 2;

    while (engine.testBounds(mapX + margin, mapY + margin)) {
      plopRiver(riverMatrix);
      if (random.nextInt(RIVER_RESET_CHANCE_BOUND + 1) < RIVER_RESET_THRESHOLD) {
        dir = lastDir;
      } else {
        if (random.nextInt(RIVER_WANDER_BOUND + 1) > RIVER_WANDER_THRESHOLD) {
          dir++;
        }
        if (random.nextInt(RIVER_WANDER_BOUND + 1) > RIVER_WANDER_THRESHOLD) {
          dir--;
        }
      }
      moveMap(dir);
    }
  }

  private void doBRiv() {
    doRiver(BRMatrix);
  }

  private void doSRiv() {
    doRiver(SRMatrix);
  }

  /**
   * Refatoração Extract Method / Parameterize Method: Itera genericamente pela matriz do rio e
   * aplica os tiles no mapa, eliminando código duplicado entre BRivPlop() e SRivPlop().
   */
  private void plopRiver(char[][] matrix) {
    for (int x = 0; x < matrix.length; x++) {
      for (int y = 0; y < matrix.length; y++) {
        putOnMap(matrix[y][x], x, y);
      }
    }
  }

  private void BRivPlop() {
    plopRiver(BRMatrix);
  }

  private void SRivPlop() {
    plopRiver(SRMatrix);
  }

  private void putOnMap(char mapChar, int xoff, int yoff) {
    if (mapChar == 0) return;

    int xloc = mapX + xoff;
    int yloc = mapY + yoff;

    if (!engine.testBounds(xloc, yloc)) return;

    char tmp = map[yloc][xloc];
    if (tmp != DIRT) {
      tmp &= LOMASK;
      if (tmp == RIVER && mapChar != CHANNEL) return;
      if (tmp == CHANNEL) return;
    }
    map[yloc][xloc] = mapChar;
  }

  /**
   * Refatoração Extract Method: Calcula a máscara binária de 4 vizinhos cardeais (norte, sul,
   * leste, oeste) através de predicado de tile (char), eliminando duplicação entre smoothRiver() e
   * smoothTrees().
   */
  private int compute4NeighborMask(int x, int y, TilePredicate neighborPredicate) {
    int bitindex = 0;
    for (int z = 0; z < 4; z++) {
      bitindex <<= 1;
      int xtem = x + DX[z];
      int ytem = y + DY[z];
      if (engine.testBounds(xtem, ytem) && neighborPredicate.test(map[ytem][xtem])) {
        bitindex |= 1;
      }
    }
    return bitindex & 15;
  }

  /**
   * Decompose Conditional / Extract Method: Verifica se o tile não é terra simples e não é árvore.
   */
  private static boolean isWaterOrNonTree(char rawTile) {
    int tile = rawTile & LOMASK;
    return tile != DIRT && (tile < WOODS_LOW || tile > WOODS_HIGH);
  }

  private void smoothRiver() {
    for (int y = 0; y < map.length; y++) {
      for (int x = 0; x < map[y].length; x++) {
        if (map[y][x] == REDGE) {
          int bitindex = compute4NeighborMask(x, y, MapGenerator::isWaterOrNonTree);
          char temp = REdTab[bitindex];
          if (temp != RIVER && random.nextInt(2) != 0) {
            temp++;
          }
          map[y][x] = temp;
        }
      }
    }
  }

  private void doTrees() {
    int amount = random.nextInt(101) + 50;

    for (int x = 0; x < amount; x++) {
      int xloc = random.nextInt(getWidth());
      int yloc = random.nextInt(getHeight());
      treeSplash(xloc, yloc);
    }

    smoothTrees();
    smoothTrees();
  }

  private void treeSplash(int xloc, int yloc) {
    int dis = random.nextInt(151) + 50;

    mapX = xloc;
    mapY = yloc;

    for (int z = 0; z < dis; z++) {
      int dir = random.nextInt(8);
      moveMap(dir);

      if (!engine.testBounds(mapX, mapY)) return;

      if ((map[mapY][mapX] & LOMASK) == DIRT) {
        map[mapY][mapX] = WOODS;
      }
    }
  }

  private void moveMap(int dir) {
    dir &= 7;
    mapX += DIRECTION_TABX[dir];
    mapY += DIRECTION_TABY[dir];
  }

  private void smoothTrees() {
    for (int y = 0; y < map.length; y++) {
      for (int x = 0; x < map[y].length; x++) {
        if (isTree(map[y][x])) {
          int bitindex = compute4NeighborMask(x, y, TileConstants::isTree);
          char temp = TEdTab[bitindex];
          if (temp != 0 && temp != WOODS && (((x + y) & 1) != 0)) {
            temp -= 8;
          }
          map[y][x] = temp;
        }
      }
    }
  }

  /** Three settings on whether to generate a new map as an island. */
  enum CreateIsland {
    ALWAYS,
    SELDOM // seldom == 10% of the time
  }
}
