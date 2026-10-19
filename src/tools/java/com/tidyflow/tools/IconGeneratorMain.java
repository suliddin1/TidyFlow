package com.tidyflow.tools;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only Java2D utility (in the tools source set, not shipped in the app jar) that renders
 * the TidyFlow icon at 16, 32, 48, 64, 128, and 256 px and writes PNGs plus multi-size .ico files.
 */
public final class IconGeneratorMain {

    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private IconGeneratorMain() {
    }

    public static void main(String[] args) throws IOException {
        Path projectRoot = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        Path iconsDir = projectRoot.resolve("src/main/resources/icons");
        Path packagingDir = projectRoot.resolve("packaging/windows");
        Files.createDirectories(iconsDir);
        Files.createDirectories(packagingDir);

        List<byte[]> pngPayloads = new ArrayList<>(SIZES.length);
        for (int size : SIZES) {
            BufferedImage img = renderIcon(size);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            byte[] pngBytes = baos.toByteArray();
            pngPayloads.add(pngBytes);

            Path pngPath = iconsDir.resolve("icon-" + size + ".png");
            Files.write(pngPath, pngBytes);
        }

        byte[] icoBytes = buildMultiSizeIco(SIZES, pngPayloads);
        Files.write(iconsDir.resolve("icon.ico"), icoBytes);
        Files.write(packagingDir.resolve("icon.ico"), icoBytes);
        System.out.println("Generated TidyFlow PNG icons (16..256) and multi-size icon.ico");
    }

    private static BufferedImage renderIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            float s = size;
            float pad = Math.max(1f, s * 0.06f);
            float box = s - 2f * pad;
            float arc = s * 0.24f;

            // Rounded gradient tile background
            GradientPaint bgPaint = new GradientPaint(
                    pad, pad, new Color(37, 99, 235),
                    pad + box, pad + box, new Color(29, 78, 216)
            );
            g.setPaint(bgPaint);
            g.fill(new RoundRectangle2D.Float(pad, pad, box, box, arc, arc));

            // Folder silhouette + flow arrow in crisp white
            g.setColor(new Color(255, 255, 255, 235));
            float folderLeft = s * 0.22f;
            float folderTop = s * 0.30f;
            float folderWidth = s * 0.56f;
            float folderHeight = s * 0.42f;
            float tabWidth = s * 0.24f;
            float tabHeight = s * 0.07f;

            GeneralPath folder = new GeneralPath();
            folder.moveTo(folderLeft, folderTop + tabHeight);
            folder.lineTo(folderLeft + tabWidth * 0.8f, folderTop + tabHeight);
            folder.lineTo(folderLeft + tabWidth, folderTop + tabHeight * 2f);
            folder.lineTo(folderLeft + folderWidth, folderTop + tabHeight * 2f);
            folder.lineTo(folderLeft + folderWidth, folderTop + folderHeight);
            folder.lineTo(folderLeft, folderTop + folderHeight);
            folder.closePath();
            g.fill(folder);

            // Accent flow checkmark / arrow inside the folder
            g.setColor(new Color(29, 78, 216));
            float strokeW = Math.max(1.4f, s * 0.075f);
            g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            GeneralPath check = new GeneralPath();
            check.moveTo(s * 0.36f, s * 0.54f);
            check.lineTo(s * 0.46f, s * 0.63f);
            check.lineTo(s * 0.64f, s * 0.45f);
            g.draw(check);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] buildMultiSizeIco(int[] sizes, List<byte[]> pngPayloads) throws IOException {
        int count = sizes.length;
        int headerAndDirSize = 6 + (count * 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        // ICONDIR (6 bytes, little-endian)
        ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0);     // reserved
        header.putShort((short) 1);     // type 1 = ICO
        header.putShort((short) count); // image count
        dos.write(header.array());

        int currentOffset = headerAndDirSize;
        for (int i = 0; i < count; i++) {
            int sz = sizes[i];
            byte[] payload = pngPayloads.get(i);
            ByteBuffer entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            entry.put((byte) (sz >= 256 ? 0 : sz)); // width (0 means 256)
            entry.put((byte) (sz >= 256 ? 0 : sz)); // height (0 means 256)
            entry.put((byte) 0);                    // color palette
            entry.put((byte) 0);                    // reserved
            entry.putShort((short) 1);              // color planes
            entry.putShort((short) 32);             // bits per pixel
            entry.putInt(payload.length);           // bytes in resource
            entry.putInt(currentOffset);            // image offset
            dos.write(entry.array());
            currentOffset += payload.length;
        }

        for (byte[] payload : pngPayloads) {
            dos.write(payload);
        }
        dos.flush();
        return out.toByteArray();
    }
}
