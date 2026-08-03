import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class FeatureGraphic {
    public static void main(String[] args) throws Exception {
        int W = 1024, H = 500;
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Background gradient (brand purple)
        Color top = new Color(0x6C, 0x5C, 0xB0);
        Color bottom = new Color(0x4F, 0x40, 0x8C);
        GradientPaint gp = new GradientPaint(0, 0, top, 0, H, bottom);
        g.setPaint(gp);
        g.fillRect(0, 0, W, H);

        String[] files = {"home_check-shot.png", "leave_dialog2-shot.png", "home_amber-shot.png"};
        double[] rotations = {-7, 0, 7};
        int targetH = 470;
        int gapOverlap = 55; // overlap amount between phones

        // compute total width first
        int[] widths = new int[files.length];
        BufferedImage[] imgs = new BufferedImage[files.length];
        for (int i = 0; i < files.length; i++) {
            imgs[i] = ImageIO.read(new File(files[i]));
            widths[i] = (int) Math.round(imgs[i].getWidth() * (targetH / (double) imgs[i].getHeight()));
        }
        int totalW = 0;
        for (int w : widths) totalW += w;
        totalW -= gapOverlap * (files.length - 1);

        int startX = (W - totalW) / 2 + 40; // shift slightly right to leave room for corner branding
        int cornerRadius = 36;

        // draw back-to-front so left one overlaps correctly visually (draw middle last on top)
        int[] order = {0, 2, 1};
        int[] xPositions = new int[files.length];
        int cx = startX;
        for (int i = 0; i < files.length; i++) {
            xPositions[i] = cx;
            cx += widths[i] - gapOverlap;
        }

        for (int idx : order) {
            BufferedImage src = imgs[idx];
            int w = widths[idx];
            int h = targetH;
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gs = scaled.createGraphics();
            gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gs.setClip(new RoundRectangle2D.Double(0, 0, w, h, cornerRadius, cornerRadius));
            gs.drawImage(src, 0, 0, w, h, null);
            gs.dispose();

            double rot = Math.toRadians(rotations[idx]);
            int px = xPositions[idx];
            int py = (H - h) / 2 + 6;

            AffineTransform old = g.getTransform();
            g.translate(px + w / 2.0, py + h / 2.0);
            g.rotate(rot);

            // shadow
            g.setColor(new Color(0, 0, 0, 70));
            g.translate(6, 10);
            g.fill(new RoundRectangle2D.Double(-w / 2.0, -h / 2.0, w, h, cornerRadius, cornerRadius));
            g.translate(-6, -10);

            // frame (slightly larger white rounded rect behind screenshot as bezel)
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new RoundRectangle2D.Double(-w / 2.0 - 6, -h / 2.0 - 6, w + 12, h + 12, cornerRadius + 6, cornerRadius + 6));

            g.drawImage(scaled, -w / 2, -h / 2, null);
            g.setTransform(old);
        }

        g.dispose();

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D go = out.createGraphics();
        go.drawImage(canvas, 0, 0, null);
        go.dispose();

        ImageIO.write(out, "png", new File("feature-graphic-1024x500.png"));
        System.out.println("done " + out.getWidth() + "x" + out.getHeight());
    }
}
