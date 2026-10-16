// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.gui;

import static micropolisj.engine.TileConstants.LOMASK;
import static micropolisj.engine.TileSpec.generateTileNames;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import micropolisj.engine.SpriteKind;
import micropolisj.engine.TileSpec;

public class TileImages {

  private static final int STD_SIZE = 16;
  private static final Map<Integer, TileImages> savedInstances = new HashMap<>();
  private final int tileWidth;
  private final int tileHeight;
  private final BufferedImage[] images;
  private Map<SpriteKind, Map<Integer, Image>> spriteImages;

  private TileImages(int size) {
    tileWidth = size;
    tileHeight = size;

    images = loadTileImages();
    loadSpriteImages();
  }

  public static TileImages getInstance(int size) {
    if (!savedInstances.containsKey(size)) {
      savedInstances.put(size, new TileImages(size));
    }
    return savedInstances.get(size);
  }

  private static FrameSpec parseFrameSpec(TileSpec spec) {
    FrameSpec result = null;

    for (String layerStr : spec.getImages()) {

      FrameSpec rv = new FrameSpec();
      result = rv;

      String[] parts = layerStr.split("@", 2);
      rv.setImage(loadImage(parts[0]));

      if (parts.length >= 2) {
        String offsetInfo = parts[1];
        parts = offsetInfo.split(",");
        if (parts.length >= 1) {
          rv.setOffsetX(Integer.parseInt(parts[0]));
        }
        if (parts.length >= 2) {
          rv.setOffsetY(Integer.parseInt(parts[1]));
        }
      } // endif something given after '@' in image specifier
    } // end foreach layer in image specification

    return result;
  }

  private static SourceImage loadImage(String fileName) {
    URL pngFile = TileImages.class.getResource("/graphics/" + fileName + ".png");
    if (pngFile == null && "university".equals(fileName)) {
      return new SourceImage(createUniversityImage());
    }
    ImageIcon ii = new ImageIcon(pngFile);
    return new SourceImage(ii.getImage());
  }

  /** Generates the 64x64 pixel-art sprite for the 4x4 University building. */
  public static BufferedImage createUniversityImage() {
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();

    // Classic SimCity terracotta ground
    g.setColor(new Color(214, 133, 105));
    g.fillRect(0, 0, 64, 64);

    // Outer dark green border
    g.setColor(new Color(0, 105, 0));
    g.drawRect(0, 0, 63, 63);

    // Central green quad lawn
    g.setColor(new Color(34, 170, 34));
    g.fillRect(14, 18, 36, 32);
    g.setColor(new Color(0, 110, 0));
    g.drawRect(14, 18, 35, 31);

    // Stone walkways across quad
    g.setColor(new Color(185, 185, 185));
    g.fillRect(30, 18, 4, 45);
    g.fillRect(14, 32, 36, 4);

    // Main Hall (North wing)
    g.setColor(new Color(155, 50, 40));
    g.fillRect(8, 4, 48, 14);
    g.setColor(new Color(60, 20, 15));
    g.drawRect(8, 4, 47, 13);
    g.setColor(new Color(85, 100, 125));
    g.fillRect(10, 6, 44, 7);
    g.setColor(new Color(230, 240, 255));
    for (int x = 12; x < 52; x += 4) {
      g.fillRect(x, 14, 2, 2);
    }

    // Clock / Bell Tower in center
    g.setColor(new Color(180, 70, 50));
    g.fillRect(27, 2, 10, 16);
    g.setColor(new Color(60, 20, 15));
    g.drawRect(27, 2, 9, 15);
    g.setColor(new Color(50, 70, 115));
    g.fillRect(28, 3, 8, 4);
    g.setColor(new Color(250, 250, 210));
    g.fillRect(30, 8, 4, 4);
    g.setColor(new Color(20, 20, 20));
    g.fillRect(31, 9, 2, 1);
    g.fillRect(31, 10, 1, 1);
    g.setColor(new Color(80, 40, 20));
    g.fillRect(30, 14, 4, 4);

    // West Academic Wing
    g.setColor(new Color(160, 55, 45));
    g.fillRect(4, 18, 11, 37);
    g.setColor(new Color(60, 20, 15));
    g.drawRect(4, 18, 10, 36);
    g.setColor(new Color(95, 110, 135));
    g.fillRect(6, 20, 7, 33);
    g.setColor(new Color(230, 240, 255));
    for (int y = 22; y < 51; y += 5) {
      g.fillRect(13, y, 1, 3);
    }

    // East Library & Observatory Wing
    g.setColor(new Color(160, 55, 45));
    g.fillRect(49, 18, 11, 37);
    g.setColor(new Color(60, 20, 15));
    g.drawRect(49, 18, 10, 36);
    g.setColor(new Color(95, 110, 135));
    g.fillRect(51, 20, 7, 33);
    g.setColor(new Color(215, 225, 240));
    g.fillRect(51, 24, 7, 7);
    g.setColor(new Color(80, 90, 105));
    g.drawRect(51, 24, 6, 6);
    g.setColor(new Color(230, 240, 255));
    for (int y = 35; y < 51; y += 5) {
      g.fillRect(50, y, 1, 3);
    }

    // South Gate columns
    g.setColor(new Color(200, 200, 200));
    g.fillRect(22, 52, 7, 7);
    g.fillRect(35, 52, 7, 7);
    g.setColor(new Color(90, 90, 90));
    g.drawRect(22, 52, 6, 6);
    g.drawRect(35, 52, 6, 6);

    // Campus trees
    int[][] trees = {
      {5, 6}, {57, 6}, {18, 22}, {43, 22}, {18, 42}, {43, 42}, {8, 57}, {16, 57}, {46, 57}, {54, 57}
    };
    for (int[] t : trees) {
      g.setColor(new Color(0, 210, 0));
      g.fillRect(t[0], t[1], 3, 3);
      g.setColor(new Color(0, 140, 0));
      g.fillRect(t[0] + 1, t[1] + 1, 1, 1);
    }
    g.dispose();
    return img;
  }

  /** Generates the 42x42 toolbar icon for the University tool (normal or selected state). */
  public static ImageIcon createUniversityToolIcon(boolean selected) {
    BufferedImage univ = createUniversityImage();
    BufferedImage icon = new BufferedImage(42, 42, BufferedImage.TYPE_INT_ARGB);
    String baseFrameRes = selected ? "/icstadhi.png" : "/icstad.png";
    URL frameUrl = TileImages.class.getResource(baseFrameRes);
    if (frameUrl != null) {
      try {
        BufferedImage frame = ImageIO.read(frameUrl);
        Graphics2D g = icon.createGraphics();
        g.drawImage(frame, 0, 0, null);
        g.dispose();
      } catch (IOException ignored) {
        // fallback below
      }
    }
    for (int y = 0; y < 32; y++) {
      for (int x = 0; x < 32; x++) {
        int rgb = univ.getRGB(x * 2, y * 2);
        if (selected) {
          icon.setRGB(5 + x, 5 + y, 0xFF000000 | rgb);
        } else {
          int r = (rgb >> 16) & 0xFF;
          int gr = (rgb >> 8) & 0xFF;
          int b = rgb & 0xFF;
          if (r > 190 && gr > 110 && gr < 160 && b < 130) {
            int c = ((x + y) % 2 == 0) ? 0xFFFFFFFF : 0xFF000000;
            icon.setRGB(5 + x, 5 + y, c);
          } else {
            icon.setRGB(5 + x, 5 + y, 0xFF000000 | rgb);
          }
        }
      }
    }
    return new ImageIcon(icon);
  }

  public BufferedImage getTileImage(int cell) {
    int tile = (cell & LOMASK) % images.length;
    return images[tile];
  }

  private BufferedImage[] loadTileImages() {

    Properties recipe = new Properties();
    try (InputStream recipeFile = TileImages.class.getResourceAsStream("/graphics/tiles.rc")) {
      recipe.load(new InputStreamReader(recipeFile, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load tiles recipe file tiles.rc", e);
    }

    String[] tileNames = generateTileNames(recipe);
    int ntiles = tileNames.length;
    BufferedImage[] images = new BufferedImage[ntiles];

    GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsDevice dev = env.getDefaultScreenDevice();
    GraphicsConfiguration conf = dev.getDefaultConfiguration();

    for (int tileNumber = 0; tileNumber < ntiles; tileNumber++) {

      BufferedImage bi = conf.createCompatibleImage(tileWidth, tileHeight, Transparency.OPAQUE);
      Graphics2D gr = bi.createGraphics();

      String tileName = tileNames[tileNumber];
      String rawSpec = recipe.getProperty(tileName);
      assert rawSpec != null;

      TileSpec tileSpec = TileSpec.parse(tileNumber, tileName, rawSpec, recipe);
      FrameSpec ref = parseFrameSpec(tileSpec);
      if (ref == null) {
        // tile is defined, but it has no images
        continue;
      }

      SourceImage sourceImg = ref.getImage();
      gr.drawImage(
          sourceImg.getImage(),
          0,
          0,
          tileWidth,
          tileHeight,
          ref.getOffsetX(),
          ref.getOffsetY(),
          (ref.getOffsetX() + STD_SIZE),
          (ref.getOffsetY() + STD_SIZE),
          null);

      images[tileNumber] = bi;
    }
    return images;
  }

  public Image getSpriteImage(SpriteKind kind, int frameNumber) {
    return spriteImages.get(kind).get(frameNumber);
  }

  private void loadSpriteImages() {
    spriteImages = new EnumMap<>(SpriteKind.class);
    for (SpriteKind kind : SpriteKind.values()) {
      Map<Integer, Image> imgs = new HashMap<>();
      for (int i = 0; i < kind.numFrames; i++) {
        Image img = loadSpriteImage(kind, i);
        if (img != null) {
          imgs.put(i, img);
        }
      }
      spriteImages.put(kind, imgs);
    }
  }

  private Image loadSpriteImage(SpriteKind kind, int frameNo) {
    String resourceName = "/obj" + kind.objectId + "-" + frameNo;

    // first, try to load specific size image
    URL iconUrl =
        TileImages.class.getResource(resourceName + "_" + tileWidth + "x" + tileHeight + ".png");
    if (iconUrl != null) {
      return new ImageIcon(iconUrl).getImage();
    }

    iconUrl = TileImages.class.getResource(resourceName + ".png");
    if (iconUrl == null) return null;

    if (tileWidth == 16 && tileHeight == 16) {
      return new ImageIcon(iconUrl).getImage();
    }

    // scale the image ourselves
    ImageIcon ii = new ImageIcon(iconUrl);
    int destWidth = ii.getIconWidth() * tileWidth / 16;
    int destHeight = ii.getIconHeight() * tileHeight / 16;

    GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsDevice dev = env.getDefaultScreenDevice();
    GraphicsConfiguration conf = dev.getDefaultConfiguration();
    BufferedImage bi = conf.createCompatibleImage(destWidth, destHeight, Transparency.TRANSLUCENT);
    Graphics2D gr = bi.createGraphics();

    gr.drawImage(
        ii.getImage(),
        0,
        0,
        destWidth,
        destHeight,
        0,
        0,
        ii.getIconWidth(),
        ii.getIconHeight(),
        null);
    return bi;
  }

  public int getTileWidth() {
    return tileWidth;
  }

  public int getTileHeight() {
    return tileHeight;
  }
}
